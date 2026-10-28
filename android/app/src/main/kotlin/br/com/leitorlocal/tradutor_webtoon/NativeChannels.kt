package br.com.leitorlocal.tradutor_webtoon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

class NativeChannels(private val context: Context, messenger: BinaryMessenger,
    private val activity: MainActivity?) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val methods = MethodChannel(messenger, "tradutor/commands")
    private val events = EventChannel(messenger, "tradutor/events")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sink: EventChannel.EventSink? = null
    init {
        AppState.context = context.applicationContext
        methods.setMethodCallHandler(this); events.setStreamHandler(this)
        if (activity != null && AppState.fastMode) AppState.fast.download()
        if (activity != null && !AppState.model.ready && AppState.model.hasStagedImport) AppState.model.download()
    }
    override fun onListen(arguments: Any?, sink: EventChannel.EventSink) { this.sink = sink; AppState.sinks.add(sink) }
    override fun onCancel(arguments: Any?) { sink?.let { AppState.sinks.remove(it) }; sink = null }
    private fun launchSettings(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "status" -> result.success(AppState.status())
                "openOverlaySettings" -> {
                    launchSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    result.success(null)
                }
                "openAccessibilitySettings" -> { launchSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); result.success(null) }
                "openTranslationInfo" -> {
                    check(ScreenTranslationService.active == null) { "Pare a sessão antes de abrir a documentação." }
                    launchSettings(Intent(Intent.ACTION_VIEW, Uri.parse("https://cloud.google.com/translate")))
                    result.success(null)
                }
                "openGeminiSetup" -> {
                    check(ScreenTranslationService.active == null) { "Pare a captura antes de abrir a página de chaves." }
                    launchSettings(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                    result.success(null)
                }
                "startSession" -> {
                    check(activity != null) { "Abra a tela de configuração para autorizar a captura." }
                    // Load on a worker before consent, so the first reading pause avoids cold startup.
                    scope.launch {
                        try { AppState.prepareTranslator(); activity.startCapture(result) }
                        catch (error: Throwable) { result.error("MODEL_START_FAILED", error.message, null) }
                    }
                }
                "stopSession" -> { ScreenTranslationService.active?.stopSelf(); result.success(null) }
                "openWebtoon" -> {
                    ReaderLauncher.open(context); result.success(null)
                }
                "setTranslationMode" -> {
                    check(ScreenTranslationService.active == null) { "Pare a sessão antes de mudar o motor." }
                    val mode = call.argument<String>("mode")!!
                    require(mode in setOf("fast", "llm", "online"))
                    if (mode == "online" && !AppState.prefs.getBoolean("onlineTextConsent", false)) {
                        check(call.argument<Boolean>("consent") == true) { "Confirme o envio de texto ao Gemini." }
                        AppState.prefs.edit().putBoolean("onlineTextConsent", true).apply()
                    }
                    val previousMode = AppState.translationMode
                    AppState.prefs.edit().putString("translationMode", mode).apply()
                    if (previousMode != mode) scope.launch { AppState.unloadMode(previousMode) }
                    AppState.targetVisible = false
                    AppState.lastError = null; AppState.lastMetrics = emptyMap()
                    if (AppState.fastMode && !AppState.fast.ready) AppState.fast.download()
                    AppState.emit("status"); result.success(null)
                }
                "configureOnline" -> async(result) {
                    check(ScreenTranslationService.active == null && AppState.onlineMode) { "Selecione o modo online e pare a captura antes de configurar." }
                    val model = call.argument<String>("model")!!
                    require(model in GeminiClient.MODELS)
                    val readerUrl = call.argument<String>("readerUrl")?.trim().orEmpty()
                    val validatedUrl = if (readerUrl.isEmpty()) null else BrowserTargetPolicy.webtoonUrl(readerUrl)
                    check(readerUrl.isEmpty() || validatedUrl != null) { "Use um link HTTPS do site oficial WEBTOON." }
                    val secret = call.argument<String>("apiKey")?.trim().orEmpty()
                    if (secret.isNotEmpty()) withContext(Dispatchers.IO) { AppState.credentials.save(secret) }
                    check(AppState.credentials.configured) { "Insira uma chave do Gemini." }
                    AppState.prefs.edit().putString("onlineModel", model).putBoolean("onlineVerified", false).apply()
                    validatedUrl?.let { AppState.prefs.edit().putString("lastReaderUrl", it).apply() }
                    AppState.online.resetVerification()
                    val report = AppState.online.benchmark()
                    AppState.lastError = null
                    report
                }
                "setOnlineModel" -> {
                    check(ScreenTranslationService.active == null) { "Pare a captura antes de mudar o modelo." }
                    val model = call.argument<String>("model")!!
                    require(model in GeminiClient.MODELS)
                    AppState.online.resetVerification()
                    AppState.prefs.edit().putString("onlineModel", model).putBoolean("onlineVerified", false).apply()
                    AppState.emit("status"); result.success(null)
                }
                "clearOnlineKey" -> async(result) {
                    check(ScreenTranslationService.active == null) { "Pare a sessão antes de remover a chave." }
                    AppState.online.resetVerification()
                    withContext(Dispatchers.IO) { AppState.credentials.clear() }
                    AppState.prefs.edit().putBoolean("onlineVerified", false).apply()
                    AppState.emit("status"); null
                }
                "downloadModel" -> { if (AppState.fastMode) AppState.fast.download() else AppState.model.download(); result.success(null) }
                "pauseDownload" -> { AppState.model.cancel(); result.success(null) }
                "configureCrop" -> {
                    val top = (call.argument<Number>("top")?.toFloat() ?: AppState.DEFAULT_TOP_CROP).coerceIn(0f, .25f)
                    val bottom = (call.argument<Number>("bottom")?.toFloat() ?: AppState.DEFAULT_BOTTOM_CROP).coerceIn(0f, .25f)
                    AppState.prefs.edit().putFloat("topCrop", top).putFloat("bottomCrop", bottom).apply()
                    ScreenTranslationService.active?.motion(); result.success(null)
                }
                "configureGlossary" -> {
                    check(ScreenTranslationService.active == null) { "Pare a sessão antes de mudar os nomes preservados." }
                    val raw = call.argument<String>("terms")!!
                    val terms = FastTextPolicy.glossary(raw)
                    require(raw.length <= 1000 && terms.size <= 30 && terms.all { it.length <= 60 }) { "Use até 30 nomes, com no máximo 60 caracteres cada." }
                    AppState.prefs.edit().putString("glossary", terms.joinToString(", ")).apply()
                    result.success(null)
                }
                "overlayReady" -> { ScreenTranslationService.active?.ready(); result.success(null) }
                "showOverlay" -> {
                    ScreenTranslationService.active?.show(call.argument<Int>("generation")!!); result.success(null)
                }
                "capture" -> {
                    val service = ScreenTranslationService.active
                    if (service == null) result.error("STALE", "Sessão encerrada", null)
                    else service.capture(call.argument<Int>("generation")!!, result)
                }
                "translate" -> async(result) {
                    val generation = call.argument<Int>("generation")!!
                    checkCurrent(generation)
                    val text = when (AppState.translationMode) {
                        "online" -> {
                            checkCurrent(generation)
                            AppState.online.translate(call.argument<String>("input")!!, call.argument<List<String>>("context").orEmpty())
                        }
                        "llm" -> AppState.llm.translate(call.argument<String>("system")!!, call.argument<String>("input")!!)
                        else -> AppState.fast.translate(call.argument<String>("input")!!)
                    }
                    checkCurrent(generation); text
                }
                "benchmark" -> async(result) {
                    check(ScreenTranslationService.active == null) { "Pare a leitura antes de medir o modelo." }
                    when (AppState.translationMode) { "online" -> AppState.online.benchmark(); "llm" -> AppState.llm.benchmark(); else -> AppState.fast.benchmark() }
                }
                "diagnostics" -> async(result) { withContext(Dispatchers.IO) { DeviceDiagnostics.collect(context) } }
                "pipelineError" -> {
                    val generation = call.argument<Int>("generation")
                    if (generation != null && ScreenTranslationService.active?.current(generation) == true) {
                        ScreenTranslationService.active?.pipelineFinished(generation)
                        AppState.error(call.argument<String>("message")!!)
                    }
                    result.success(null)
                }
                "pipelineOcr" -> {
                    if (ScreenTranslationService.active?.current(call.argument<Int>("generation")!!) == true) {
                        AppState.lastOcrReport = mapOf("lines" to call.argument<List<Any>>("lines"),
                            "paragraphs" to call.argument<List<Any>>("paragraphs"))
                    }
                    result.success(null)
                }
                "pipelineStage" -> {
                    if (ScreenTranslationService.active?.current(call.argument<Int>("generation")!!) == true) {
                        AppState.stage(call.argument<String>("message")!!)
                    }
                    result.success(null)
                }
                "pipelineMetrics" -> {
                    val generation = call.argument<Int>("generation")!!
                    if (ScreenTranslationService.active?.current(generation) == true) {
                        AppState.lastError = null
                        AppState.lastMetrics = (call.arguments as Map<*, *>).entries.associate { it.key.toString() to it.value }
                        ScreenTranslationService.active?.pipelineFinished(generation)
                        AppState.emit("metrics", AppState.lastMetrics)
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (error: Throwable) { result.error("NATIVE_ERROR", error.message ?: error.javaClass.simpleName, null) }
    }
    private fun checkCurrent(generation: Int) {
        if (ScreenTranslationService.active?.current(generation) != true) throw CancellationException("Tela alterada")
    }
    private fun async(result: MethodChannel.Result, action: suspend () -> Any?) {
        scope.launch {
            try { result.success(action()) }
            catch (error: CancellationException) { result.error("STALE", "Tela alterada", null) }
            catch (error: GeminiFailure) {
                android.util.Log.w("WebtoonGemini", "${error.code}: ${error.message}")
                result.error(error.code, error.message, mapOf("httpStatus" to error.httpStatus))
            }
            catch (error: Throwable) { result.error("NATIVE_ERROR", error.message ?: error.javaClass.simpleName, null) }
        }
    }
    fun close() { onCancel(null); methods.setMethodCallHandler(null); events.setStreamHandler(null); scope.cancel() }
}
