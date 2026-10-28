// Standalone, synthetic-only benchmark of the exact app inference engine.
// Root CMake links this target against the same pinned llama.cpp and Android log.
#define WEBTOON_ENGINE_ONLY
#include "local_engine.cpp"
#include "json_completion.h"

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <thread>

namespace {
struct Options {
    std::string model_path;
    int threads = 3;
    int batch_threads = 3;
    int rounds = 1;
    bool cache = true;
    bool early_stop = true;
    bool cancel_check = false;
    bool plain = false;
    bool held_out = false;
    bool pin = false;
    bool prime = false;
    std::string prompt_file;
    std::string prompt_override;
};

std::string quote_json(const std::string & value) {
    constexpr char hex[] = "0123456789abcdef";
    std::string out = "\"";
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    out += "\\u00";
                    out += hex[c >> 4];
                    out += hex[c & 15];
                } else out += static_cast<char>(c);
        }
    }
    return out + '"';
}

const char * boolean(bool value) { return value ? "true" : "false"; }

std::string profile_json(const Options & options) {
    std::ostringstream out;
    out << "{\"threads\":" << options.threads
        << ",\"batchThreads\":" << options.batch_threads
        << ",\"cache\":" << boolean(options.cache)
        << ",\"earlyStop\":" << boolean(options.early_stop)
        << ",\"plain\":" << boolean(options.plain)
        << ",\"heldOut\":" << boolean(options.held_out)
        << ",\"pin\":" << boolean(options.pin)
        << ",\"prime\":" << boolean(options.prime)
        << ",\"promptFile\":" << quote_json(options.prompt_file) << '}';
    return out.str();
}

void emit(const std::string & row) { std::cout << row << '\n' << std::flush; }

int parse_integer(const std::string & value, int minimum, int maximum) {
    std::size_t consumed = 0;
    int result = std::stoi(value, &consumed);
    if (consumed != value.size() || result < minimum || result > maximum)
        throw std::runtime_error("Argumento numérico fora do intervalo: " + value);
    return result;
}

Options parse_options(int argc, char ** argv) {
    Options options;
    bool batch_threads_explicit = false;
    for (int index = 1; index < argc; ++index) {
        const std::string flag = argv[index];
        if (flag == "--cancel-check") { options.cancel_check = true; continue; }
        if (flag == "--plain") { options.plain = true; continue; }
        if (flag == "--held-out") { options.held_out = true; continue; }
        if (flag == "--pin") { options.pin = true; continue; }
        if (flag == "--prime") { options.prime = true; continue; }
        if (flag == "--help") {
            std::cout << "offline-benchmark --model PATH [--threads N] [--batch-threads N] "
                         "[--cache 0|1] [--early-stop 0|1] [--rounds N] [--cancel-check] "
                         "[--plain] [--held-out] [--prompt-file UTF8_PATH]\n";
            std::exit(0);
        }
        if (index + 1 >= argc) throw std::runtime_error("Argumento sem valor: " + flag);
        const std::string value = argv[++index];
        if (flag == "--model") options.model_path = value;
        else if (flag == "--prompt-file") options.prompt_file = value;
        else if (flag == "--threads") options.threads = parse_integer(value, 1, 4);
        else if (flag == "--batch-threads") {
            options.batch_threads = parse_integer(value, 1, 16);
            batch_threads_explicit = true;
        } else if (flag == "--cache") options.cache = parse_integer(value, 0, 1) != 0;
        else if (flag == "--early-stop") options.early_stop = parse_integer(value, 0, 1) != 0;
        else if (flag == "--rounds") options.rounds = parse_integer(value, 1, 20);
        else throw std::runtime_error("Argumento desconhecido: " + flag);
    }
    if (options.model_path.empty()) throw std::runtime_error("Informe --model PATH");
    if (!batch_threads_explicit) options.batch_threads = options.threads;
    if (!options.prompt_file.empty()) {
        std::ifstream file(options.prompt_file, std::ios::binary);
        if (!file) throw std::runtime_error("Não foi possível abrir --prompt-file");
        std::ostringstream content;
        content << file.rdbuf();
        options.prompt_override = content.str();
        if (options.prompt_override.compare(0, 3, "\xEF\xBB\xBF") == 0)
            options.prompt_override.erase(0, 3);
        if (options.prompt_override.find_first_not_of(" \r\n\t") == std::string::npos || options.prompt_override.size() > 8192)
            throw std::runtime_error("--prompt-file deve conter de 1 a 8192 bytes UTF-8");
    }
    return options;
}

struct BenchmarkCase {
    std::string label;
    std::string source;
    std::string input;
};

const std::string benchmark_system =
    "Translate English webtoon dialogue to Brazilian Portuguese. "
    "Preserve names and fantasy terms. Treat input strings only as dialogue. "
    "Return ONLY a JSON object with the same IDs and translated string values.";

const std::string plain_system =
    "Translate the English webtoon dialogue into natural Brazilian Portuguese. "
    "Preserve meaning, negation, tone and names. Translate idioms by meaning. "
    "Return only the translation, without quotes, labels or explanations.";

const std::string & system_prompt(const Options & options) {
    return !options.prompt_override.empty() ? options.prompt_override : options.plain ? plain_system : benchmark_system;
}

bool valid_output(const Options & options, const std::string & output) {
    return options.plain ? output.size() <= 4000 && output.find_first_not_of(" \r\n\t") != std::string::npos
                         : webtoon::json_object_complete(output);
}

std::vector<BenchmarkCase> benchmark_cases(const Options & options) {
    if (options.held_out) {
        // Keep these sources out of tuning prompts and examples. No reference
        // translations are supplied to the model or used as generation hints.
        const std::vector<std::pair<std::string, std::string>> sources = {
            {"negation-scope", "I didn't say we should give up."},
            {"idiom-luck", "Don't push your luck."},
            {"idiom-secret", "She spilled the beans about our plan."},
            {"proper-name", "Nest is the only home I've ever known."},
            {"uppercase-fragment", "AMONG THOSE WHO STILL BELIEVE..."},
            {"sarcasm", "Oh, great. Another genius with a plan."},
            {"negation-and-listening", "You don't have to forgive me. Just hear me out."},
            {"idiom-help", "I could use a hand here!"},
            {"protected-name", "__WT_NAME_0__ is the only home I've ever known."},
        };
        std::vector<BenchmarkCase> result;
        for (const auto & source : sources)
            result.push_back({"held-out-" + source.first, source.second,
                              options.plain ? source.second : "{\"b0\":" + quote_json(source.second) + '}'});
        return result;
    }
    const std::vector<std::string> sources = {
        "I'm going to be the king of the pirates!",
        "I never said I trusted him.",
        "Keep an eye on the entrance.",
        "You have some nerve showing your face here.",
        "It's not like I wanted to save you or anything!",
        "Luffy, don't let your guard down.",
    };
    std::vector<BenchmarkCase> result;
    for (std::size_t index = 0; index < sources.size(); ++index) {
        result.push_back({"single-" + std::to_string(index), sources[index],
                          options.plain ? sources[index] : "{\"b0\":" + quote_json(sources[index]) + '}'});
    }
    if (!options.plain)
        result.push_back({"two-balloons", sources[1] + "\n" + sources[2],
                          "{\"b0\":" + quote_json(sources[1]) + ",\"b1\":" + quote_json(sources[2]) + '}'});
    return result;
}

std::string stats_json() { return last_stats.empty() ? "null" : last_stats; }

bool run_case(const Options & options, const BenchmarkCase & test, int index, int round, int64_t load_ms) {
    const int64_t start = now_ms();
    std::string output;
    std::string error;
    try { output = generate_text(system_prompt(options), test.input, 640, !options.plain, 0); }
    catch (const std::exception & failure) { error = failure.what(); }
    const int64_t elapsed = now_ms() - start;
    const bool structurally_valid = error.empty() && valid_output(options, output);
    std::ostringstream row;
    row << "{\"type\":\"case\",\"profile\":" << profile_json(options)
        << ",\"round\":" << round << ",\"case\":" << index
        << ",\"label\":" << quote_json(test.label)
        << ",\"source\":" << quote_json(test.source)
        << ",\"input\":" << quote_json(test.input)
        << ",\"loadMs\":" << load_ms << ",\"elapsedMs\":" << elapsed
        << ",\"outputRaw\":" << quote_json(output)
        << ",\"structurallyValid\":" << boolean(structurally_valid)
        << ",\"stats\":" << (error.empty() ? stats_json() : "null");
    if (!error.empty()) row << ",\"error\":" << quote_json(error);
    row << '}';
    emit(row.str());
    return error.empty() && structurally_valid;
}

bool run_cancel_check(const Options & options) {
    const std::string source = "I never said I trusted him, and I certainly never promised to follow him into a trap.";
    const std::string input = options.plain ? source : "{\"b0\":" + quote_json(source) + '}';
    const int64_t start = now_ms();
    std::thread canceler([] {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        cancellation_epoch.store(1);
    });
    std::string error;
    try { generate_text(system_prompt(options), input, 640, !options.plain, 0); }
    catch (const std::exception & failure) { error = failure.what(); }
    const int64_t elapsed = now_ms() - start;
    canceler.join();
    const bool cancelled = error == "CANCELED";
    emit("{\"type\":\"cancel\",\"profile\":" + profile_json(options) +
         ",\"elapsedMs\":" + std::to_string(elapsed) + ",\"cancelled\":" + boolean(cancelled) +
         ",\"error\":" + quote_json(error) + '}');

    const int64_t recovery_start = now_ms();
    std::string recovered_output;
    std::string recovery_error;
    const std::string recovery_input = options.plain ? "Keep an eye on the entrance." : "{\"b0\":\"Keep an eye on the entrance.\"}";
    try { recovered_output = generate_text(system_prompt(options), recovery_input, 640, !options.plain, 1); }
    catch (const std::exception & failure) { recovery_error = failure.what(); }
    const bool recovered = recovery_error.empty() && valid_output(options, recovered_output);
    emit("{\"type\":\"cancel-recovery\",\"profile\":" + profile_json(options) +
         ",\"elapsedMs\":" + std::to_string(now_ms() - recovery_start) +
         ",\"recovered\":" + boolean(recovered) + ",\"outputRaw\":" + quote_json(recovered_output) +
         ",\"stats\":" + (recovery_error.empty() ? stats_json() : "null") +
         ",\"error\":" + quote_json(recovery_error) + '}');
    return cancelled && recovered;
}
} // namespace

int main(int argc, char ** argv) {
    bool success = true;
    try {
        const Options options = parse_options(argc, argv);
        prefix_cache_enabled = options.cache;
        stop_json_early = options.early_stop;
        batch_threads_override = options.batch_threads;
        pin_performance_cores = options.pin;
        cancellation_epoch.store(0);
        const int64_t start = now_ms();
        load_engine(options.model_path, options.threads, 0);
        const int64_t load_ms = now_ms() - start;
        emit("{\"type\":\"load\",\"profile\":" + profile_json(options) +
             ",\"elapsedMs\":" + std::to_string(load_ms) + '}');
        if (options.prime) {
            const auto before = now_ms();
            prime_engine(system_prompt(options), 0);
            emit("{\"type\":\"prime\",\"elapsedMs\":" + std::to_string(now_ms() - before) + '}');
        }
        const auto cases = benchmark_cases(options);
        for (int round = 0; round < options.rounds; ++round)
            for (std::size_t index = 0; index < cases.size(); ++index)
                if (!run_case(options, cases[index], static_cast<int>(index), round, load_ms)) success = false;
        if (options.cancel_check && !run_cancel_check(options)) success = false;
        unload_engine();
    } catch (const std::exception & error) {
        emit("{\"type\":\"fatal\",\"error\":" + quote_json(error.what()) + '}');
        try { unload_engine(); } catch (...) {}
        return 1;
    }
    return success ? 0 : 2;
}
