package com.sebas.traductorapp.pipeline

import com.sebas.traductorapp.model.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilterTest {

    private fun group(text: String, lineHeight: Int) = LineGroup(
        lines = listOf(OcrLine(text, 100, 100, 300, 100 + lineHeight, 0.9f)),
        left = 100,
        top = 100,
        right = 300,
        bottom = 100 + lineHeight,
        text = text,
        lineHeight = lineHeight,
        confidence = 0.9f,
    )

    @Test
    fun noiseIsDetected() {
        listOf(
            "...",
            "?!",
            "123",
            "x",
            "READ AT VORTEXSCANS.ORG",
            "TL: someone",
            "Join our discord",
            "ASURA SCANS",
        ).forEach { assertTrue("debería ser ruido: $it", TextFilter.isNoise(it)) }
    }

    @Test
    fun dialogueIsNotNoise() {
        listOf(
            "WHAT?!",
            "I can't believe it.",
            "THE MACHINE SCANS EVERYTHING AROUND US.",
            "I READ ON THE NEWS THAT HE LEFT.",
        ).forEach { assertFalse("no debería ser ruido: $it", TextFilter.isNoise(it)) }
    }

    @Test
    fun cleanRemovesOcrJunk() {
        assertEquals("HELLO WORLD?", TextFilter.clean("  |HELLO   WORLD ? | "))
        assertEquals("I'M FINE.", TextFilter.clean("|'M FINE ."))
    }

    @Test
    fun hashKeyNormalizesCaseAndWhitespace() {
        assertEquals(TextFilter.hashKey("hello world"), TextFilter.hashKey("  Hello   WORLD "))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", TextFilter.hashKey("  ABC "))
        assertNotEquals(TextFilter.hashKey("hello"), TextFilter.hashKey("hello!"))
    }

    @Test
    fun sfxDetection() {
        assertTrue(TextFilter.isSfx(group("BOOM", 90), 30))
        assertFalse(TextFilter.isSfx(group("WHAT?!", 90), 30))
        assertFalse(TextFilter.isSfx(group("BOOM", 30), 30))
        assertFalse(TextFilter.isSfx(group("HUGE BOOM", 90), 30))
    }
}
