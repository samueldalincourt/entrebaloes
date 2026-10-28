package br.com.leitorlocal.tradutor_webtoon

import android.os.Debug
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Dedicated on-device EN -> PT translation. Only model downloads use the network. */
class FastTranslator {
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: Translator? = null
    private var prepared = false
    @Volatile var downloading = false
        private set
    val ready get() = AppState.prefs.getBoolean("fastModelReady", false)
    var benchmark: Map<String, Any?> = emptyMap()
        private set

    private suspend fun <T> Task<T>.waitForResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
    private suspend fun ensureModel() {
        if (prepared) return
        val translator = client ?: Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE).build()).also { client = it }
        downloading = true; AppState.emit("status")
        if (!ready) AppState.stage("Preparando o modelo rápido pelo Wi-Fi.")
        try {
            withTimeout(120000) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).waitForResult()
            }
            prepared = true
            AppState.prefs.edit().putBoolean("fastModelReady", true).apply()
        } finally { downloading = false; AppState.emit("status") }
    }
    fun download() {
        if (downloading) return
        scope.launch {
            try {
                prepare()
                if (benchmark.isEmpty() && ScreenTranslationService.active == null) {
                    val report = benchmark()
                    AppState.stage("Modelo rápido pronto. Teste: ${report["elapsedMs"]} ms.")
                }
                AppState.emit("status")
            }
            catch (_: TimeoutCancellationException) { AppState.error("Conecte ao Wi-Fi e tente baixar o modelo rápido novamente.") }
            catch (error: Exception) { AppState.error("Falha ao preparar tradução rápida: ${error.message}") }
        }
    }
    suspend fun prepare() = mutex.withLock { ensureModel() }

    suspend fun translate(input: String): String {
        val expected = epoch.get()
        return mutex.withLock {
            ensureModel()
            val source = JSONObject(input)
            require(source.length() in 1..4) { "Lote de tradução inválido." }
            val output = JSONObject()
            val glossary = AppState.glossary
            for (id in source.keys()) {
                if (epoch.get() != expected) throw CancellationException("Tela alterada")
                val text = source.getString(id)
                require(text.length <= 1200)
                val preparedText = FastTextPolicy.prepare(text, glossary)
                val translated = FastTextPolicy.restore(client!!.translate(preparedText.text).waitForResult(), preparedText)
                if (epoch.get() != expected) throw CancellationException("Tela alterada")
                check(translated.isNotBlank()) { "O modelo rápido retornou uma tradução vazia." }
                output.put(id, translated)
            }
            output.toString()
        }
    }
    fun cancelActive() { epoch.incrementAndGet() }
    suspend fun benchmark(): Map<String, Any?> {
        val start = SystemClock.elapsedRealtime()
        val text = JSONObject(translate(JSONObject().put("b0", "I'm going to be the king of the pirates!")
            .put("b1", ".AMONG THE PESTS THAT CALL NEST HOME.")
            .put("b2", "LIKE I SAID BEFORE,").toString()))
        check(text.getString("b0").lowercase().contains("rei") && text.getString("b0").lowercase().contains("pirata")) {
            "O teste da tradução rápida não retornou o resultado esperado. Confira o diagnóstico."
        }
        check(text.getString("b1").lowercase().contains("entre") && !text.getString("b1").contains("among", true)) {
            "O teste das reticências não foi traduzido corretamente."
        }
        if (AppState.glossary.any { it.equals("Nest", true) }) check(text.getString("b1").contains("Nest", true))
        benchmark = mapOf("translation" to text.getString("b0"),
            "ellipsisTranslation" to text.getString("b1"), "continuationTranslation" to text.getString("b2"),
            "elapsedMs" to SystemClock.elapsedRealtime() - start,
            "processPssKb" to Debug.getPss(), "sentences" to 3, "engine" to "ML Kit offline EN-PT")
        return benchmark
    }
    suspend fun unload() {
        cancelActive()
        mutex.withLock { client?.close(); client = null; prepared = false }
    }
}
