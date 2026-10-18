package com.sebas.traductorapp.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInterceptorTest {

    @Test
    fun recognisesImageExtensionsIgnoringQueryAndCase() {
        assertTrue(ImageInterceptor.looksLikeImage("/file/abc/01.webp", null))
        assertTrue(ImageInterceptor.looksLikeImage("/x/PAGE.JPG", null))
        assertTrue(ImageInterceptor.looksLikeImage("/x/page.jpeg?w=800&q=90", null))
        assertTrue(ImageInterceptor.looksLikeImage("/x/page.png#frag", null))
        assertTrue(ImageInterceptor.looksLikeImage("/x/page.avif", null))
        assertTrue(ImageInterceptor.looksLikeImage("/x/anim.gif", null))
    }

    @Test
    fun recognisesImageAcceptHeader() {
        assertTrue(ImageInterceptor.looksLikeImage("/img.php", "image/avif,image/webp,image/apng,*/*;q=0.8"))
        assertTrue(ImageInterceptor.looksLikeImage("/cdn/12345", " image/*"))
    }

    @Test
    fun rejectsNonImages() {
        assertFalse(ImageInterceptor.looksLikeImage("/series/foo/chapter-24", "text/html,application/xhtml+xml"))
        assertFalse(ImageInterceptor.looksLikeImage("/api/chapter", "application/json"))
        assertFalse(ImageInterceptor.looksLikeImage("/static/app.js", "*/*"))
        assertFalse(ImageInterceptor.looksLikeImage("/x/page.webp.js", null))
        assertFalse(ImageInterceptor.looksLikeImage("/x/page.css?v=image.png", null))
    }
}
