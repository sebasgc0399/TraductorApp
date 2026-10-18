package com.sebas.traductorapp.pipeline

import java.security.MessageDigest

object TextFilter {
    private const val SFX_HEIGHT_FACTOR = 1.6f
    private const val SHORT_TEXT_WORDS = 3

    private val WHITESPACE = Regex("\\s+")
    private val PIPE_AS_I = Regex("(^|\\s)[|\u00A6](?=['\u2019])")
    private val STRAY_PIPE = Regex("(?<=\\s)[|\u00A6]+(?=\\s)")
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([?!.,;:\u2026])")

    private val DOMAIN = Regex(
        "\\b[a-z0-9][a-z0-9-]*\\.(?:com|org|net|io|gg|xyz|me|co|cc)\\b|\\bwww\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CREDITS = Regex(
        "discord|patreon|\\bko-?fi\\b|translated\\s+by|proofread|typeset|raw\\s+provider|scanlat|\\p{L}{2,}scans\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT_ROLE = Regex(
        "\\b(?:tl|tlc|pr|ts|qc|translators?|cleaners?|redrawers?)\\s*[:\uFF1A]",
        RegexOption.IGNORE_CASE,
    )
    private val READ_AT = Regex(
        "^\\W*(?:please\\s+)?read\\s+(?:only\\s+)?(?:at|on)\\b" +
            "|\\bread\\s+(?:only\\s+)?(?:at|on)\\s+\\S*(?:scan|toon|comic|manga|manhwa)",
        RegexOption.IGNORE_CASE,
    )

    // Palabras que también aparecen en diálogos ("it scans", "a translator"): solo cuentan en textos cortos.
    private val AMBIGUOUS_CREDIT = Regex("\\b(?:scans|translators?|cleaners?)\\b", RegexOption.IGNORE_CASE)

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun clean(text: String): String {
        // ML Kit suele leer la "I" de las fuentes de cómic como '|': "|'M" -> "I'M".
        var s = PIPE_AS_I.replace(text, "$1I")
        s = WHITESPACE.replace(s, " ").trim { it == '|' || it == '\u00A6' || it.isWhitespace() }
        s = STRAY_PIPE.replace(s, "")
        s = SPACE_BEFORE_PUNCT.replace(s, "$1")
        return WHITESPACE.replace(s, " ").trim()
    }

    fun isNoise(text: String): Boolean {
        val s = clean(text)
        if (s.count { !it.isWhitespace() } <= 1) return true
        if (s.none { it.isLetter() }) return true
        if (DOMAIN.containsMatchIn(s)) return true
        if (CREDITS.containsMatchIn(s) || CREDIT_ROLE.containsMatchIn(s) || READ_AT.containsMatchIn(s)) return true
        val words = s.split(' ').size
        return words <= SHORT_TEXT_WORDS &&
            s.none { it == '?' || it == '!' } &&
            AMBIGUOUS_CREDIT.containsMatchIn(s)
    }

    fun isSfx(group: LineGroup, medianLineHeight: Int): Boolean {
        if (medianLineHeight <= 0) return false
        val s = clean(group.text)
        if (s.isEmpty() || !s.all { it.isLetter() }) return false
        return group.lineHeight > SFX_HEIGHT_FACTOR * medianLineHeight
    }

    fun hashKey(text: String): String {
        val normalized = WHITESPACE.replace(text.lowercase(), " ").trim()
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }
}
