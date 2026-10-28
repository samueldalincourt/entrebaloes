package br.com.leitorlocal.tradutor_webtoon

import java.net.URI
import java.util.Locale

object BrowserTargetPolicy {
    fun webtoonUrl(address: String?): String? {
        if (address.isNullOrBlank()) return null
        return try {
            val clean = address.trim()
            if (clean.any { it.isWhitespace() }) return null
            val uri = URI(if (clean.contains("://")) clean else "https://$clean")
            if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
            if (uri.host?.lowercase(Locale.ROOT) !in setOf("webtoons.com", "www.webtoons.com", "m.webtoons.com")) return null
            uri.toASCIIString()
        } catch (_: Exception) { null }
    }
}
