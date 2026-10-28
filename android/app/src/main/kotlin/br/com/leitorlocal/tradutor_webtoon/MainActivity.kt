package br.com.leitorlocal.tradutor_webtoon

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    private var channels: NativeChannels? = null
    private var consentResult: MethodChannel.Result? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channels = NativeChannels(this, flutterEngine.dartExecutor.binaryMessenger, this)
    }
    fun startCapture(result: MethodChannel.Result) {
        check(consentResult == null) { "A autorização de captura já está aberta." }
        check(Settings.canDrawOverlays(this)) { "Autorize a sobreposição primeiro." }
        check(AppState.accessibilityConnected) { "Ative a acessibilidade primeiro." }
        check(ScreenTranslationService.active == null) { "Uma sessão já está ativa." }
        check(AppState.runtimeAvailable) { "O motor local não carregou. Reinstale o APK completo." }
        check(AppState.modelReady) { "Baixe o modelo antes de iniciar a leitura." }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val consent = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        consentResult = result
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        } else launchConsent(consent)
    }
    @Suppress("DEPRECATION")
    private fun launchConsent(intent: Intent) { startActivityForResult(intent, 42) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && consentResult != null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launchConsent(if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent())
        }
    }
    @Deprecated("Used for MediaProjection consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        val result = consentResult ?: return
        consentResult = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            result.error("CONSENT_DENIED", "Captura não autorizada.", null); return
        }
        try {
            startForegroundService(Intent(this, ScreenTranslationService::class.java)
                .putExtra("resultCode", resultCode).putExtra("consent", data))
            result.success(null)
            moveTaskToBack(true)
        } catch (error: Exception) { result.error("START_FAILED", error.message, null) }
    }
    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channels?.close(); channels = null
        consentResult?.error("ACTIVITY_CLOSED", "Tela de autorização fechada.", null); consentResult = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
