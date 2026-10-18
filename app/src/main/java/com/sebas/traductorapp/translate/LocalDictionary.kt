package com.sebas.traductorapp.translate

import com.sebas.traductorapp.data.SpanishVariant

/**
 * Resuelve localmente interjecciones muy cortas ("WHAT?!", "Huh?", "HAHAHA") para no gastar tokens.
 * Devuelve null si el texto no es una interjección conocida de 1 o 2 palabras.
 */
object LocalDictionary {
    private const val OKAY = "\u0000okay" // marcador: depende de la variante

    // Puntos suspensivos iniciales opcionales, 1-2 palabras y puntuación final.
    private val PATTERN = Regex("""^([.…]*)\s*([A-Za-z]+)(?:[\s,]+([A-Za-z]+))?\s*([?!.…]*)$""")

    private val LAUGH = Regex("""^a?(?:ha)+h?$""")
    private val LAUGH_HE = Regex("""^(?:he){2,}h?$|^heh$""")
    private val LAUGH_VILLAIN = Regex("""^(?:mu|mw|bw)a?(?:ha)+h?$""")
    private val HA = Regex("ha")
    private val HE = Regex("he")

    // Se escriben igual en español: se conserva el alargamiento original ("Ohhh", "Nooo").
    private val ECHO = setOf("oh", "ah", "eh", "ugh", "no", "tsk", "shh", "argh", "hmph")

    private val WORDS: Map<String, String> = mapOf(
        "what" to "qué",
        "huh" to "eh",
        "uh" to "eh",
        "um" to "eh",
        "umm" to "eh",
        "er" to "eh",
        "erm" to "eh",
        "hm" to "mmm",
        "hmm" to "mmm",
        "yes" to "sí",
        "yeah" to "sí",
        "yep" to "sí",
        "yup" to "sí",
        "nope" to "no",
        "nah" to "no",
        "ok" to OKAY,
        "okay" to OKAY,
        "okey" to OKAY,
        "wow" to "guau",
        "whoa" to "guau",
        "woah" to "guau",
        "hey" to "oye",
        "sorry" to "perdón",
        "thanks" to "gracias",
        "thx" to "gracias",
        "why" to "por qué",
        "who" to "quién",
        "where" to "dónde",
        "when" to "cuándo",
        "how" to "cómo",
        "really" to "en serio",
        "seriously" to "en serio",
        "damn" to "maldición",
        "dammit" to "maldición",
        "damnit" to "maldición",
        "ouch" to "ay",
        "ow" to "ay",
        "oops" to "uy",
        "whoops" to "uy",
        "phew" to "uf",
        "aha" to "ajá",
        "hello" to "hola",
        "hi" to "hola",
        "bye" to "adiós",
        "goodbye" to "adiós",
        "please" to "por favor",
        "sure" to "claro",
        "help" to "ayuda",
    )

    private val PHRASES: Map<String, String> = mapOf(
        "thank you" to "gracias",
        "oh no" to "oh, no",
        "no way" to "no puede ser",
        "come on" to "vamos",
        "my god" to "dios mío",
        "thank god" to "gracias a dios",
    )

    // Mismos mapas indexados por la forma sin letras repetidas ("whaaat" -> "what", "sooorry" -> "sorry").
    private val WORDS_COLLAPSED: Map<String, String> = WORDS.entries.associate { collapse(it.key) to it.value }
    private val ECHO_COLLAPSED: Set<String> = ECHO.mapTo(HashSet()) { collapse(it) }
    private val PHRASES_COLLAPSED: Map<String, String> =
        PHRASES.entries.associate { e -> e.key.split(' ').joinToString(" ") { collapse(it) } to e.value }

    fun translate(text: String, variant: SpanishVariant): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 40) return null
        val match = PATTERN.matchEntire(trimmed) ?: return null
        val leading = match.groupValues[1]
        val first = match.groupValues[2].lowercase()
        val second = match.groupValues[3].lowercase()
        val trailing = match.groupValues[4]

        val core: String = (if (second.isEmpty()) word(first, variant) else pair(first, second, variant))
            ?: return null
        return leading + punctuate(sentenceCase(core), trailing)
    }

    private fun pair(first: String, second: String, variant: SpanishVariant): String? {
        PHRASES["$first $second"]?.let { return it }
        PHRASES_COLLAPSED["${collapse(first)} ${collapse(second)}"]?.let { return it }
        // Solo se acepta repetir la misma interjección: "No, no", "Hey, hey", "Ha ha".
        val same = collapse(first) == collapse(second) || (laugh(first) != null && laugh(second) != null)
        if (!same) return null
        val a = word(first, variant) ?: return null
        val b = word(second, variant) ?: return null
        return "$a, $b"
    }

    private fun word(w: String, variant: SpanishVariant): String? {
        laugh(w)?.let { return it }
        val exact = WORDS[w]
        if (exact != null) return resolve(exact, variant)
        val c = collapse(w)
        if (w in ECHO || c in ECHO_COLLAPSED) return w
        return WORDS_COLLAPSED[c]?.let { resolve(it, variant) }
    }

    private fun resolve(value: String, variant: SpanishVariant): String =
        if (value != OKAY) {
            value
        } else {
            when (variant) {
                SpanishVariant.LATINO -> "está bien"
                SpanishVariant.ESPANA -> "vale"
            }
        }

    private fun laugh(w: String): String? {
        if (w == "aha") return null
        return when {
            LAUGH_VILLAIN.matches(w) -> "mua" + "ja".repeat(HA.findAll(w).count().coerceIn(1, 6))
            LAUGH.matches(w) -> "ja".repeat(HA.findAll(w).count().coerceIn(1, 6))
            LAUGH_HE.matches(w) -> "je".repeat(HE.findAll(w).count().coerceIn(1, 6))
            else -> null
        }
    }

    /** Signos de apertura según el cierre: "?" → ¿…?, "!" → ¡…!, "?!"/"!?" → ¡¿…?!, "..." se conserva. */
    private fun punctuate(core: String, trailing: String): String {
        val questions = trailing.count { it == '?' }
        val exclamations = trailing.count { it == '!' }
        return when {
            questions > 0 && exclamations > 0 -> {
                val dots = trailing.filter { it == '.' || it == '…' }
                "¡¿$core$dots?!"
            }
            questions > 0 -> "¿".repeat(questions.coerceAtMost(3)) + core + trailing
            exclamations > 0 -> "¡".repeat(exclamations.coerceAtMost(3)) + core + trailing
            else -> core + trailing
        }
    }

    private fun sentenceCase(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    /** Colapsa letras repetidas consecutivas: "hmmm" -> "hm", "ughhh" -> "ugh". */
    private fun collapse(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
        }
        return sb.toString()
    }
}
