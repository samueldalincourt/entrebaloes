package br.com.leitorlocal.tradutor_webtoon

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class ScrollAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: ScrollAccessibilityService? = null }
    private var foregroundPackage: String? = null
    override fun onServiceConnected() {
        instance = this
        AppState.context = applicationContext
        AppState.accessibilityConnected = true
        refreshForeground()
        AppState.emit("status")
    }
    fun refreshForeground(): Boolean {
        // Floating controls are system overlay windows, not the reader.
        val available = windows
        val root = try {
            (available.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                ?: available.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION })?.root
                ?: rootInActiveWindow
        } finally {
            @Suppress("DEPRECATION")
            available.forEach { it.recycle() }
        }
        val previousPackage = foregroundPackage
        foregroundPackage = root?.packageName?.toString()
        @Suppress("DEPRECATION")
        root?.recycle()
        val visible = foregroundPackage != null && foregroundPackage !in setOf(packageName, "android", "com.android.systemui")
        val changed = AppState.targetVisible != visible || previousPackage != foregroundPackage
        AppState.targetVisible = visible
        if (changed) {
            ScreenTranslationService.active?.targetChanged(visible)
            AppState.emit("status")
        }
        return visible
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (event.packageName?.toString() == foregroundPackage && refreshForeground()) {
                    AppState.scrollEvents++
                    ScreenTranslationService.active?.motion()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshForeground()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName?.toString() == foregroundPackage && refreshForeground()) {
                    ScreenTranslationService.active?.contentChanged()
                }
            }
        }
    }
    override fun onInterrupt() { ScreenTranslationService.active?.pauseReading() }
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.println("connected=${AppState.accessibilityConnected} readerVisible=${AppState.targetVisible} foreground=$foregroundPackage")
        writer.println("mode=${AppState.translationMode} onlineKeyConfigured=${AppState.credentials.configured} onlineReady=${AppState.online.ready}")
        writer.println("onlineBenchmark=${AppState.online.benchmark}")
    }
    override fun onDestroy() {
        instance = null
        AppState.accessibilityConnected = false
        AppState.targetVisible = false
        ScreenTranslationService.active?.pauseReading()
        AppState.emit("status")
        super.onDestroy()
    }
}
