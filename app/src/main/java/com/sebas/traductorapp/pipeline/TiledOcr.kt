package com.sebas.traductorapp.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sebas.traductorapp.model.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** OCR de páginas muy altas por franjas horizontales del ancho completo, con solape entre franjas. */
class TiledOcr {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Franja en coordenadas de la imagen completa: filas [top, bottom). */
    private class Tile(val top: Int, val bottom: Int)

    private class TileLine(val line: OcrLine, val tile: Int)

    suspend fun recognize(file: File, width: Int, height: Int): List<OcrLine> =
        withContext(Dispatchers.Default) {
            if (width <= 0 || height <= 0) return@withContext emptyList()
            val viaDecoder = try {
                recognizeWithRegionDecoder(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "BitmapRegionDecoder falló; uso decodeFile", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Sin memoria con BitmapRegionDecoder; uso decodeFile", e)
                null
            }
            val lines = viaDecoder ?: try {
                recognizeWithFullBitmap(file, width, height)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo decodificar ${file.name}", e)
                emptyList()
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Sin memoria al decodificar ${file.name}", e)
                emptyList()
            }
            dedupe(lines)
        }

    fun close() {
        recognizer.close()
    }

    /** null si el decoder no se pudo abrir; lanza excepción si falla la decodificación de un tile. */
    private suspend fun recognizeWithRegionDecoder(file: File): List<TileLine>? {
        val decoder = openDecoder(file) ?: return null
        return try {
            val width = decoder.width
            val height = decoder.height
            val options = BitmapFactory.Options().apply {
                inSampleSize = if (width > SAMPLE_WIDTH_THRESHOLD) 2 else 1
            }
            ocrTiles(width, planTiles(width, height), keep = null) { tile ->
                decoder.decodeRegion(Rect(0, tile.top, width, tile.bottom), options)
                    ?: throw IllegalStateException("decodeRegion devolvió null")
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun openDecoder(file: File): BitmapRegionDecoder? =
        try {
            BitmapRegionDecoder.newInstance(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir BitmapRegionDecoder para ${file.name}", e)
            null
        }

    private suspend fun recognizeWithFullBitmap(file: File, width: Int, height: Int): List<TileLine> {
        var sample = if (width > SAMPLE_WIDTH_THRESHOLD) 2 else 1
        while ((width.toLong() / sample) * (height.toLong() / sample) * 4 > MAX_BITMAP_BYTES) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val source: Bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return emptyList()
        return try {
            val scaleY = source.height.toFloat() / height
            ocrTiles(width, planTiles(width, height), keep = source) { tile ->
                val y0 = (tile.top * scaleY).roundToInt().coerceIn(0, source.height - 1)
                val y1 = (tile.bottom * scaleY).roundToInt().coerceIn(y0 + 1, source.height)
                // Si el recorte es la imagen entera, createBitmap devuelve la misma instancia (por eso keep).
                Bitmap.createBitmap(source, 0, y0, source.width, y1 - y0)
            }
        } finally {
            source.recycle()
        }
    }

    private fun planTiles(width: Int, height: Int): List<Tile> {
        val scale = if (width > BASE_WIDTH) width.toFloat() / BASE_WIDTH else 1f
        val tileH = (TILE_HEIGHT * scale).roundToInt()
        val overlap = (TILE_OVERLAP * scale).roundToInt()
        if (height <= tileH * SINGLE_TILE_SLACK) return listOf(Tile(0, height))
        val count = ceil((height - overlap).toDouble() / (tileH - overlap)).toInt()
        // Reparto uniforme: el último tile termina justo en el borde y el solape real queda >= overlap.
        val stride = (height - tileH).toDouble() / (count - 1)
        return List(count) { i ->
            val top = if (i == count - 1) height - tileH else (i * stride).roundToInt()
            Tile(top, top + tileH)
        }
    }

    /**
     * Ejecuta ML Kit tile por tile (secuencial). [decode] devuelve el bitmap del tile, que cubre todo el
     * ancho de la imagen, posiblemente reducido; [keep] es un bitmap que no se debe reciclar aquí.
     */
    private suspend fun ocrTiles(
        width: Int,
        tiles: List<Tile>,
        keep: Bitmap?,
        decode: (Tile) -> Bitmap,
    ): List<TileLine> {
        val out = ArrayList<TileLine>()
        for ((index, tile) in tiles.withIndex()) {
            currentCoroutineContext().ensureActive()
            val bitmap = decode(tile)
            var recycle = bitmap !== keep
            try {
                val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                collectLines(result, bitmap, width, tile, index, tiles.size, out)
            } catch (e: CancellationException) {
                // ML Kit puede seguir leyendo el bitmap: se deja al GC en vez de reciclarlo.
                recycle = false
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "OCR falló en el tile $index", e)
            } finally {
                if (recycle) bitmap.recycle()
            }
        }
        return out
    }

    private fun collectLines(
        result: Text,
        bitmap: Bitmap,
        fullWidth: Int,
        tile: Tile,
        index: Int,
        tileCount: Int,
        out: MutableList<TileLine>,
    ) {
        val tileW = bitmap.width
        val tileH = bitmap.height
        val scaleX = fullWidth.toFloat() / tileW
        val scaleY = (tile.bottom - tile.top).toFloat() / tileH
        val isFirst = index == 0
        val isLast = index == tileCount - 1
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val box = line.boundingBox ?: continue
                // Línea cortada por el borde del tile: el tile vecino la contiene completa gracias al solape.
                if (!isLast && box.bottom >= tileH - EDGE_PX) continue
                if (!isFirst && box.top <= EDGE_PX) continue
                val left = (box.left * scaleX).roundToInt().coerceIn(0, fullWidth)
                val right = (box.right * scaleX).roundToInt().coerceIn(0, fullWidth)
                val top = (tile.top + box.top * scaleY).roundToInt().coerceIn(tile.top, tile.bottom)
                val bottom = (tile.top + box.bottom * scaleY).roundToInt().coerceIn(tile.top, tile.bottom)
                if (right <= left || bottom <= top) continue
                out.add(TileLine(OcrLine(text, left, top, right, bottom, line.confidence), index))
            }
        }
    }

    /** Elimina las líneas leídas dos veces en la zona de solape; se queda con la caja más grande. */
    private fun dedupe(lines: List<TileLine>): List<OcrLine> {
        val kept = ArrayList<TileLine>(lines.size)
        for (candidate in lines) {
            val index = kept.indexOfFirst { isDuplicate(it, candidate) }
            if (index < 0) {
                kept.add(candidate)
            } else if (area(candidate.line) > area(kept[index].line)) {
                kept[index] = candidate
            }
        }
        return kept.map { it.line }
    }

    private fun isDuplicate(a: TileLine, b: TileLine): Boolean {
        val w = min(a.line.right, b.line.right) - max(a.line.left, b.line.left)
        val h = min(a.line.bottom, b.line.bottom) - max(a.line.top, b.line.top)
        if (w <= 0 || h <= 0) return false
        val intersection = w.toLong() * h
        val areaA = area(a.line)
        val areaB = area(b.line)
        val union = areaA + areaB - intersection
        if (union > 0 && intersection.toFloat() / union > IOU_DUPLICATE) return true
        // Entre tiles distintos, una caja casi contenida en otra es la misma línea leída de forma parcial.
        val smaller = min(areaA, areaB)
        return a.tile != b.tile && smaller > 0 && intersection.toFloat() / smaller > CONTAINMENT_DUPLICATE
    }

    private fun area(line: OcrLine): Long = line.width.toLong() * line.height

    private companion object {
        const val TAG = "TiledOcr"
        const val BASE_WIDTH = 1080
        const val TILE_HEIGHT = 1600
        const val TILE_OVERLAP = 250
        const val SAMPLE_WIDTH_THRESHOLD = 1600
        const val SINGLE_TILE_SLACK = 1.2f
        const val EDGE_PX = 4
        const val MAX_BITMAP_BYTES = 40L * 1024 * 1024
        const val IOU_DUPLICATE = 0.5f
        const val CONTAINMENT_DUPLICATE = 0.7f
    }
}
