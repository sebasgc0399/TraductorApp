package com.sebas.traductorapp.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NextChapterScannerTest {

    @Test
    fun extractsPlainAndLazyImagesInOrder() {
        val html = """
            <img src="/logo.png" alt="logo">
            <img src="https://storage.example.org/s/1/01.webp">
            <img src="data:image/gif;base64,AAAA" data-src="https://storage.example.org/s/1/02.webp">
            <img class="x" src="/img/lazy-placeholder.gif" data-lazy-src="https://storage.example.org/s/1/03.webp">
        """.trimIndent()
        val urls = NextChapterScanner.extractImageUrls(html, "https://example.org/series/foo/chapter-25")
        assertEquals(
            listOf(
                "https://example.org/logo.png",
                "https://storage.example.org/s/1/01.webp",
                "https://storage.example.org/s/1/02.webp",
                "https://storage.example.org/s/1/03.webp",
            ),
            urls,
        )
    }

    @Test
    fun fallsBackToUrlsInsideScripts() {
        val html = """<script>self.__next_f.push(["{\"images\":[\"https:\/\/cdn.site.com\/a\/1.jpg\",\"https:\/\/cdn.site.com\/a\/2.jpg\",\"https:\/\/cdn.site.com\/a\/3.jpg\"]}"])</script>"""
        val urls = NextChapterScanner.extractImageUrls(html, "https://site.com/ch/2")
        assertEquals(3, urls.size)
        assertEquals("https://cdn.site.com/a/1.jpg", urls.first())
    }

    @Test
    fun picksPagesFromPreferredHost() {
        val urls = listOf(
            "https://example.org/logo.png",
            "https://storage.example.org/1.webp",
            "https://storage.example.org/2.webp",
            "https://ads.net/banner.jpg",
        )
        assertEquals(
            listOf("https://storage.example.org/1.webp", "https://storage.example.org/2.webp"),
            NextChapterScanner.pickPages(urls, "storage.example.org"),
        )
    }

    @Test
    fun picksMostFrequentHostWhenNoneMatches() {
        val urls = listOf("https://a.com/1.jpg", "https://cdn.b.com/1.jpg", "https://cdn.b.com/2.jpg", "https://cdn.b.com/3.jpg")
        assertEquals(3, NextChapterScanner.pickPages(urls, "other.com").size)
        assertEquals(emptyList<String>(), NextChapterScanner.pickPages(urls.take(2), null))
    }
}
