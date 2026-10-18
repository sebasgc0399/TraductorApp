package com.sebas.traductorapp.pipeline

/** Utilidades de color: mediana por canal y color de texto legible sobre un fondo. */
object ColorSampler {
    private const val DARK_TEXT_LUMA = 0.45

    private val WHITE = 0xFFFFFFFF.toInt()
    private val NEAR_BLACK = 0xFF111111.toInt()

    /**
     * Luma sobre sRGB codificado (no linealizado). Con 0.45 el corte coincide con el punto donde texto
     * negro y blanco dan contraste parecido, así los grises claros llevan texto oscuro.
     */
    fun pickTextColor(bg: Int): Int {
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return if (luma > DARK_TEXT_LUMA) NEAR_BLACK else WHITE
    }

    fun medianColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return WHITE
        val reds = IntArray(256)
        val greens = IntArray(256)
        val blues = IntArray(256)
        for (p in pixels) {
            reds[(p shr 16) and 0xFF]++
            greens[(p shr 8) and 0xFF]++
            blues[p and 0xFF]++
        }
        val target = (pixels.size + 1) / 2
        return (0xFF shl 24) or
            (medianOf(reds, target) shl 16) or
            (medianOf(greens, target) shl 8) or
            medianOf(blues, target)
    }

    private fun medianOf(histogram: IntArray, target: Int): Int {
        var accumulated = 0
        for (value in histogram.indices) {
            accumulated += histogram[value]
            if (accumulated >= target) return value
        }
        return 255
    }
}
