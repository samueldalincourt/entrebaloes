#include "llama.h"
#include "ggml-cpu.h"
#include "json_completion.h"
#ifndef WEBTOON_ENGINE_ONLY
#include <jni.h>
#endif
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <sstream>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <sys/auxv.h>
#include <asm/hwcap.h>
#include <unistd.h>

namespace {
std::mutex engine_mutex;
llama_model * model = nullptr;
llama_context * context = nullptr;
ggml_threadpool_t cpu_pool = nullptr;
std::atomic<int64_t> cancellation_epoch{0};
std::atomic<int64_t> active_epoch{0};
std::atomic<int64_t> deadline_ms{0};
std::once_flag backend_once;
std::vector<llama_token> cached_prompt;
bool prefix_cache_enabled = true;
bool stop_json_early = true;
bool pin_performance_cores = true;
int batch_threads_override = 0;
std::string last_stats = "{}";

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
bool canceled() { return active_epoch.load() != cancellation_epoch.load(); }
bool abort_decode(void *) {
    const auto deadline = deadline_ms.load();
    return canceled() || (deadline > 0 && now_ms() > deadline);
}
void check_work() {
    if (canceled()) throw std::runtime_error("CANCELED");
    if (deadline_ms.load() > 0 && now_ms() > deadline_ms.load())
        throw std::runtime_error("O modelo excedeu o limite de 60 segundos neste aparelho.");
}
void discard_cache() {
    cached_prompt.clear();
    if (context) llama_memory_clear(llama_get_memory(context), false);
}
void release_engine() {
    cached_prompt.clear(); last_stats = "{}";
    if (context) { llama_detach_threadpool(context); llama_free(context); context = nullptr; }
    if (cpu_pool) { ggml_threadpool_free(cpu_pool); cpu_pool = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}
void unload_engine() {
    std::lock_guard<std::mutex> lock(engine_mutex);
    release_engine();
}
void log_message(ggml_log_level level, const char * message, void *) {
    if (level < GGML_LOG_LEVEL_INFO) return;
    __android_log_write(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
        "WebtoonLlama", message);
}
std::string format_prompt(const std::string & system, const std::string & input) {
    const char * chat_template = llama_model_chat_template(model, nullptr);
    const std::string specification = chat_template ? chat_template : "";
    const bool translation_model = specification.find("<｜hy_User｜>") != std::string::npos;
    // HY-MT is translation-tuned using a user-only task prompt, without a system message.
    const std::string translation_input = "Please translate the following text into Brazilian Portuguese. "
        "Note that the translation style must strictly conform to [natural, colloquial comic dialogue]. "
        "Preserve meaning, names and negation. Output only the translation.\n\n" + input;
    if (translation_model) {
        // The generic built-in Hunyuan-VL formatter reverses user text/role order
        // for this MT template. Match its published GGUF template exactly.
        return "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + translation_input + "<｜hy_Assistant｜>";
    }
    llama_chat_message messages[] = {{"system", system.c_str()}, {"user", input.c_str()}};
    const auto * first = messages;
    const int message_count = 2;
    const int needed = llama_chat_apply_template(chat_template, first, message_count, true, nullptr, 0);
    if (needed <= 0) throw std::runtime_error("Template de conversa do modelo não suportado.");
    std::vector<char> formatted(needed + 1);
    const int written = llama_chat_apply_template(chat_template, first, message_count, true,
        formatted.data(), formatted.size());
    if (written < 0 || written > needed) throw std::runtime_error("Falha ao preparar o prompt.");
    std::string result(formatted.data(), written);
    if (specification.find("enable_thinking") != std::string::npos &&
            specification.find("</think>") != std::string::npos) {
        // Qwen3 non-thinking generation prefix; no hidden reasoning tokens are generated.
        result += "<think>\n\n</think>\n\n";
    }
    return result;
}
constexpr const char * json_grammar = R"GBNF(
root ::= ws "{" ws (string ws ":" ws string (ws "," ws string ws ":" ws string)*)? ws "}" ws
string ::= "\"" char* "\""
char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
ws ::= [ \t\n\r]*
)GBNF";

void load_engine(const std::string & model_path, int threads, int64_t epoch) {
    std::lock_guard<std::mutex> lock(engine_mutex);
    try {
        active_epoch.store(epoch); deadline_ms.store(now_ms() + 60000); check_work();
        if (!model || !context) {
            std::call_once(backend_once, [] { llama_log_set(log_message, nullptr); llama_backend_init(); });
            auto model_params = llama_model_default_params();
            model_params.n_gpu_layers = 0;
            model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
            model_params.progress_callback = [](float, void *) { return !abort_decode(nullptr); };
            model = llama_model_load_from_file(model_path.c_str(), model_params);
            if (!model) { check_work(); throw std::runtime_error("Não foi possível abrir o modelo GGUF verificado."); }
            check_work();
            auto params = llama_context_default_params();
            params.n_ctx = 2048; params.n_batch = 128; params.n_ubatch = 128;
            params.n_threads = std::clamp<int>(threads, 1, 4);
            params.n_threads_batch = batch_threads_override > 0 ? batch_threads_override : params.n_threads;
            params.offload_kqv = false; params.op_offload = false;
            params.abort_callback = abort_decode;
            context = llama_init_from_model(model, params);
            if (!context) throw std::runtime_error("Memória insuficiente para inicializar o modelo local.");
            if (pin_performance_cores) {
                std::vector<int> capacities(std::clamp<long>(sysconf(_SC_NPROCESSORS_CONF), 1, GGML_MAX_N_THREADS));
                int largest = 0;
                for (size_t cpu = 0; cpu < capacities.size(); ++cpu) {
                    std::ifstream file("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpu_capacity");
                    file >> capacities[cpu]; largest = std::max(largest, capacities[cpu]);
                }
                if (largest > 0) {
                    auto pool_params = ggml_threadpool_params_default(params.n_threads);
                    for (size_t cpu = 0; cpu < capacities.size(); ++cpu)
                        pool_params.cpumask[cpu] = capacities[cpu] == largest;
                    pool_params.strict_cpu = true;
                    cpu_pool = ggml_threadpool_new(&pool_params);
                    if (cpu_pool) llama_attach_threadpool(context, cpu_pool, cpu_pool);
                }
            }
        }
        check_work(); deadline_ms.store(0);
    } catch (...) { release_engine(); deadline_ms.store(0); throw; }
}

void prime_engine(const std::string & system, int64_t epoch) {
    std::lock_guard<std::mutex> lock(engine_mutex);
    try {
        active_epoch.store(epoch); deadline_ms.store(now_ms() + 60000); check_work();
        if (!context || !model) throw std::runtime_error("O modelo local ainda não foi carregado.");
        const auto prompt = format_prompt(system, "");
        const auto * vocab = llama_model_get_vocab(model);
        const int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0) throw std::runtime_error("Falha ao preparar o modelo local.");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
            throw std::runtime_error("Falha ao preparar o modelo local.");
        discard_cache();
        for (int offset = 0; offset < count; offset += 128) {
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(128, count - offset));
            if (llama_decode(context, batch) != 0) { check_work(); throw std::runtime_error("Falha ao aquecer o modelo local."); }
            check_work();
        }
        cached_prompt = std::move(tokens);
        deadline_ms.store(0);
    } catch (...) { discard_cache(); deadline_ms.store(0); throw; }
}

std::string generate_text(const std::string & system, const std::string & input,
        int max_tokens, bool json, int64_t epoch) {
    std::lock_guard<std::mutex> lock(engine_mutex);
    try {
        active_epoch.store(epoch); deadline_ms.store(now_ms() + 60000); check_work();
        if (!model || !context) throw std::runtime_error("O modelo local ainda não foi carregado.");
        const auto start = now_ms();
        const auto prompt = format_prompt(system, input);
        const auto * vocab = llama_model_get_vocab(model);
        const int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0) throw std::runtime_error("Falha ao tokenizar o texto.");
        max_tokens = std::clamp<int>(max_tokens, 32, 640);
        if (count + max_tokens > static_cast<int>(llama_n_ctx(context)))
            throw std::runtime_error("O texto excede o contexto do modelo. Reduza o lote de balões.");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
            throw std::runtime_error("Falha ao tokenizar o texto.");

        size_t common = 0;
        if (prefix_cache_enabled) {
            while (common < tokens.size() && common < cached_prompt.size() && tokens[common] == cached_prompt[common]) ++common;
            // Always recompute the last prompt token to refresh logits, including exact repeats.
            common = std::min(common, tokens.size() - 1);
            if (!llama_memory_seq_rm(llama_get_memory(context), 0, common, -1)) {
                discard_cache(); common = 0;
            }
        } else {
            cached_prompt.clear();
            llama_memory_clear(llama_get_memory(context), true);
        }
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (json) {
            auto * grammar = llama_sampler_init_grammar(vocab, json_grammar, "root");
            if (!grammar) throw std::runtime_error("Falha ao preparar o formato JSON.");
            llama_sampler_chain_add(sampler.get(), grammar);
        }
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        for (int offset = static_cast<int>(common); offset < count; offset += 128) {
            check_work();
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(128, count - offset));
            if (llama_decode(context, batch) != 0) {
                check_work(); throw std::runtime_error("Falha ao processar o texto no modelo local.");
            }
        }
        cached_prompt = prefix_cache_enabled ? tokens : std::vector<llama_token>{};
        const auto prefill_end = now_ms();
        std::string output;
        webtoon::JsonCompletion completion;
        bool complete = false;
        int generated = 0;
        for (int step = 0; step < max_tokens; ++step) {
            check_work();
            auto token = llama_sampler_sample(sampler.get(), context, -1);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            char buffer[256];
            int length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
            std::string piece;
            if (length >= 0) piece.assign(buffer, length);
            else {
                std::vector<char> larger(-length);
                length = llama_token_to_piece(vocab, token, larger.data(), larger.size(), 0, false);
                if (length < 0) throw std::runtime_error("Falha ao decodificar a tradução.");
                piece.assign(larger.data(), length);
            }
            output += piece; ++generated;
            if (json && stop_json_early && completion.feed(piece)) { complete = true; break; }
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context, batch) != 0) {
                check_work(); throw std::runtime_error("Falha durante a geração local.");
            }
        }
        check_work();
        if (!complete) throw std::runtime_error("A tradução excedeu o limite de geração.");
        const auto end = now_ms();
        std::ostringstream stats;
        stats << "{\"promptTokens\":" << count << ",\"cachedTokens\":" << common
            << ",\"generatedTokens\":" << generated << ",\"prefillMs\":" << prefill_end - start
            << ",\"generationMs\":" << end - prefill_end << ",\"elapsedMs\":" << end - start << "}";
        last_stats = stats.str();
        __android_log_write(ANDROID_LOG_INFO, "WebtoonLlamaTiming", last_stats.c_str());
        deadline_ms.store(0);
        return output;
    } catch (...) {
        // Aborted decode can leave partial ubatches in KV memory. Never reuse them.
        discard_cache(); deadline_ms.store(0); throw;
    }
}
}

#ifndef WEBTOON_ENGINE_ONLY
namespace {
void fail(JNIEnv * env, const std::exception & error) {
    const bool cancellation = std::string(error.what()) == "CANCELED";
    env->ThrowNew(env->FindClass(cancellation ? "java/util/concurrent/CancellationException" :
        "java/lang/IllegalStateException"), cancellation ? "Tela alterada" : error.what());
}
std::string bytes(JNIEnv * env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_br_com_leitorlocal_tradutor_1webtoon_LlamaNative_buildInfo(JNIEnv * env, jobject) {
    const auto capabilities = getauxval(AT_HWCAP);
    if (!(capabilities & HWCAP_ASIMDDP) || !(capabilities & HWCAP_ASIMDHP)) {
        env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"),
            "Esta versão otimizada requer CPU ARM64 com DOTPROD e FP16.");
        return nullptr;
    }
    return env->NewStringUTF("llama.cpp/" WEBTOON_LLAMA_COMMIT " CPU ARM64 DOTPROD FP16 prefix-cache");
}
extern "C" JNIEXPORT void JNICALL
Java_br_com_leitorlocal_tradutor_1webtoon_LlamaNative_load(JNIEnv * env, jobject,
        jstring file, jint threads, jlong epoch) {
    const char * path = env->GetStringUTFChars(file, nullptr);
    if (!path) return;
    std::string model_path(path); env->ReleaseStringUTFChars(file, path);
    try { load_engine(model_path, threads, epoch); }
    catch (const std::exception & error) { fail(env, error); }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_br_com_leitorlocal_tradutor_1webtoon_LlamaNative_generate(JNIEnv * env, jobject,
        jbyteArray system, jbyteArray input, jint max_tokens, jboolean json, jlong epoch) {
    try {
        const auto output = generate_text(bytes(env, system), bytes(env, input), max_tokens, json, epoch);
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_br_com_leitorlocal_tradutor_1webtoon_LlamaNative_prime(JNIEnv * env, jobject, jbyteArray system, jlong epoch) {
    try { prime_engine(bytes(env, system), epoch); }
    catch (const std::exception & error) { fail(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_br_com_leitorlocal_tradutor_1webtoon_LlamaNative_cancel(JNIEnv *, jobject, jlong epoch) {
    auto previous = cancellation_epoch.load();
    while (previous < epoch && !cancellation_epoch.compare_exchange_weak(previous, epoch)) {}
}
extern "C" JNIEXPORT void JNICALL
Java_br_com_leitorlocal_tradutor_1webtoon_LlamaNative_unload(JNIEnv *, jobject) { unload_engine(); }
#endif
