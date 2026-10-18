package com.sebas.traductorapp.series

import com.sebas.traductorapp.model.ChapterInfo
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer

/**
 * Deduce la clave estable de la serie (para el glosario) y el número de capítulo a partir de la URL,
 * usando el título de la página cuando la URL no nombra la serie (p. ej. MangaDex).
 */
object SeriesResolver {

    private const val MARK = "(?:chapter|chap|ch|episode|ep|capitulo|cap)"
    private const val NUM = "(\\d+)(?:\\.(\\d+)|-(\\d{1,2})$)?"

    // "chapter-24", "ch_24.5", "chapter-24-5" (estilo Madara = 24.5), "chapter-24-titulo"
    private val CHAPTER_SEGMENT = Regex("^$MARK[-_ .]?$NUM(?:[-_ ].*)?$", RegexOption.IGNORE_CASE)

    // "nombre-de-la-serie-chapter-12" en un solo segmento
    private val INLINE_CHAPTER = Regex("^(.+?)[-_ ]$MARK[-_ .]?$NUM(?:[-_ ].*)?$", RegexOption.IGNORE_CASE)

    private val NUMBER = Regex("^(\\d+)(?:\\.(\\d+))?$")
    private val CHAPTER_WORDS = setOf("chapter", "chap", "ch", "episode", "ep", "capitulo", "cap")
    private val SERIES_MARKERS = setOf(
        "series", "serie", "manga", "mangas", "manhwa", "manhua", "comic", "comics", "webtoon", "webtoons", "obra", "obras",
    )

    private val HEX_ID = Regex("^(?=.*\\d)(?=.*[a-f])[0-9a-f]{6,}$", RegexOption.IGNORE_CASE)
    private val LONG_NUMBER = Regex("^\\d{3,}$")
    private val WORD_SPLIT = Regex("[-_\\s+]+")

    private const val TITLE_MARK = "(?:vol(?:ume)?|chapter|chap|ch|episode|ep|cap(?:i|í)tulo|cap|season)"
    private val TITLE_CHAPTER = Regex(
        "\\b(?:chapter|chap|ch|episode|ep|cap(?:i|í)tulo|cap)\\.?\\s*(\\d+(?:\\.\\d+)?)",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_SEPARATORS = Regex("\\s+[-–—]+\\s+|\\s*[|·•»]+\\s*|\\s+::\\s+")
    private val MARKERS_ANY = Regex("\\b$TITLE_MARK\\.?\\s*\\d+(?:\\.\\d+)?|#\\d+", RegexOption.IGNORE_CASE)
    private val LEADING_MARKERS = Regex(
        "^\\s*(?:$TITLE_MARK\\.?\\s*\\d+(?:\\.\\d+)?\\s*[:.,\\-–—]?\\s*)+",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_FROM_MARKER = Regex(
        "\\s*[-:,(\\[]?\\s*\\b$TITLE_MARK\\.?\\s*\\d+(?:\\.\\d+)?\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_READ = Regex("^read\\s+", RegexOption.IGNORE_CASE)
    private val TRAILING_NOISE = Regex(
        "\\s+(?:online|english|eng|raw|free|for\\s+free|manga|manhwa|manhua|webtoon)$",
        RegexOption.IGNORE_CASE,
    )
    private val NON_SLUG = Regex("[^\\p{L}\\p{N}]+")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val FALLBACK_URL = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/?#]*@)?([^/?#:]+)(?::\\d+)?([^?#]*)(?:\\?([^#]*))?")

    private val GENERIC_SLD = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob")

    private class UrlParts(val host: String, val path: String, val query: String?)

    private class ChapterHit(val index: Int, val number: String, val inlineSeries: String?)

    fun resolve(url: String, title: String?): ChapterInfo {
        val parts = parseUrl(url)
        val usableTitle = title?.trim()?.takeIf { isUsableTitle(it, parts?.host) }
        if (parts == null) return fromTitle("unknown", usableTitle)
        val host = parts.host
        val segs = parts.path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

        if (host == "webtoons.com" || host.endsWith(".webtoons.com")) {
            resolveWebtoons(segs, parts.query, usableTitle, host)?.let { return it }
        }
        resolveIdChapter(host, segs, usableTitle)?.let { return it }
        return resolveBySegments(host, segs, usableTitle)
    }

    // (b) webtoons.com: /<lang>/<genre>/<title>/<episode>/viewer?title_no=..&episode_no=..
    private fun resolveWebtoons(segs: List<String>, query: String?, title: String?, host: String): ChapterInfo? {
        val params = parseQuery(query)
        val titleNo = params["title_no"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val viewerIdx = segs.indexOfFirst { it.equals("viewer", ignoreCase = true) }
        val listIdx = segs.indexOfFirst { it.equals("list", ignoreCase = true) }
        val slug = when {
            viewerIdx >= 2 -> segs[viewerIdx - 2]
            listIdx >= 1 -> segs[listIdx - 1]
            segs.size >= 3 -> segs[2]
            else -> null
        }
        val episodeSeg = if (viewerIdx >= 1) segs[viewerIdx - 1] else null
        val chapter = params["episode_no"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { normalizeNumber(it, null) }
            ?: episodeSeg?.let { Regex("(?:episode|ep)[-_]?(\\d+)", RegexOption.IGNORE_CASE).find(it) }
                ?.let { normalizeNumber(it.groupValues[1], null) }
        val key = when {
            titleNo != null -> "webtoons.com/title/$titleNo"
            slug != null -> "webtoons.com/title/$slug"
            else -> return null
        }
        val seriesTitle = slug?.let { titleFromSlug(it) } ?: title?.let { cleanSeriesTitle(it, host) } ?: "Webtoon"
        return ChapterInfo(key, seriesTitle, chapter)
    }

    // (c) estilo MangaDex: /chapter/<uuid> sin la serie en la URL -> la serie sale del título
    private fun resolveIdChapter(host: String, segs: List<String>, title: String?): ChapterInfo? {
        val i = segs.indexOfFirst { it.equals("chapter", ignoreCase = true) || it.equals("chapters", ignoreCase = true) }
        if (i < 0 || i + 1 >= segs.size) return null
        val next = segs[i + 1]
        if (NUMBER.matches(next) || CHAPTER_SEGMENT.matches(next) || INLINE_CHAPTER.matches(next)) return null
        if (segs.subList(0, i).any { it.lowercase() in SERIES_MARKERS }) return null
        return fromTitle(host, title)
    }

    private fun fromTitle(host: String, title: String?): ChapterInfo {
        val chapter = title?.let { chapterFromTitle(it) }
        val clean = title?.let { cleanSeriesTitle(it, host) }
        if (clean == null) return ChapterInfo("$host/unknown", host, chapter)
        val slug = slugify(clean)
        val key = if (slug.isEmpty()) "$host/unknown" else "$host/t/$slug"
        return ChapterInfo(key, clean, chapter)
    }

    // (a) marcador de serie + slug, y (d) patrón genérico de capítulo
    private fun resolveBySegments(host: String, segs: List<String>, title: String?): ChapterInfo {
        val hit = findChapter(segs)
        val markerIdx = segs.indexOfFirst { it.lowercase() in SERIES_MARKERS }
        val hasChapterWord = segs.any { it.equals("chapter", true) || it.equals("chapters", true) }

        // (a) host + ruta hasta el slug que sigue al marcador
        val bySlug: Pair<List<String>, String?>? = if (markerIdx >= 0 && markerIdx + 1 < segs.size) {
            val slugIdx = markerIdx + 1
            when {
                hit != null && hit.index > slugIdx -> Pair(segs.subList(0, slugIdx + 1), hit.number)
                hit != null && hit.index == slugIdx && hit.inlineSeries != null ->
                    Pair(segs.subList(0, slugIdx) + listOfNotNull(hit.inlineSeries), hit.number)
                hit == null -> {
                    val last = segs.last()
                    val ch = when {
                        segs.size - 1 > slugIdx && NUMBER.matches(last) -> numberOf(last)
                        hasChapterWord -> title?.let { chapterFromTitle(it) }
                        else -> null
                    }
                    Pair(segs.subList(0, slugIdx + 1), ch)
                }
                else -> null
            }
        } else {
            null
        }

        // (d) genérico: lo que está antes del segmento de capítulo
        val resolved: Pair<List<String>, String?> = bySlug ?: when {
            hit != null -> Pair(segs.subList(0, hit.index) + listOfNotNull(hit.inlineSeries), hit.number)
            segs.isNotEmpty() && NUMBER.matches(segs.last()) -> Pair(segs.dropLast(1), numberOf(segs.last()))
            else -> Pair(segs, null)
        }
        val seriesSegs = resolved.first
        val chapter = resolved.second

        // Sitios tipo Asura rotan un hash al final del slug ("solo-leveling-a1b2c3d4"): se quita para que el glosario no se parta.
        val keySegs = if (seriesSegs.isEmpty()) seriesSegs else seriesSegs.dropLast(1) + stableSlug(seriesSegs.last())
        val key = if (keySegs.isEmpty()) host else host + "/" + keySegs.joinToString("/")
        val seriesTitle = seriesSegs.lastOrNull()?.let { titleFromSlug(it) }
            ?: title?.let { cleanSeriesTitle(it, host) }
            ?: host
        return ChapterInfo(key, seriesTitle, chapter)
    }

    private fun stableSlug(seg: String): String {
        val parts = seg.split('-')
        return if (parts.size > 1 && HEX_ID.matches(parts.last())) parts.dropLast(1).joinToString("-") else seg
    }

    private fun findChapter(segs: List<String>): ChapterHit? {
        for (i in segs.indices.reversed()) {
            val s = segs[i]
            CHAPTER_SEGMENT.matchEntire(s)?.let { m ->
                return ChapterHit(i, normalizeNumber(m.groupValues[1], frac(m, 2, 3)), null)
            }
            if (s.lowercase() in CHAPTER_WORDS && i + 1 < segs.size && NUMBER.matches(segs[i + 1])) {
                return ChapterHit(i, numberOf(segs[i + 1]), null)
            }
        }
        for (i in segs.indices.reversed()) {
            INLINE_CHAPTER.matchEntire(segs[i])?.let { m ->
                return ChapterHit(i, normalizeNumber(m.groupValues[2], frac(m, 3, 4)), m.groupValues[1])
            }
        }
        return null
    }

    private fun frac(m: MatchResult, a: Int, b: Int): String? =
        m.groupValues[a].ifEmpty { m.groupValues[b] }.ifEmpty { null }

    private fun numberOf(s: String): String {
        val m = NUMBER.matchEntire(s) ?: return s
        return normalizeNumber(m.groupValues[1], m.groupValues[2].ifEmpty { null })
    }

    private fun normalizeNumber(intPart: String, fracPart: String?): String {
        val i = intPart.trimStart('0').ifEmpty { "0" }
        val f = fracPart?.trimEnd('0')
        return if (f.isNullOrEmpty()) i else "$i.$f"
    }

    private fun chapterFromTitle(title: String): String? {
        val m = TITLE_CHAPTER.find(title) ?: return null
        val raw = m.groupValues[1]
        return normalizeNumber(raw.substringBefore('.'), raw.substringAfter('.', "").ifEmpty { null })
    }

    /** "i-became-a-hunter-a1b2c3" -> "I Became A Hunter". Null si el slug es solo un id. */
    internal fun titleFromSlug(slug: String): String? {
        val words = slug.split(WORD_SPLIT).filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && isIdToken(words.last())) words.removeAt(words.lastIndex)
        while (words.size > 1 && LONG_NUMBER.matches(words.first())) words.removeAt(0)
        if (words.isEmpty() || words.all { isIdToken(it) || it.all(Char::isDigit) }) return null
        return words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercaseChar() } }
    }

    private fun isIdToken(w: String): Boolean = HEX_ID.matches(w) || LONG_NUMBER.matches(w)

    /** Quita del título de la página los marcadores de capítulo/volumen y el nombre del sitio. */
    internal fun cleanSeriesTitle(raw: String, host: String): String? {
        val t = raw.trim()
        if (!isUsableTitle(t, host)) return null
        val label = siteLabel(host)
        var parts = t.split(TITLE_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        parts = parts.filterNot { isMarkerOnly(it) }
        val withoutSite = parts.filterNot { isSitePart(it, label) }
        parts = when {
            withoutSite.size < parts.size -> withoutSite
            parts.size >= 2 -> parts.dropLast(1) // el último suele ser el nombre del sitio
            else -> parts
        }
        var s = parts.joinToString(" - ")
        s = LEADING_MARKERS.replace(s, "")
        s = TRAILING_FROM_MARKER.replace(s, "")
        s = LEADING_READ.replace(s, "")
        repeat(4) { s = TRAILING_NOISE.replace(s, "") }
        s = s.trim(' ', '-', '|', ':', ',', '–', '—', '·', '.')
        return s.ifBlank { null }
    }

    private fun isMarkerOnly(part: String): Boolean =
        MARKERS_ANY.replace(part, "").trim(' ', '.', ':', ',', '-', '#', '(', ')').isEmpty()

    private fun isSitePart(part: String, label: String): Boolean {
        if (label.isEmpty()) return false
        val norm = part.lowercase().filter { it.isLetterOrDigit() }
        return norm == label || (label.length >= 4 && norm.contains(label))
    }

    private fun isUsableTitle(title: String, host: String?): Boolean {
        if (title.isBlank()) return false
        if (title.contains("://")) return false
        if (host != null && title.trim().lowercase().removePrefix("www.") == host) return false
        return true
    }

    private fun slugify(s: String): String {
        val noAccents = DIACRITICS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        return noAccents.lowercase().replace(NON_SLUG, "-").trim('-')
    }

    private fun siteLabel(host: String): String {
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return host.lowercase().filter { it.isLetterOrDigit() }
        val tld = labels[labels.size - 1]
        val sld = labels[labels.size - 2]
        val base = if (tld.length == 2 && sld in GENERIC_SLD && labels.size >= 3) labels[labels.size - 3] else sld
        return base.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun parseUrl(url: String): UrlParts? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        try {
            val u = URI(withScheme)
            val host = u.host
            if (!host.isNullOrEmpty()) return UrlParts(cleanHost(host), u.path ?: "", u.rawQuery)
        } catch (_: Exception) {
            // URL con caracteres no válidos para java.net.URI: se usa el parser de respaldo
        }
        val m = FALLBACK_URL.find(withScheme) ?: return null
        val host = m.groupValues[1].ifEmpty { return null }
        return UrlParts(cleanHost(host), m.groupValues[2], m.groupValues[3].ifEmpty { null })
    }

    private fun cleanHost(host: String): String = host.trim().trimEnd('.').lowercase().removePrefix("www.")

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val k = pair.substringBefore('=')
            if (k.isEmpty()) continue
            out.putIfAbsent(decode(k), decode(pair.substringAfter('=', "")))
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }
}
