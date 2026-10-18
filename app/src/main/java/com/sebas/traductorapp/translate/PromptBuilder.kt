package com.sebas.traductorapp.translate

import com.sebas.traductorapp.data.SpanishVariant
import com.sebas.traductorapp.model.GlossaryEntry
import com.sebas.traductorapp.model.SourceLine
import kotlin.math.ceil

/**
 * Arma el prompt en tres tramos, de más a menos estable: instrucciones fijas (≥ 1,024 tokens, se cachean solas),
 * bloque de la serie (resumen + glosario) y parte variable (contexto + globos). Con las mismas entradas, los dos
 * primeros tramos son byte-idénticos entre llamadas.
 */
object PromptBuilder {
    private const val EMPTY = "(vacío)"
    private const val START = "(inicio)"
    private const val SUMMARY_MAX_LINES = 80
    private const val SUMMARY_LINE_MAX_CHARS = 300
    private val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")

    fun build(
        variant: SpanishVariant,
        glossary: List<GlossaryEntry>,
        summary: String,
        context: List<String>,
        lines: List<SourceLine>,
    ): BuiltPrompt {
        // El glosario va al final y en el orden recibido: los términos nuevos se agregan al final del bloque.
        val glossaryText = glossary
            .filter { it.en.isNotBlank() }
            .joinToString("\n") { "${oneLine(it.en)} => ${oneLine(it.es)}" }

        val seriesBlock = buildString {
            append("\n\n[SUMMARY]\n")
            append(summary.trim().ifEmpty { EMPTY })
            append("\n\n[GLOSSARY]\n")
            append(glossaryText.ifEmpty { EMPTY })
        }

        val contextText = context
            .map(::oneLine)
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "- $it" }

        val variablePart = buildString {
            append("[CONTEXT]\n")
            append(contextText.ifEmpty { START })
            append("\n\n[TRANSLATE]\n")
            append(lines.joinToString("\n") { "${it.id}|${oneLine(it.text)}" })
        }

        return BuiltPrompt(instructions(variant), seriesBlock, variablePart, lines.map { it.id })
    }

    /** Estimación holgada de la salida: ~3 caracteres por token, +60 % por el español y el JSON. */
    fun maxOutputTokensFor(lines: List<SourceLine>): Int {
        val textTokens = lines.sumOf { it.text.length / 3.0 }
        val estimate = textTokens * 1.6 + 40.0 * lines.size + 150.0
        return ceil(estimate).toInt().coerceIn(200, 6000)
    }

    internal fun instructions(variant: SpanishVariant): String = when (variant) {
        SpanishVariant.LATINO -> INSTRUCTIONS_LATINO
        SpanishVariant.ESPANA -> INSTRUCTIONS_ESPANA
    }

    /** Instrucciones de la llamada de resumen (cortas: esa llamada no usa caché). */
    fun summaryInstructions(variant: SpanishVariant): String = when (variant) {
        SpanishVariant.LATINO -> "$SUMMARY_BASE\nWrite it in neutral Latin American Spanish."
        SpanishVariant.ESPANA -> "$SUMMARY_BASE\nWrite it in Spain Spanish."
    }

    /** Entrada de la llamada de resumen: título, resumen actual y las últimas [SUMMARY_MAX_LINES] líneas en español. */
    fun summaryInput(request: SummaryRequest): String {
        val lines = request.recentLines
            .map(::oneLine)
            .filter { it.isNotEmpty() }
            .takeLast(SUMMARY_MAX_LINES)
            .joinToString("\n") { "- ${it.take(SUMMARY_LINE_MAX_CHARS)}" }
        return buildString {
            append("[SERIES]\n")
            append(oneLine(request.seriesTitle).ifEmpty { EMPTY })
            append("\n\n[CURRENT SUMMARY]\n")
            append(request.currentSummary.trim().ifEmpty { EMPTY })
            append("\n\n[NEW LINES]\n")
            append(lines.ifEmpty { EMPTY })
        }
    }

    private fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ").trim()

    private val BASE = """
        You are a professional manhwa translator and typesetter. You translate English webtoon/manhwa speech bubbles into natural, publication-quality Spanish that readers enjoy without noticing it is a translation. Always write the output in Spanish.

        INPUT FORMAT
        These instructions are followed by two series sections, and the user message carries the batch:
        - [SUMMARY]: a short summary of the story so far: who is who, how they talk to each other and what is happening. Context only.
        - [GLOSSARY]: fixed "English => Spanish" pairs for this series (characters, places, skills, items, titles). Follow them exactly.
        - [CONTEXT]: the latest lines already translated into Spanish, oldest first. Use them to keep tone, speakers, register and continuity. Never translate or repeat them.
        - [TRANSLATE]: the bubbles to translate in reading order, one per line, as "<id>|<English text>". Line breaks inside a bubble were replaced with spaces.
        An empty section shows "(vacío)". "(inicio)" in [CONTEXT] means the chapter has just started.

        OCR NOISE
        The English text comes from OCR of comic lettering, so expect errors: ALL CAPS, words broken by hyphens or spaces ("TOGE- THER", "BEL IEVE"), confusions such as l/I/1, 0/O, rn/m, cl/d and vv/w, missing or extra spaces, stray symbols, and two neighboring bubbles merged into one line. Silently rebuild the intended sentence using the nearby lines and common sense. Never mention the errors and never copy garbage characters into the Spanish.

        OUTPUT CONTRACT
        Reply with JSON only, following the schema:
        - "t": one string per input line, in the same order, written as "<id>|<Spanish text>" with the same id as the input line, for example "7|¿Quién eres?". Exactly one string per id: do not skip, merge or invent ids. If one sentence spans several bubbles, split the Spanish so each bubble keeps its own part.
        - "g": ONLY genuinely new recurring proper names or special terms (characters, guilds, places, skills, items, titles, ranks) that appear in this batch and are not already in the glossary, as {"en": "original", "es": "chosen Spanish"}. Names that stay the same are still listed once ({"en": "Kang Jin", "es": "Kang Jin"}). Common words never go here. If there is nothing new, return [].

        TRANSLATION RULES
        1. Meaning and tone over literal wording. Write natural spoken dialogue, the way a native speaker would say it, not textbook Spanish. Keep jokes and wordplay working in Spanish when possible.
        2. Keep the length close to the English so the text fits the same bubble. Prefer short, punchy phrasing and drop filler before dropping meaning.
        3. Use sentence case, never ALL CAPS, even when the source is all caps. Keep capitals only for names, acronyms and a single shouted word used for emphasis.
        4. Use correct Spanish punctuation: opening ¿ and ¡, ellipsis …, dashes — for interruptions, and ¡¿…?! for shocked questions. Keep stutters and drawn-out sounds ("W-what?" → "¿Q-qué?", "NOOO!" → "¡Nooo!").
        5. Never translate personal names. Follow the glossary exactly, including spelling. Keep Korean honorifics and kinship terms as they are: hyung, noona, oppa, unnie, sunbae, hoobae, ahjussi, -nim, -ssi.
        6. Keep game and system vocabulary consistent unless the glossary says otherwise: Level → Nivel, Skill → Habilidad, Status → Estado, Stats → Estadísticas, Hunter → Cazador, Gate → Portal, Dungeon → Mazmorra, Guild → Gremio, Quest → Misión, Item → Objeto, Player → Jugador, S-rank → rango S. EXP, HP and MP stay as they are. System windows keep their bracket style: "[Quest completed]" → "[Misión completada]". When a system window or status screen lists separate fields (name, rank, level, effect…), put each field on its own line with a real line break; never write a literal backslash sequence such as \n. Ordinary dialogue stays on one line.
        7. Register: use "tú" among peers, friends, family, rivals and enemies; use "usted" for formal or respectful speech (to elders, bosses, nobles, teachers, clients, or anyone addressed with -nim). Keep each pair of characters consistent with the summary and the context lines, and switch only when their relationship clearly changes.
        8. Translate slang, insults and swearing with equivalent intensity. Do not censor or soften them, and do not add profanity that is not there.
        9. Sound effects: use short Spanish onomatopoeia (BOOM → ¡Bum!, CRASH → ¡Crac!, SIGH → Suspiro) or keep the original when it works in Spanish.
        10. Gender and number: infer the speaker's and listener's gender from names, context lines and the summary to get agreement right (cansado/cansada, solo/sola); when it is truly unknown, prefer phrasing that avoids marking gender.
        11. Narration boxes and inner thoughts keep the narrator's voice. Numbers, ages, times, units and currencies stay as written, with natural Spanish wording around them.
        12. Pure noise (watermarks, scanlation credits, site names or URLs, page numbers and unreadable garbage) keeps its id with empty text: "7|".
        13. Never add notes, explanations, translator comments, quotation marks around the text or the original English.
    """.trimIndent()

    private val VARIANT_LATINO = """
        SPANISH VARIANT
        Write neutral Latin American Spanish that reads naturally from Mexico to Argentina. Use "ustedes" for plural you, never "vosotros" or its verb forms. Avoid Spain-only words and slang such as vale, tío, guay, mola, coger, ordenador or móvil; prefer widely understood options like "está bien", "genial", "tomar", "computadora" and "celular". Avoid strongly local slang (che, güey, parce) unless a character clearly needs it.
    """.trimIndent()

    private val VARIANT_ESPANA = """
        SPANISH VARIANT
        Write Spain Spanish (castellano peninsular). Use "vosotros" with its verb forms for plural informal you ("¿Qué hacéis aquí?") and "ustedes" only for formal plural. Spain colloquialisms are welcome when they fit the character: vale, tío/tía, guay, mola, flipar, joder, hostia. Use Spain vocabulary such as ordenador, móvil, coche and coger, and prefer the present perfect for things that just happened ("¡Has subido de nivel!").
    """.trimIndent()

    private val EXAMPLE_LATINO = """
        EXAMPLE (empty summary and glossary)
        [TRANSLATE]
        1|MIN-JUN HYUNG! YOU CLEARED THE GATE ALONE?!
        2|[LEVEL UP! YOU HAVE REACHED LEVEL 12.]
        3|l-I GUESS I GOT LUCKY...
        4|READ AT WWW.SCANSITE.ORG
        Output:
        {"t":["1|¡Min-jun hyung! ¡¿Despejaste el portal tú solo?!","2|[¡Subiste de nivel! Has alcanzado el nivel 12.]","3|S-supongo que tuve suerte…","4|"],"g":[{"en":"Min-jun","es":"Min-jun"}]}
    """.trimIndent()

    private val EXAMPLE_ESPANA = """
        EXAMPLE (empty summary and glossary)
        [TRANSLATE]
        1|MIN-JUN HYUNG! YOU CLEARED THE GATE ALONE?!
        2|[LEVEL UP! YOU HAVE REACHED LEVEL 12.]
        3|l-I GUESS I GOT LUCKY...
        4|READ AT WWW.SCANSITE.ORG
        Output:
        {"t":["1|¡Min-jun hyung! ¡¿Has despejado el portal tú solo?!","2|[¡Has subido de nivel! Has alcanzado el nivel 12.]","3|S-supongo que he tenido suerte…","4|"],"g":[{"en":"Min-jun","es":"Min-jun"}]}
    """.trimIndent()

    private val INSTRUCTIONS_LATINO = listOf(BASE, VARIANT_LATINO, EXAMPLE_LATINO).joinToString("\n\n")
    private val INSTRUCTIONS_ESPANA = listOf(BASE, VARIANT_ESPANA, EXAMPLE_ESPANA).joinToString("\n\n")

    private val SUMMARY_BASE = """
        You keep the running story summary that a manhwa translator reads before translating each batch of speech bubbles. Update [CURRENT SUMMARY] with [NEW LINES] (dialogue already translated into Spanish, in reading order) and return the new summary as compact notes, at most 120 words:
        - Who is who: names, roles, relationships, and how each character speaks (tú or usted with whom, slang, formal tone, verbal tics).
        - The current situation: where the characters are and what is happening now.
        - Running terms: recurring names, skills, items or jokes worth keeping consistent.
        Keep stable facts from the current summary unless the new lines contradict them, and drop details that no longer matter. Use only what the summary and the lines say: no guesses and no spoilers beyond them. Reply with JSON only: {"summary": "..."}.
    """.trimIndent()
}
