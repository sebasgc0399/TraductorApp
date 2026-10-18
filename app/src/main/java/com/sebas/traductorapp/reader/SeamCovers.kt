package com.sebas.traductorapp.reader

import com.sebas.traductorapp.model.Bubble
import com.sebas.traductorapp.model.PageAnalysis

/** Un globo listo para dibujar: [cover] = cubierta sin texto; seamTop/seamBottom = toca el corte con la vecina. */
data class RenderBubble(val b: Bubble, val seamTop: Boolean, val seamBottom: Boolean, val cover: Boolean)

/**
 * Los sitios cortan el webtoon en imágenes (p. ej. 800×5000) y a veces un globo queda partido en el corte: la línea
 * cortada no la lee el OCR de ninguna de las dos imágenes y se asoman restos del original. Se coordinan las
 * cubiertas de ambos lados: llegan hasta el borde con el ancho combinado y, si del otro lado el OCR no vio nada,
 * se agrega una cubierta sin texto con el color del globo vecino.
 */
object SeamCovers {
    const val EDGE_PX = 6
    private const val COVER_ID_PREV = -1000
    private const val COVER_ID_NEXT = -2000

    fun compute(a: PageAnalysis, prevPage: PageAnalysis?, nextPage: PageAnalysis?): List<RenderBubble> {
        val prev = prevPage?.takeIf { it.width == a.width }
        val next = nextPage?.takeIf { it.width == a.width }
        fun overlapX(p: Bubble, q: Bubble) = minOf(p.right, q.right) > maxOf(p.left, q.left)
        fun atBottom(b: Bubble, h: Int) = b.bottom >= h - EDGE_PX
        fun atTop(b: Bubble) = b.top <= EDGE_PX

        val out = mutableListOf<RenderBubble>()

        for (b in a.bubbles) {
            var left = b.left
            var right = b.right
            var top = b.top
            var bottom = b.bottom
            val seamBottom = next != null && atBottom(b, a.height)
            val seamTop = prev != null && atTop(b)
            if (seamBottom) {
                bottom = a.height
                next.bubbles.firstOrNull { atTop(it) && overlapX(it, b) }?.let { p ->
                    left = minOf(left, p.left)
                    right = maxOf(right, p.right)
                }
            }
            if (seamTop) {
                top = 0
                prev.bubbles.firstOrNull { atBottom(it, prev.height) && overlapX(it, b) }?.let { p ->
                    left = minOf(left, p.left)
                    right = maxOf(right, p.right)
                }
            }
            if (seamTop || seamBottom) {
                val m = b.lineHeight / 2
                left = maxOf(0, left - m)
                right = minOf(a.width, right + m)
            }
            out += RenderBubble(b.copy(left = left, top = top, right = right, bottom = bottom), seamTop, seamBottom, cover = false)
        }
        // Continuaciones sin pareja: el vecino tiene un globo pegado al corte y aquí el OCR no vio nada.
        if (prev != null) {
            for (p in prev.bubbles) {
                if (!atBottom(p, prev.height) || a.bubbles.any { atTop(it) && overlapX(it, p) }) continue
                val m = p.lineHeight / 2
                val h = minOf(a.height, p.lineHeight * 6 / 5 + EDGE_PX)
                out += RenderBubble(
                    p.copy(id = COVER_ID_PREV - p.id, text = "", left = maxOf(0, p.left - m), right = minOf(a.width, p.right + m), top = 0, bottom = h),
                    seamTop = true, seamBottom = false, cover = true,
                )
            }
        }
        if (next != null) {
            for (n in next.bubbles) {
                if (!atTop(n) || a.bubbles.any { atBottom(it, a.height) && overlapX(it, n) }) continue
                val m = n.lineHeight / 2
                val h = n.lineHeight * 6 / 5 + EDGE_PX
                out += RenderBubble(
                    n.copy(id = COVER_ID_NEXT - n.id, text = "", left = maxOf(0, n.left - m), right = minOf(a.width, n.right + m), top = maxOf(0, a.height - h), bottom = a.height),
                    seamTop = false, seamBottom = true, cover = true,
                )
            }
        }
        return out
    }
}
