package br.com.leitorlocal.tradutor_webtoon

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.plugin.common.EventChannel
import java.util.concurrent.CopyOnWriteArraySet

object AppState {
    // Optional browser shortcut; manual screen translation is not browser-bound.
    const val TARGET = "com.android.chrome"
    const val DEFAULT_TOP_CROP = .05f
    const val DEFAULT_BOTTOM_CROP = .03f
    val main = Handler(Looper.getMainLooper())
    val sinks = CopyOnWriteArraySet<EventChannel.EventSink>()
    lateinit var context: Context
    var targetVisible = false
    var accessibilityConnected = false
    var lastError: String? = null
    var lastMetrics: Map<String, Any?> = emptyMap()
    var lastOcrReport: Map<String, Any?> = emptyMap()
    var scrollEvents = 0
    var pipelineStage = "Sessão parada."
        private set
    fun stage(message: String) {
        if (pipelineStage == message) return
        pipelineStage = message
        android.util.Log.i("WebtoonPipeline", message)
        ScreenTranslationService.active?.updateControlStatus(message)
        emit("status")
    }
    val model by lazy { ModelStore(context) }
    val llm by lazy { LocalTranslator() }
    val fast by lazy { FastTranslator() }
    val credentials by lazy { ApiCredentialStore(context) }
    val online by lazy { OnlineTranslator(credentials) }
    val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val translationMode get() = prefs.getString("translationMode", "fast") ?: "fast"
    val fastMode get() = translationMode == "fast"
    val onlineMode get() = translationMode == "online"
    val onlineModel get() = prefs.getString("onlineModel", GeminiClient.FLASH)?.takeIf { it in GeminiClient.MODELS } ?: GeminiClient.FLASH
    val glossaryText get() = prefs.getString("glossary", "Nest") ?: "Nest"
    val glossary get() = FastTextPolicy.glossary(glossaryText)
    val glossaryHash: String get() = java.security.MessageDigest.getInstance("SHA-256")
            .digest(glossary.joinToString("|").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val fastCacheNamespace get() = "MLKit-translate-17.0.3-en-pt-v2:$glossaryHash"
    val cacheNamespace get() = when (translationMode) {
        "online" -> "Gemini:$onlineModel:${GeminiClient.PROMPT_VERSION}:$glossaryHash"
        "llm" -> "${model.modelId}:native-plain-v1:$glossaryHash"
        else -> fastCacheNamespace
    }
    val runtimeAvailable get() = translationMode != "llm" || llm.available
    val modelReady get() = when (translationMode) { "online" -> online.ready; "llm" -> model.ready; else -> fast.ready }
    val engineName get() = when (translationMode) {
        "online" -> "Gemini $onlineModel (online)"
        "llm" -> LlamaNative.info.getOrNull()
        else -> "ML Kit offline EN-PT"
    }
    val selectedBenchmark get() = when (translationMode) { "online" -> online.benchmark; "llm" -> llm.benchmark; else -> fast.benchmark }
    suspend fun prepareTranslator() { when (translationMode) { "online" -> online.prepare(); "llm" -> llm.prepare(); else -> fast.prepare() } }
    fun cancelTranslation() { when (translationMode) { "online" -> online.cancelActive(); "llm" -> llm.cancelActive(); else -> fast.cancelActive() } }
    suspend fun unloadTranslator() { unloadMode(translationMode) }
    suspend fun unloadMode(mode: String) { when (mode) { "online" -> online.unload(); "llm" -> llm.unload(); else -> fast.unload() } }
    fun emit(type: String, data: Map<String, Any?> = emptyMap()) {
        main.post { sinks.forEach { it.success(mapOf("type" to type) + data) } }
    }
    fun error(message: String) {
        lastError = message
        stage("Falha: $message")
        emit("error", mapOf("message" to message))
    }
    fun status(): Map<String, Any?> {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        return mapOf(
            "overlayPermission" to Settings.canDrawOverlays(context),
            "accessibility" to accessibilityConnected,
            "running" to (ScreenTranslationService.active != null),
            "readingPaused" to (ScreenTranslationService.active?.paused ?: true),
            "targetVisible" to targetVisible,
            "readerInstalled" to (context.packageManager.getLaunchIntentForPackage(TARGET) != null),
            "runtimeAvailable" to runtimeAvailable,
            "translationMode" to translationMode,
            "cacheNamespace" to cacheNamespace,
            "glossary" to glossaryText,
            "engine" to engineName,
            "onlineModel" to onlineModel,
            "onlineKeyConfigured" to credentials.configured,
            "onlineConsent" to prefs.getBoolean("onlineTextConsent", false),
            "modelReady" to modelReady,
            "downloadRunning" to when (translationMode) { "fast" -> fast.downloading; "llm" -> model.downloading; else -> false },
            "downloadProgress" to if (translationMode == "llm") model.progress else 0.0,
            "downloadedBytes" to model.downloadedBytes,
            "totalBytes" to model.totalBytes,
            "lastError" to lastError,
            "metrics" to lastMetrics,
            "scrollEvents" to scrollEvents,
            "pipelineStage" to pipelineStage,
            "topCrop" to prefs.getFloat("topCrop", DEFAULT_TOP_CROP).toDouble(),
            "bottomCrop" to prefs.getFloat("bottomCrop", DEFAULT_BOTTOM_CROP).toDouble(),
            "android" to Build.VERSION.RELEASE,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "ramBytes" to memory.totalMem,
            "availableRamBytes" to memory.availMem,
            "abis" to Build.SUPPORTED_ABIS.toList(),
            "benchmark" to selectedBenchmark,
        )
    }
}
