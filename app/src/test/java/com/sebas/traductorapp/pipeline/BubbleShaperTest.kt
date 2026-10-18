package com.sebas.traductorapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BubbleShaperTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val nearBlack = 0xFF111111.toInt()

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Fondo gris con ruido (trama de dibujo), lejos del blanco. */
    private fun textured(w: Int, h: Int): IntArray {
        val random = Random(7)
        return IntArray(w * h) { gray(80 + random.nextInt(70)) }
    }

    /** "Letras": barras negras de 3 px cada 8 px, en líneas de [lineHeight] px separadas 6 px. */
    private fun drawText(px: IntArray, w: Int, text: IntBox, lineHeight: Int) {
        var y0 = text.top
        while (y0 < text.bottom) {
            val y1 = minOf(y0 + lineHeight, text.bottom)
            for (y in y0 until y1) {
                for (x in text.left until text.right) if ((x - text.left) % 8 < 3) px[y * w + x] = black
            }
            y0 = y1 + 6
        }
    }

    private fun shape(px: IntArray, w: Int, h: Int, text: IntBox, lineHeight: Int) =
        BubbleShaper.shape(px, w, h, text.left, text.top, text.right, text.bottom, lineHeight)

    private fun ShapeResult.box() = IntBox(left, top, right, bottom)

    private fun ellipse(dx: Double, dy: Double, a: Double, b: Double) = (dx / a) * (dx / a) + (dy / b) * (dy / b)

    @Test
    fun whiteEllipseBubbleGrowsInsideItsOutline() {
        val w = 500
        val h = 360
        val px = textured(w, h)
        // Globo blanco con contorno negro de 4 px.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - 250.0
                val dy = y - 180.0
                if (ellipse(dx, dy, 170.0, 110.0) <= 1.0) {
                    px[y * w + x] = if (ellipse(dx, dy, 166.0, 106.0) <= 1.0) white else black
                }
            }
        }
        val text = IntBox(170, 155, 330, 202)
        drawText(px, w, text, 20)
        val padded = BubbleShaper.paddedBox(text.left, text.top, text.right, text.bottom, 20, w, h)

        val r = shape(px, w, h, text, 20)

        assertTrue(r.uniform)
        assertEquals(white, r.bg)
        assertEquals(nearBlack, r.fg)
        assertTrue(r.left <= padded.left && r.top <= padded.top)
        assertTrue(r.right >= padded.right && r.bottom >= padded.bottom)
        assertTrue("ancho ${r.box().width}", r.box().width >= padded.width + 40)
        assertTrue("alto ${r.box().height}", r.box().height >= padded.height + 30)
        // Fuera de las letras todo es interior blanco: no pisa el contorno ni el fondo.
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) {
                if (x >= text.left && x < text.right && y >= text.top && y < text.bottom) continue
                assertEquals("($x,$y)", white, px[y * w + x])
            }
        }
    }

    @Test
    fun gradientBackgroundKeepsThePaddedTextBox() {
        val w = 480
        val h = 300
        val px = IntArray(w * h) { gray((it % w) * 255 / (w - 1)) }
        val text = IntBox(160, 130, 320, 170)
        drawText(px, w, text, 20)

        val r = shape(px, w, h, text, 20)

        assertFalse(r.uniform)
        assertEquals(IntBox(153, 124, 327, 176), r.box())
    }

    @Test
    fun textOnPlainWhitePageDoesNotGrowOnOpenSides() {
        val w = 480
        val h = 300
        val px = IntArray(w * h) { white }
        val text = IntBox(160, 130, 320, 170)
        drawText(px, w, text, 20)

        val r = shape(px, w, h, text, 20)

        assertTrue(r.uniform)
        assertEquals(white, r.bg)
        assertEquals(nearBlack, r.fg)
        assertEquals(IntBox(153, 124, 327, 176), r.box())
    }

    @Test
    fun boxCutByTheRegionEdgeOnlyGrowsOnClosedSides() {
        val w = 480
        val h = 300
        val px = textured(w, h)
        // Recuadro blanco que sigue más allá del borde superior de la región.
        for (y in 0 until 220) for (x in 80 until 400) px[y * w + x] = white
        val text = IntBox(180, 90, 300, 130)
        drawText(px, w, text, 20)

        val r = shape(px, w, h, text, 20)

        assertTrue(r.uniform)
        assertEquals(84, r.top)
        assertTrue("bottom ${r.bottom}", r.bottom in 170..220)
        assertTrue("left ${r.left}", r.left in 80..150)
        assertTrue("right ${r.right}", r.right in 330..400)
    }

    @Test
    fun darkNarrationBoxIsUniformWithLightText() {
        val w = 400
        val h = 260
        val px = textured(w, h)
        for (y in 40 until 220) for (x in 40 until 360) px[y * w + x] = black
        val text = IntBox(140, 110, 260, 150)
        for (y in 110 until 150) for (x in 140 until 260) if ((x - 140) % 8 < 3) px[y * w + x] = white

        val r = shape(px, w, h, text, 20)

        assertTrue(r.uniform)
        assertEquals(white, r.fg)
        assertTrue(r.left in 40 until 133 && r.right in 268..360)
        assertTrue(r.top in 40 until 104 && r.bottom in 157..220)
    }

    @Test
    fun overlappingSideBySideBoxesAreClippedAtTheMidline() {
        val cores = listOf(IntBox(100, 100, 200, 150), IntBox(260, 100, 360, 150))
        val boxes = listOf(IntBox(60, 60, 250, 190), IntBox(220, 70, 400, 180))

        val out = BubbleShaper.resolveOverlaps(boxes, cores)

        assertEquals(IntBox(60, 60, 230, 190), out[0])
        assertEquals(IntBox(230, 70, 400, 180), out[1])
        assertFalse(out[0].intersects(out[1]))
    }

    @Test
    fun overlappingStackedBoxesAreClippedVertically() {
        val cores = listOf(IntBox(100, 100, 300, 160), IntBox(120, 240, 280, 300))
        val boxes = listOf(IntBox(50, 40, 350, 230), IntBox(80, 190, 330, 360))

        val out = BubbleShaper.resolveOverlaps(boxes, cores)

        assertEquals(IntBox(50, 40, 350, 200), out[0])
        assertEquals(IntBox(80, 200, 330, 360), out[1])
    }

    @Test
    fun expandedBoxNeverShrinksBelowItsCore() {
        // La caja ampliada de la izquierda invade el núcleo de la derecha, que no se amplió.
        val cores = listOf(IntBox(100, 100, 200, 150), IntBox(210, 100, 300, 150))
        val boxes = listOf(IntBox(60, 60, 290, 190), IntBox(210, 100, 300, 150))

        val out = BubbleShaper.resolveOverlaps(boxes, cores)

        assertEquals(IntBox(60, 60, 205, 190), out[0])
        assertEquals(cores[1], out[1])
        assertFalse(out[0].intersects(out[1]))
    }

    @Test
    fun separateBoxesAreUnchanged() {
        val cores = listOf(IntBox(100, 100, 200, 150), IntBox(400, 100, 500, 150))
        val boxes = listOf(IntBox(60, 60, 250, 190), IntBox(350, 60, 560, 190))

        assertEquals(boxes, BubbleShaper.resolveOverlaps(boxes, cores))
    }
}
