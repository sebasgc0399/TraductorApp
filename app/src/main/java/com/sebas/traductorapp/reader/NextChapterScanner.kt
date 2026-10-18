package com.sebas.traductorapp.reader

import java.net.URI

/** Extrae las URLs de las páginas del siguiente capítulo desde su HTML, para precargarlo sin abrirlo. */
object NextChapterScanner {
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""\b(data-src|data-lazy-src|data-original|data-url|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val LAZY_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-url")
    private val PLACEHOLDER = Regex("(?i)(lazy|placeholder|transparent|loading|blank|spacer)")

    // Lectores que arman la página con JS suelen traer la lista de imágenes en un JSON dentro del HTML.
    private val RAW_IMAGE_URL = Regex("""https?:\\?/\\?/[^"'\s<>()]+?\.(?:webp|jpe?g|png|avif)(?:\?[^"'\s<>()]*)?""", RegexOption.IGNORE_CASE)

    fun extractImageUrls(html: String, baseUrl: String): List<String> {
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val out = LinkedHashSet<String>()
        for (tag in IMG_TAG.findAll(html)) {
            val attrs = ATTR.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
            val src = attrs["src"]
            val lazy = LAZY_ATTRS.firstNotNullOfOrNull { attrs[it]?.takeIf(String::isNotBlank) }
            val chosen = when {
                lazy != null && (src.isNullOrBlank() || src.startsWith("data:") || PLACEHOLDER.containsMatchIn(src)) -> lazy
                !src.isNullOrBlank() && !src.startsWith("data:") -> src
                else -> lazy
            } ?: continue
            resolve(base, chosen)?.let(out::add)
        }
        if (out.size < 3) {
            for (m in RAW_IMAGE_URL.findAll(html)) {
                resolve(base, m.value.replace("\\/", "/"))?.let(out::add)
            }
        }
        return out.toList()
    }

    /**
     * Se queda con las imágenes del host que sirve las páginas del capítulo actual; si ninguna coincide,
     * con las del host más frecuente (al menos 3), para dejar fuera logos y anuncios.
     */
    fun pickPages(urls: List<String>, preferredHost: String?): List<String> {
        val byHost = urls.groupBy { hostOf(it) }
        if (preferredHost != null) byHost[preferredHost]?.let { return it }
        val best = byHost.maxByOrNull { it.value.size } ?: return emptyList()
        return if (best.value.size >= 3) best.value else emptyList()
    }

    fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun resolve(base: URI?, raw: String): String? = runCatching {
        val cleaned = raw.replace("&amp;", "&").trim()
        val uri = if (base != null) base.resolve(cleaned.replace(" ", "%20")) else URI(cleaned)
        uri.toString().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrNull()
}
