package com.sebas.traductorapp.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import com.sebas.traductorapp.model.Bubble
import com.sebas.traductorapp.model.PageAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Imagen de página -> globos de texto en orden de lectura (OCR en el dispositivo, sin IA). */
class PageProcessor {
    private val ocr = TiledOcr()

    suspend fun process(url: String, file: File, translateSfx: Boolean): PageAnalysis? =
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            // Logos, avatares y banners pequeños no son páginas del cómic.
            if (width < MIN_PAGE_WIDTH || height < MIN_PAGE_HEIGHT) return@withContext null

            val groups = BubbleGrouper.group(ocr.recognize(file, width, height))
            val pageLineHeight = medianInt(groups.flatMap { group -> group.lines.map { it.height } })
            val kept = groups.mapNotNull { group ->
                val text = TextFilter.clean(group.text)
                when {
                    TextFilter.isNoise(text) -> null
                    !translateSfx && TextFilter.isSfx(group, pageLineHeight) -> null
                    else -> group to text
                }
            }
            if (kept.isEmpty()) {
                return@withContext PageAnalysis(url, width, height, emptyList(), version = PageAnalysis.CURRENT_VERSION)
            }

            val source = openPixelSource(file, width, height)
            val shapes = try {
                kept.map { (group, _) ->
                    BubbleShaper.analyze(
                        source.decoder,
                        source.bitmap,
                        source.scale,
                        group.left,
                        group.top,
                        group.right,
                        group.bottom,
                        group.lineHeight,
                        width,
                        height,
                    )
                }
            } finally {
                source.release()
            }

            // Globos ampliados que se pisan: cada uno retrocede hasta la mitad del hueco entre los textos.
            val cores = kept.map { (group, _) ->
                BubbleShaper.paddedBox(group.left, group.top, group.right, group.bottom, group.lineHeight, width, height)
            }
            val boxes = BubbleShaper.resolveOverlaps(
                shapes.map { IntBox(it.left, it.top, it.right, it.bottom) },
                cores,
            )
            val bubbles = kept.mapIndexed { index, (group, text) ->
                val shape = shapes[index]
                val box = boxes[index]
                Bubble(
                    id = index + 1,
                    text = text,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    lineHeight = group.lineHeight,
                    confidence = group.confidence,
                    bg = shape.bg,
                    fg = shape.fg,
                    uniform = shape.uniform,
                )
            }
            PageAnalysis(url, width, height, bubbles, version = PageAnalysis.CURRENT_VERSION)
        }

    fun close() {
        ocr.close()
    }

    /** Un solo decoder por página para todos los globos; si no abre, un bitmap reducido. */
    private fun openPixelSource(file: File, width: Int, height: Int): PixelSource {
        val decoder: BitmapRegionDecoder? = try {
            BitmapRegionDecoder.newInstance(file.absolutePath)
        } catch (e: Exception) {
            null
        }
        if (decoder != null) return PixelSource(decoder, null, 1)

        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) * 4 > FALLBACK_BITMAP_BYTES) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap: Bitmap? = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        // Factor real (por si el decoder no respetó inSampleSize al pie de la letra).
        val scale = if (bitmap != null && bitmap.width > 0) {
            (width.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        } else {
            1
        }
        return PixelSource(null, bitmap, scale)
    }

    /** [scale]: px de la imagen completa por px de [bitmap]. */
    private class PixelSource(
        val decoder: BitmapRegionDecoder?,
        val bitmap: Bitmap?,
        val scale: Int,
    ) {
        fun release() {
            decoder?.recycle()
            bitmap?.recycle()
        }
    }

    private companion object {
        const val MIN_PAGE_WIDTH = 500
        const val MIN_PAGE_HEIGHT = 300
        const val FALLBACK_BITMAP_BYTES = 16L * 1024 * 1024
    }
}
