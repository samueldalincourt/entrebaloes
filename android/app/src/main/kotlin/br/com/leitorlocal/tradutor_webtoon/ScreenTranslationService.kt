package br.com.leitorlocal.tradutor_webtoon

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ScreenTranslationService : Service() {
    companion object { @Volatile var active: ScreenTranslationService? = null }
    private val handler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("screen-capture")
    private lateinit var captureHandler: Handler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val debouncer = ScrollDebouncer()
    private val reading = ManualReadingGate()
    val paused get() = reading.paused
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var engine: FlutterEngine? = null
    private var bridge: NativeChannels? = null
    private var overlay: FlutterView? = null
    private var controls: LinearLayout? = null
    private var toggleButton: Button? = null
    private var controlStatus: TextView? = null
    private lateinit var windows: WindowManager
    private var width = 0; private var height = 0
    private var overlayReady = false
    private var stopped = false
    private var idleUnload: Job? = null
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!Settings.canDrawOverlays(this@ScreenTranslationService) || !AppState.accessibilityConnected) {
                suspendReading(); AppState.error("Uma permissão foi desativada. Revise as autorizações."); stopSelf(); return
            }
            if (Build.VERSION.SDK_INT >= 29 &&
                (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                suspendReading(); AppState.error("Leitura pausada por aquecimento do aparelho."); stopSelf(); return
            }
            handler.postDelayed(this, 1000)
        }
    }
    private var stableTask: Runnable? = null
    private data class Pending(val generation: Int, val notBefore: Long, val result: MethodChannel.Result,
        val claimed: AtomicBoolean = AtomicBoolean(false))
    @Volatile private var pending: Pending? = null
    private var captureTimeout: Runnable? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                pauseReading()
            } else if (intent?.action == Intent.ACTION_USER_PRESENT && AppState.targetVisible) {
                motion()
            }
        }
    }
    override fun onCreate() {
        super.onCreate(); AppState.context = applicationContext; active = this
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        captureThread.start(); captureHandler = Handler(captureThread.looper)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        try {
            check(Settings.canDrawOverlays(this)) { "Autorize a sobreposição nas configurações." }
            check(AppState.accessibilityConnected) { "Ative o serviço de acessibilidade." }
            createNotification()
            val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val consent = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra("consent", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("consent")
            check(code == Activity.RESULT_OK && consent != null) { "Capture a tela com novo consentimento." }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, consent)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopped) { AppState.error("Captura encerrada. Inicie uma nova sessão."); stopSelf() }
                }
                override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                    if (newWidth != width || newHeight != height) {
                        AppState.error("A área de captura mudou. Inicie uma nova sessão em tela cheia.")
                        stopSelf()
                    }
                }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    if (!isVisible) suspendReading()
                }
            }, handler)
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }
            width = metrics.widthPixels; height = metrics.heightPixels
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader!!.setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
                image?.use {
                    try { processImage(it) }
                    catch (error: Throwable) {
                        val capture = pending
                        handler.post {
                            if (capture != null && pending === capture) {
                                pending = null
                                controls?.visibility = View.VISIBLE
                                capture.result.error("CAPTURE_FAILED", "Falha ao copiar o frame: ${error.message}", null)
                            }
                            if (error is OutOfMemoryError) {
                                AppState.error("Memória insuficiente para capturar a tela."); stopSelf()
                            }
                        }
                    }
                }
            }, captureHandler)
            display = projection!!.createVirtualDisplay("webtoon-capture", width, height,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, captureHandler)
            createOverlay()
            createControls()
            AppState.stage("Tradução pausada. Toque em Traduzir no botão flutuante.")
            handler.post(watchdog)
            AppState.lastError = null; AppState.emit("status")
        } catch (error: Throwable) {
            AppState.error("Não foi possível iniciar: ${error.message}"); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun createNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(NotificationChannel("reading", "Tradução ativa", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "reading")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(getString(R.string.app_name))
            .setContentText("Use o botão flutuante para traduzir ou pausar a tela.")
            .setContentIntent(open).setOngoing(true).addAction(0, "Parar", stop).build()
        ServiceCompat.startForeground(this, 10, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
    }
    private fun createOverlay() {
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(this); loader.ensureInitializationComplete(this, null)
        val flutter = FlutterEngine(this)
        engine = flutter
        bridge = NativeChannels(this, flutter.dartExecutor.binaryMessenger, null)
        val texture = FlutterTextureView(this).apply { isOpaque = false }
        val view = FlutterView(this, texture)
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        view.attachToFlutterEngine(flutter)
        overlay = view
        val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        layout.gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0)
        // A single untouchable SAW window; Android blocks touch through higher opacity.
        layout.alpha = if (Build.VERSION.SDK_INT >= 31) {
            val input = getSystemService(android.hardware.input.InputManager::class.java)
            minOf(.79f, input.maximumObscuringOpacityForTouch)
        } else .79f
        if (Build.VERSION.SDK_INT >= 28) layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        windows.addView(view, layout); view.visibility = View.INVISIBLE
        flutter.lifecycleChannel.appIsResumed()
        flutter.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "overlayMain"))
    }
    private fun createControls() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 35, 51)); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(123, 183, 158))
            }
            elevation = dp(8).toFloat()
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(this).apply {
            text = "⠿"; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(191, 246, 220))
            contentDescription = "Arrastar controle de tradução"
        }
        row.addView(handle, LinearLayout.LayoutParams(dp(30), dp(48)))
        val toggle = Button(this).apply {
            text = "▶ Traduzir"; textSize = 14f; isAllCaps = false
            contentDescription = "Iniciar tradução da tela"
            setTextColor(Color.rgb(16, 24, 38))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(191, 246, 220))
            setOnClickListener { toggleReading() }
        }
        toggleButton = toggle
        row.addView(toggle, LinearLayout.LayoutParams(0, dp(48), 1f))
        val close = TextView(this).apply {
            text = "×"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = "Encerrar sessão de tradução"
            setOnClickListener { stopSelf() }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(36), dp(48)))
        panel.addView(row)
        controlStatus = TextView(this).apply {
            textSize = 11f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(213, 224, 232)); setPadding(dp(8), 0, dp(8), 0)
        }
        panel.addView(controlStatus)
        val layout = WindowManager.LayoutParams(dp(236), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (width - dp(248)).coerceAtLeast(0); y = height / 3
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (startX + event.rawX - downX).toInt().coerceIn(0, (width - panel.width).coerceAtLeast(0))
                    layout.y = (startY + event.rawY - downY).toInt().coerceIn(0, (height - panel.height - dp(32)).coerceAtLeast(0))
                    windows.updateViewLayout(panel, layout); true
                }
                MotionEvent.ACTION_UP -> { handle.performClick(); true }
                else -> true
            }
        }
        controls = panel
        windows.addView(panel, layout)
    }
    fun updateControlStatus(message: String) {
        handler.post { controlStatus?.text = message }
    }
    fun ready() {
        overlayReady = true
        AppState.stage("Tradução pausada. Toque em Traduzir no botão flutuante.")
        if (!paused && AppState.targetVisible) motion()
    }
    fun current(generation: Int) = !stopped && !paused && generation == debouncer.generation && AppState.targetVisible &&
        AppState.accessibilityConnected && Settings.canDrawOverlays(this) &&
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive &&
        !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    fun targetChanged(visible: Boolean) {
        idleUnload?.cancel(); idleUnload = null
        if (visible) {
            if (!paused) motion()
        } else {
            suspendReading()
            AppState.stage(if (paused) "Tradução pausada." else "Abra o conteúdo que deseja traduzir.")
            idleUnload = scope.launch { delay(30000); runCatching { AppState.unloadTranslator() } }
        }
    }
    fun contentChanged() { if (reading.acceptsContentChange()) motion() }
    fun motion() {
        if (stopped || paused || !AppState.targetVisible) return
        invalidate()
        if (!reading.schedule()) return
        val generation = debouncer.motion(SystemClock.uptimeMillis())
        AppState.emit("invalidate", mapOf("generation" to generation))
        if (AppState.targetVisible) AppState.stage("Aguardando a rolagem parar.")
        val task = Runnable {
            stableTask = null
            if (overlayReady && current(generation) && debouncer.stable(SystemClock.uptimeMillis(), generation) && reading.begin()) {
                AppState.stage("Tela parada. Iniciando leitura.")
                AppState.emit("stable", mapOf("generation" to generation))
            }
        }
        stableTask = task; handler.postDelayed(task, 550)
    }
    private fun invalidate() {
        overlay?.visibility = View.INVISIBLE
        if (!stopped) controls?.visibility = View.VISIBLE
        stableTask?.let { handler.removeCallbacks(it) }; stableTask = null
        debouncer.invalidate()
        reading.invalidate()
        AppState.cancelTranslation()
        pending?.let { capture -> pending = null; capture.result.error("STALE", "Tela alterada", null) }
        captureTimeout?.let { handler.removeCallbacks(it) }; captureTimeout = null
    }
    fun suspendReading() {
        invalidate(); AppState.emit("invalidate", mapOf("generation" to debouncer.generation))
    }
    fun pauseReading() {
        reading.pause()
        suspendReading()
        toggleButton?.text = "▶ Traduzir"
        toggleButton?.contentDescription = "Iniciar tradução da tela"
        AppState.stage("Tradução pausada. Toque em Traduzir para retomar.")
        AppState.emit("status")
    }
    private fun toggleReading() {
        if (!paused) { pauseReading(); return }
        reading.resume()
        toggleButton?.text = "Ⅱ Pausar"
        toggleButton?.contentDescription = "Pausar tradução da tela"
        AppState.lastError = null
        ScrollAccessibilityService.instance?.refreshForeground()
        if (AppState.targetVisible) motion() else AppState.stage("Abra o conteúdo que deseja traduzir.")
        AppState.emit("status")
    }
    fun pipelineFinished(generation: Int?) {
        if (generation == debouncer.generation) reading.complete()
    }
    fun show(generation: Int) {
        if (current(generation) && Settings.canDrawOverlays(this)) overlay?.visibility = View.VISIBLE
    }
    fun capture(generation: Int, result: MethodChannel.Result) {
        if (!current(generation)) { result.error("STALE", "Tela alterada", null); return }
        if (pending != null) { result.error("BUSY", "Captura em andamento", null); return }
        overlay?.visibility = View.INVISIBLE
        controls?.visibility = View.INVISIBLE
        // Require a frame timestamp after the overlay has left the compositor.
        AppState.stage("Capturando a tela.")
        pending = Pending(generation, System.nanoTime() + 80_000_000L, result)
        handler.postDelayed({
            if (pending?.generation == generation && current(generation)) {
                // Reattach the surface to request a fresh composition even on a static page.
                // Keep the same VirtualDisplay and consent token for the whole session.
                display?.surface = null
                display?.surface = reader?.surface
            }
        }, 85)
        val timeout = Runnable {
            val capture = pending
            if (capture?.result === result) {
                pending = null; result.error("CAPTURE_TIMEOUT", "Nenhum frame novo recebido. Reinicie a sessão de captura.", null)
                controls?.visibility = View.VISIBLE
            }
        }
        captureTimeout = timeout; handler.postDelayed(timeout, 2500)
    }
    private fun processImage(image: Image) {
        val capture = pending ?: return
        if (image.timestamp < capture.notBefore || !capture.claimed.compareAndSet(false, true)) return
        // Image bytes must be copied before Image.close() on the reader thread.
        val plane = image.planes[0]
        val paddedWidth = plane.rowStride / plane.pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        val top = (height * AppState.prefs.getFloat("topCrop", AppState.DEFAULT_TOP_CROP)).toInt()
        val bottom = (height * AppState.prefs.getFloat("bottomCrop", AppState.DEFAULT_BOTTOM_CROP)).toInt()
        val cropHeight = height - top - bottom
        val cropped = Bitmap.createBitmap(bitmap, 0, top, width, cropHeight)
        if (cropped !== bitmap) bitmap.recycle()
        val path = File(cacheDir, "ocr-${capture.generation}-${System.nanoTime()}.png")
        try {
            var brightness = 0L
            for (y in 0 until cropped.height step maxOf(1, cropped.height / 24)) {
                for (x in 0 until cropped.width step maxOf(1, cropped.width / 24)) {
                    brightness += cropped.getPixel(x, y) and 0x00ffffff
                }
            }
            check(brightness != 0L) { "Captura preta. O conteúdo pode impedir a captura de tela." }
            path.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            handler.post {
                if (pending !== capture) { path.delete(); return@post }
                pending = null
                controls?.visibility = View.VISIBLE
                captureTimeout?.let { handler.removeCallbacks(it) }; captureTimeout = null
                if (!current(capture.generation)) { path.delete(); capture.result.error("STALE", "Tela alterada", null) }
                else capture.result.success(mapOf("path" to path.absolutePath, "width" to width,
                    "height" to height, "cropTop" to top, "cropHeight" to cropHeight, "generation" to capture.generation))
            }
        } catch (error: Exception) {
            path.delete(); handler.post {
                if (pending === capture) {
                    pending = null
                    controls?.visibility = View.VISIBLE
                    capture.result.error("CAPTURE_FAILED", error.message, null)
                }
            }
        } finally { cropped.recycle() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        suspendReading(); AppState.error("Dimensões alteradas. Reinicie a sessão de captura."); stopSelf()
    }
    override fun onDestroy() {
        val previousMode = AppState.translationMode
        stopped = true; invalidate(); active = null
        handler.removeCallbacks(watchdog)
        try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null); reader?.close(); reader = null
        projection?.stop(); projection = null
        overlay?.let { it.detachFromFlutterEngine(); windows.removeView(it) }; overlay = null
        controls?.let { windows.removeView(it) }; controls = null
        toggleButton = null; controlStatus = null
        bridge?.close(); bridge = null
        engine?.destroy(); engine = null
        captureThread.quitSafely()
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { runCatching { AppState.unloadMode(previousMode) } }
        AppState.lastOcrReport = emptyMap()
        cacheDir.listFiles()?.filter { it.name.startsWith("ocr-") }?.forEach { it.delete() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppState.emit("status"); super.onDestroy()
    }
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.println("overlayReady=$overlayReady paused=$paused targetVisible=${AppState.targetVisible} generation=${debouncer.generation}")
        writer.println("scrollEvents=${AppState.scrollEvents} capturePending=${pending != null}")
        writer.println("stage=${AppState.pipelineStage}")
        writer.println("lastError=${AppState.lastError}")
        writer.println("metrics=${AppState.lastMetrics}")
        writer.println("engine=${AppState.engineName}")
        writer.println("benchmark=${AppState.selectedBenchmark}")
        if (args?.contains("--ocr") == true) writer.println("ocr=${AppState.lastOcrReport}")
    }
}
