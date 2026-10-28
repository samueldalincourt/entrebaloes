package br.com.leitorlocal.tradutor_webtoon

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class OnlineTranslator(private val credentials: ApiCredentialStore) {
    private val api = GeminiClient()
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    private var cooldownUntil = 0L
    var benchmark: Map<String, Any?> = emptyMap()
        private set
    val ready get() = credentials.configured && AppState.prefs.getBoolean("onlineVerified", false)
    suspend fun prepare() {
        check(AppState.prefs.getBoolean("onlineTextConsent", false)) { "Confirme o envio de texto ao Gemini no modo online." }
        check(ready) { "Salve a chave e teste a conexão com o Gemini antes de iniciar." }
        withContext(Dispatchers.IO) { credentials.read() }
    }
    fun resetVerification() {
        cancelActive(); benchmark = emptyMap()
        AppState.prefs.edit().putBoolean("onlineVerified", false).apply()
    }
    fun cancelActive() { epoch.incrementAndGet(); api.cancel() }
    suspend fun unload() { cancelActive() }
    suspend fun translate(input: String, context: List<String>): String {
        val expected = epoch.get()
        return mutex.withLock {
            check(SystemClock.elapsedRealtime() >= cooldownUntil) { "Aguarde a cota do Gemini se recuperar ou use o modo offline." }
            if (expected != epoch.get()) throw CancellationException("Tela alterada")
            val key = withContext(Dispatchers.IO) { credentials.read() }
            try {
                api.translate(key, AppState.onlineModel, JSONObject(input), context, AppState.glossary) { expected == epoch.get() }
            } catch (error: GeminiFailure) {
                if (error.code == "QUOTA") cooldownUntil = SystemClock.elapsedRealtime() + 60000
                if (error.code in setOf("AUTH", "MODEL", "BILLING", "SETUP")) AppState.prefs.edit().putBoolean("onlineVerified", false).apply()
                throw error
            }
        }
    }
    suspend fun benchmark(): Map<String, Any?> {
        val source = JSONObject().put("b0", "Among the people who call Nest home.")
            .put("b1", "I didn't say we should attack.").put("b2", "Keep an eye on the entrance.")
        val start = SystemClock.elapsedRealtime()
        val output = JSONObject(translate(source.toString(), source.keys().asSequence().map { source.getString(it) }.toList()))
        benchmark = mapOf("translation" to output.getString("b0"), "negationTranslation" to output.getString("b1"),
            "idiomTranslation" to output.getString("b2"), "elapsedMs" to SystemClock.elapsedRealtime() - start,
            "sentences" to 3, "engine" to AppState.onlineModel)
        AppState.prefs.edit().putBoolean("onlineVerified", true).apply()
        AppState.emit("status")
        return benchmark
    }
}
