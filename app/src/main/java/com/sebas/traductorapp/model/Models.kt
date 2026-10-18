package com.sebas.traductorapp.model

import kotlinx.serialization.Serializable

/** Una línea reconocida por OCR, en coordenadas de la imagen completa (px). */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Un globo de diálogo detectado en una página.
 * Coordenadas en px de la imagen original: es el área que se cubre y donde se escribe el español
 * (el interior del globo si el fondo es liso, o la caja del texto con margen si no lo es).
 * [lineHeight] es la altura media de sus líneas (px).
 * [bg] y [fg] son colores ARGB empaquetados en Int (fondo muestreado y color de texto legible).
 * [uniform] = el fondo alrededor del texto es liso (globo blanco típico): se pinta opaco, sin desenfoque.
 */
@Serializable
data class Bubble(
    val id: Int,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val lineHeight: Int,
    val confidence: Float,
    val bg: Int,
    val fg: Int,
    val uniform: Boolean = false,
)

/** Resultado del OCR de una imagen de página. Se guarda en caché por URL. */
@Serializable
data class PageAnalysis(
    val url: String,
    val width: Int,
    val height: Int,
    val bubbles: List<Bubble>,
    /** Versión del análisis: las entradas de caché más viejas que [CURRENT_VERSION] se recalculan (el OCR es gratis). */
    val version: Int = 1,
    /** Si se conservaron las onomatopeyas: el análisis en caché solo vale con el mismo ajuste. */
    val sfx: Boolean = false,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

@Serializable
data class GlossaryEntry(
    val en: String,
    val es: String,
)

/** Identidad de la serie y capítulo que se está leyendo. */
data class ChapterInfo(
    val seriesKey: String,
    val seriesTitle: String,
    val chapter: String?,
)

/** Tokens reportados por el proveedor en una llamada. */
data class TokenUsage(
    val inputTokens: Int,
    val cachedTokens: Int,
    val cacheWriteTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
)

/** Un globo pendiente de traducir, con su id único dentro del lote. */
data class SourceLine(
    val id: Int,
    val text: String,
)

data class TranslationResult(
    /** id -> texto en español ("" si el modelo decidió que era ruido). */
    val translations: Map<Int, String>,
    /** Términos nuevos para el glosario propuestos por el modelo. */
    val newGlossary: List<GlossaryEntry>,
    val usage: TokenUsage,
    val latencyMs: Long,
    val model: String,
)
