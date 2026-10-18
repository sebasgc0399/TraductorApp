package com.sebas.traductorapp.reader

import com.sebas.traductorapp.model.Bubble
import com.sebas.traductorapp.model.PageAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamCoversTest {

    private fun bubble(id: Int, l: Int, t: Int, r: Int, b: Int, lh: Int) =
        Bubble(id, "TEXT $id", l, t, r, b, lh, 0.8f, 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), uniform = true)

    private fun page(vararg bubbles: Bubble) = PageAnalysis("u", 800, 5000, bubbles.toList(), version = 2)

    // Caso real (capítulo 31, páginas 5 y 6): un recuadro de narración partido por el corte.
    private val page5 = page(bubble(2, 236, 1598, 567, 1943, 34), bubble(3, 247, 4730, 552, 4997, 32))
    private val page6 = page(bubble(1, 265, 0, 536, 47, 36), bubble(4, 355, 4707, 665, 4817, 25))

    @Test
    fun bubbleTouchingBottomExtendsToSeamWithCombinedWidth() {
        val out = SeamCovers.compute(page5, null, page6)
        val b3 = out.single { it.b.id == 3 }
        assertTrue(b3.seamBottom)
        assertFalse(b3.cover)
        assertEquals(5000, b3.b.bottom)
        assertEquals(247 - 16, b3.b.left) // min(247, 265) - lh/2
        assertEquals(552 + 16, b3.b.right) // max(552, 536) + lh/2
        // El globo de arriba no se toca.
        assertEquals(page5.bubbles[0], out.single { it.b.id == 2 }.b)
    }

    @Test
    fun bubbleTouchingTopExtendsToSeamWithCombinedWidth() {
        val b1 = SeamCovers.compute(page6, page5, null).single { it.b.id == 1 }
        assertTrue(b1.seamTop)
        assertEquals(0, b1.b.top)
        assertEquals(247 - 18, b1.b.left)
        assertEquals(552 + 18, b1.b.right)
    }

    @Test
    fun missingPartnerGetsCoverOnlyPiece() {
        val emptyNext = page()
        val out = SeamCovers.compute(emptyNext, page5, null)
        val cover = out.single()
        assertTrue(cover.cover)
        assertTrue(cover.seamTop)
        assertEquals("", cover.b.text)
        assertEquals(0, cover.b.top)
        assertEquals(32 * 6 / 5 + SeamCovers.EDGE_PX, cover.b.bottom)
        assertTrue(cover.b.id < 0)
    }

    @Test
    fun noNeighborsOrDifferentWidthChangesNothing() {
        assertEquals(page5.bubbles, SeamCovers.compute(page5, null, null).map { it.b })
        val wide = PageAnalysis("w", 1000, 5000, page6.bubbles, version = 2)
        assertEquals(page5.bubbles, SeamCovers.compute(page5, null, wide).map { it.b })
    }
}
