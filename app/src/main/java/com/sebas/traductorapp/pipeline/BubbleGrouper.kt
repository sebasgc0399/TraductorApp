package com.sebas.traductorapp.pipeline

import com.sebas.traductorapp.model.OcrLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Líneas OCR que forman un mismo globo. Caja en px de la imagen completa. */
data class LineGroup(
    val lines: List<OcrLine>,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val lineHeight: Int,
    val confidence: Float,
)

object BubbleGrouper {
    private const val MAX_GAP_FACTOR = 1.0f
    private const val MIN_OVERLAP_FRACTION = 0.2f
    private const val MAX_HEIGHT_RATIO = 1.8f
    private const val ROW_OVERLAP_FRACTION = 0.5f

    private val WHITESPACE = Regex("\\s+")
    private val HYPHENS = charArrayOf('-', '‐', '‑', '­')

    fun group(lines: List<OcrLine>): List<LineGroup> {
        val valid = lines
            .filter { it.width > 0 && it.height > 0 && it.text.isNotBlank() }
            .sortedBy { it.top }
        if (valid.isEmpty()) return emptyList()

        val parent = IntArray(valid.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        for (i in valid.indices) {
            val a = valid[i]
            for (j in i + 1 until valid.size) {
                val b = valid[j]
                // Ordenadas por top: si b ya está más de una altura de a por debajo, ninguna posterior se une a a.
                if (b.top - a.bottom > a.height * MAX_GAP_FACTOR) break
                if (belongTogether(a, b)) {
                    val ra = find(i)
                    val rb = find(j)
                    if (ra != rb) parent[rb] = ra
                }
            }
        }

        val groups = valid.indices
            .groupBy { find(it) }
            .values
            .map { indices -> buildGroup(indices.map { valid[it] }) }
        return readingOrder(groups, { it.top }, { it.bottom }, { it.left })
    }

    private fun belongTogether(a: OcrLine, b: OcrLine): Boolean {
        val minH = min(a.height, b.height)
        val maxH = max(a.height, b.height)
        // Alturas muy distintas: una onomatopeya grande no se mezcla con el diálogo.
        if (maxH >= MAX_HEIGHT_RATIO * minH) return false

        val gap = max(a.top, b.top) - min(a.bottom, b.bottom)
        if (gap > MAX_GAP_FACTOR * minH) return false

        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap >= MIN_OVERLAP_FRACTION * min(a.width, b.width)) return true
        val centerDistance = abs((a.left + a.right) - (b.left + b.right)) / 2f
        return centerDistance <= max(a.width, b.width) / 2f
    }

    private fun buildGroup(members: List<OcrLine>): LineGroup {
        val ordered = readingOrder(members, { it.top }, { it.bottom }, { it.left })
        return LineGroup(
            lines = ordered,
            left = ordered.minOf { it.left },
            top = ordered.minOf { it.top },
            right = ordered.maxOf { it.right },
            bottom = ordered.maxOf { it.bottom },
            text = joinLines(ordered.map { it.text }),
            lineHeight = medianInt(ordered.map { it.height }),
            confidence = ordered.map { it.confidence }.average().toFloat(),
        )
    }

    private fun joinLines(texts: List<String>): String {
        val sb = StringBuilder()
        for (raw in texts) {
            val t = WHITESPACE.replace(raw, " ").trim()
            if (t.isEmpty()) continue
            val n = sb.length
            when {
                n == 0 -> sb.append(t)
                // Palabra partida con guion al final de la línea: "INCRED-" + "IBLE" -> "INCREDIBLE".
                n >= 2 && sb[n - 1] in HYPHENS && sb[n - 2].isLetter() && t[0].isLetter() -> {
                    sb.setLength(n - 1)
                    sb.append(t)
                }
                else -> sb.append(' ').append(t)
            }
        }
        return WHITESPACE.replace(sb.toString(), " ").trim()
    }

    /**
     * Orden de lectura: de arriba abajo; los elementos cuyo rango vertical se solapa más del 50 % de la
     * altura menor con el primero de la fila forman una fila y se leen de izquierda a derecha.
     */
    private fun <T> readingOrder(
        items: List<T>,
        top: (T) -> Int,
        bottom: (T) -> Int,
        left: (T) -> Int,
    ): List<T> {
        val sorted = items.sortedWith(compareBy<T> { top(it) }.thenBy { left(it) })
        val used = BooleanArray(sorted.size)
        val result = ArrayList<T>(sorted.size)
        for (i in sorted.indices) {
            if (used[i]) continue
            used[i] = true
            val anchor = sorted[i]
            val anchorTop = top(anchor)
            val anchorBottom = bottom(anchor)
            val row = mutableListOf(anchor)
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val candidate = sorted[j]
                val candidateTop = top(candidate)
                if (candidateTop >= anchorBottom) break
                val candidateBottom = bottom(candidate)
                val overlap = min(anchorBottom, candidateBottom) - max(anchorTop, candidateTop)
                val minHeight = min(anchorBottom - anchorTop, candidateBottom - candidateTop)
                if (minHeight > 0 && overlap > ROW_OVERLAP_FRACTION * minHeight) {
                    row.add(candidate)
                    used[j] = true
                }
            }
            row.sortBy { left(it) }
            result.addAll(row)
        }
        return result
    }
}

/** Mediana de enteros (promedio de los dos centrales si la cantidad es par); 0 si la lista está vacía. */
internal fun medianInt(values: List<Int>): Int {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}
