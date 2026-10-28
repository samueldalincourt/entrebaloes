package br.com.leitorlocal.tradutor_webtoon

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

object ReaderLauncher {
    fun open(context: Context) {
        if (AppState.onlineMode) {
            val saved = BrowserTargetPolicy.webtoonUrl(AppState.prefs.getString("lastReaderUrl", null))
            val tab = CustomTabsIntent.Builder().setUrlBarHidingEnabled(false).build()
            tab.intent.setPackage(AppState.TARGET)
            tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(context, Uri.parse(saved ?: "https://www.webtoons.com/en/"))
        } else {
            val intent = context.packageManager.getLaunchIntentForPackage(AppState.TARGET)
            check(intent != null) { "O Chrome não está instalado." }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
