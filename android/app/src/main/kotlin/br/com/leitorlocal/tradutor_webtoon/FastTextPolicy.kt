package br.com.leitorlocal.tradutor_webtoon

import java.util.Locale

/** Repair OCR punctuation/casing without changing the source used as the cache key. */
object FastTextPolicy {
    data class Prepared(val text: String, val prefix: String, val terms: Map<String, String>)
    fun glossary(raw: String): List<String> = raw.split(',', ';', '\n')
        .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
        .sortedByDescending { it.length }

    fun prepare(source: String, glossary: List<String>): Prepared {
        val leading = Regex("^[.\u2026\\s]+").find(source)?.value.orEmpty()
        val prefix = if (leading.any { it == '.' || it == '\u2026' }) "…" else ""
        var body = source.substring(leading.length).trim()
        val letters = body.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) body = body.lowercase(Locale.ROOT)
        val protected = linkedMapOf<String, String>()
        for ((index, term) in glossary.withIndex()) {
            val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            val token = "ZXQTERM${index}ZXQ"
            if (pattern.containsMatchIn(body)) {
                body = body.replace(pattern, token)
                protected[token] = term
            }
        }
        return Prepared(body, prefix, protected)
    }
    fun restore(translated: String, prepared: Prepared): String {
        var text = translated.trim()
        for ((token, term) in prepared.terms) {
            val index = token.removePrefix("ZXQTERM").removeSuffix("ZXQ")
            val pattern = Regex("ZXQ\\s*TERM\\s*${index}\\s*ZXQ", RegexOption.IGNORE_CASE)
            check(pattern.containsMatchIn(text)) { "O tradutor não preservou um nome protegido. Tente o modo LLM para este balão." }
            text = text.replace(pattern, term)
        }
        return prepared.prefix + text
    }
}
