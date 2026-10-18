package com.sebas.traductorapp.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Caja final de un globo con su fondo, color de texto y si el fondo es liso. */
data class ShapeResult(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val bg: Int,
    val fg: Int,
    val uniform: Boolean,
)

/** Rectángulo entero [left, right) × [top, bottom). */
internal data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersects(other: IntBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Decide qué área cubrir para cada globo.
 *
 * - Anillo de 2–6 px fuera de la caja del texto con margen: su mediana es el fondo. Es "liso" si al menos el
 *   90 % del anillo está a ≤ 48 (|ΔR|+|ΔG|+|ΔB|) de esa mediana.
 * - Si es liso, relleno por inundación (tolerancia 64; la caja del texto es transitable) para hallar el interior
 *   del globo. Si el relleno toca el borde de la región, ese lado está "abierto" (texto sobre fondo liso
 *   sin contorno) y no se amplía.
 * - El rectángulo crece desde la caja del texto, solo por filas/columnas que estén enteras dentro del relleno
 *   (no pisa el contorno ni la cola), hasta el 80 % de la semiextensión del relleno, y luego se encoge 1 celda.
 * - Si no es liso (degradado, dibujo), se usa la caja del texto con margen.
 */
object BubbleShaper {
    const val PAD_X_FACTOR = 0.35f
    const val PAD_Y_FACTOR = 0.30f

    /** La región analizada es la caja del texto ampliada max(3·lh, 0.6·ancho) y max(3·lh, 0.8·alto) por lado. */
    private const val REGION_LH_FACTOR = 3f
    private const val REGION_W_FACTOR = 0.6f
    private const val REGION_H_FACTOR = 0.8f

    /** Lado máximo de la rejilla de análisis: se toma 1 de cada k píxeles. */
    private const val MAX_ANALYSIS_SIDE = 400

    private const val RING_INNER_PX = 2
    private const val RING_OUTER_PX = 6
    private const val MATCH_TOLERANCE = 48
    private const val FILL_TOLERANCE = 64
    private const val UNIFORM_FRACTION = 0.9f

    /** Los globos son más o menos elípticos: se usa el 80 % de la semiextensión del interior. */
    private const val INTERIOR_FACTOR = 0.80f
    private const val INSET_CELLS = 1

    private val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Entrada Android: decodifica solo la región necesaria con [decoder] (o la recorta de [fallback], que es la
     * imagen reducida por [fallbackScale]) y llama a [shape]. Coordenadas en px de la imagen completa.
     */
    fun analyze(
        decoder: BitmapRegionDecoder?,
        fallback: Bitmap?,
        fallbackScale: Int,
        textLeft: Int,
        textTop: Int,
        textRight: Int,
        textBottom: Int,
        lineHeight: Int,
        imageW: Int,
        imageH: Int,
    ): ShapeResult {
        val core = paddedBox(textLeft, textTop, textRight, textBottom, lineHeight, imageW, imageH)
        val plain = flat(core, WHITE)
        if (core.isEmpty) return plain

        val region = analysisRegion(textLeft, textTop, textRight, textBottom, lineHeight, imageW, imageH)
        val crop = try {
            when {
                decoder != null && !decoder.isRecycled -> cropFromDecoder(decoder, region)
                fallback != null && !fallback.isRecycled -> cropFromBitmap(fallback, max(1, fallbackScale), region)
                else -> null
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (crop == null) return plain

        val sx = crop.scaleX
        val sy = crop.scaleY
        val localLeft = floor((textLeft - crop.originX) * sx).toInt()
        val localTop = floor((textTop - crop.originY) * sy).toInt()
        val localRight = ceil((textRight - crop.originX) * sx).toInt()
        val localBottom = ceil((textBottom - crop.originY) * sy).toInt()
        val localLh = max(1, (lineHeight * sy).roundToInt())
        val local = paddedBox(localLeft, localTop, localRight, localBottom, localLh, crop.width, crop.height)
        val res = shape(crop.pixels, crop.width, crop.height, localLeft, localTop, localRight, localBottom, localLh)
        if (!res.uniform) return flat(core, res.bg)

        // De vuelta a la imagen completa; los lados que no crecieron quedan exactamente en la caja con margen.
        val left = if (res.left >= local.left) {
            core.left
        } else {
            floor(crop.originX + res.left / sx).toInt().coerceIn(0, core.left)
        }
        val top = if (res.top >= local.top) {
            core.top
        } else {
            floor(crop.originY + res.top / sy).toInt().coerceIn(0, core.top)
        }
        val right = if (res.right <= local.right) {
            core.right
        } else {
            ceil(crop.originX + res.right / sx).toInt().coerceIn(core.right, imageW)
        }
        val bottom = if (res.bottom <= local.bottom) {
            core.bottom
        } else {
            ceil(crop.originY + res.bottom / sy).toInt().coerceIn(core.bottom, imageH)
        }
        return ShapeResult(left, top, right, bottom, res.bg, res.fg, true)
    }

    /**
     * Puro: [pixels] son los ARGB de la región (por filas, [regionW]×[regionH]) y la caja del texto está en
     * coordenadas de la región. El resultado también.
     */
    fun shape(
        pixels: IntArray,
        regionW: Int,
        regionH: Int,
        textLeft: Int,
        textTop: Int,
        textRight: Int,
        textBottom: Int,
        lineHeight: Int,
    ): ShapeResult {
        val core = paddedBox(textLeft, textTop, textRight, textBottom, lineHeight, regionW, regionH)
        if (core.isEmpty || regionW <= 0 || regionH <= 0 || pixels.size < regionW * regionH) return flat(core, WHITE)

        // Rejilla reducida: la celda (gx, gy) es el píxel (gx·k, gy·k).
        val k = max(1, ceilDiv(max(regionW, regionH), MAX_ANALYSIS_SIDE))
        val gw = ceilDiv(regionW, k)
        val gh = ceilDiv(regionH, k)
        val grid = IntArray(gw * gh)
        for (gy in 0 until gh) {
            val src = gy * k * regionW
            val dst = gy * gw
            for (gx in 0 until gw) grid[dst + gx] = pixels[src + gx * k]
        }
        val cl = core.left / k
        val ct = core.top / k
        val cr = ceilDiv(core.right, k)
        val cb = ceilDiv(core.bottom, k)

        // Anillo: celdas a distancia (Chebyshev) ringIn..ringOut de la caja con margen.
        val ringIn = max(1, ceilDiv(RING_INNER_PX, k))
        val ringOut = max(ringIn, RING_OUTER_PX / k)
        val x0 = max(0, cl - ringOut)
        val x1 = min(gw, cr + ringOut)
        val y0 = max(0, ct - ringOut)
        val y1 = min(gh, cb + ringOut)
        val holeL = cl - ringIn + 1
        val holeR = cr + ringIn - 1
        val holeT = ct - ringIn + 1
        val holeB = cb + ringIn - 1
        val ring = IntArray(max(0, (x1 - x0) * (y1 - y0)))
        var ringCount = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (x < holeL || x >= holeR || y < holeT || y >= holeB) ring[ringCount++] = y * gw + x
            }
        }
        if (ringCount == 0) return flat(core, WHITE)
        val colors = IntArray(ringCount) { grid[ring[it]] }
        val bg = ColorSampler.medianColor(colors)
        val fg = ColorSampler.pickTextColor(bg)
        val matching = colors.count { distance(it, bg) <= MATCH_TOLERANCE }
        if (matching < UNIFORM_FRACTION * ringCount) return flat(core, bg)

        // Relleno BFS (4 vecinos) desde el anillo; la caja del texto se atraviesa para pasar por las letras.
        val inside = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        var head = 0
        var tail = 0
        for (i in 0 until ringCount) {
            val idx = ring[i]
            if (!inside[idx] && distance(grid[idx], bg) <= MATCH_TOLERANCE) {
                inside[idx] = true
                queue[tail++] = idx
            }
        }
        var minX = gw
        var maxX = -1
        var minY = gh
        var maxY = -1
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % gw
            val y = idx / gw
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            for (dir in 0 until 4) {
                val nx = when (dir) {
                    0 -> x - 1
                    1 -> x + 1
                    else -> x
                }
                val ny = when (dir) {
                    2 -> y - 1
                    3 -> y + 1
                    else -> y
                }
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                val n = ny * gw + nx
                if (inside[n]) continue
                val inCore = nx >= cl && nx < cr && ny >= ct && ny < cb
                if (inCore || distance(grid[n], bg) <= FILL_TOLERANCE) {
                    inside[n] = true
                    queue[tail++] = n
                }
            }
        }

        // Tocar el borde de la región = lado abierto: ahí no se amplía.
        val (targetL, targetR) = target(minX, maxX + 1, minX == 0, maxX == gw - 1, cl, cr)
        val (targetT, targetB) = target(minY, maxY + 1, minY == 0, maxY == gh - 1, ct, cb)

        var l = cl
        var t = ct
        var r = cr
        var b = cb
        var grew = true
        while (grew) {
            grew = false
            if (l > targetL && columnInside(inside, gw, l - 1, t, b)) {
                l--
                grew = true
            }
            if (r < targetR && columnInside(inside, gw, r, t, b)) {
                r++
                grew = true
            }
            if (t > targetT && rowInside(inside, gw, t - 1, l, r)) {
                t--
                grew = true
            }
            if (b < targetB && rowInside(inside, gw, b, l, r)) {
                b++
                grew = true
            }
        }
        if (l < cl) l = min(l + INSET_CELLS, cl)
        if (r > cr) r = max(r - INSET_CELLS, cr)
        if (t < ct) t = min(t + INSET_CELLS, ct)
        if (b > cb) b = max(b - INSET_CELLS, cb)

        val left = if (l < cl) l * k else core.left
        val top = if (t < ct) t * k else core.top
        val right = if (r > cr) min(r * k, regionW) else core.right
        val bottom = if (b > cb) min(b * k, regionH) else core.bottom
        return ShapeResult(left, top, right, bottom, bg, fg, true)
    }

    /** Caja del texto con el margen de siempre (0.35·lh en x, 0.30·lh en y), recortada a [0, maxW]×[0, maxH]. */
    internal fun paddedBox(left: Int, top: Int, right: Int, bottom: Int, lineHeight: Int, maxW: Int, maxH: Int): IntBox {
        val lh = max(1, lineHeight)
        val padX = (PAD_X_FACTOR * lh).roundToInt()
        val padY = (PAD_Y_FACTOR * lh).roundToInt()
        return IntBox(
            (left - padX).coerceAtLeast(0),
            (top - padY).coerceAtLeast(0),
            (right + padX).coerceAtMost(maxW),
            (bottom + padY).coerceAtMost(maxH),
        )
    }

    /**
     * Si dos cajas se pisan, cada una retrocede hacia su [cores] (caja del texto con margen) en el eje donde los
     * textos están más separados, cortando en la mitad del hueco. Nunca queda más chica que su núcleo.
     */
    internal fun resolveOverlaps(boxes: List<IntBox>, cores: List<IntBox>): List<IntBox> {
        require(boxes.size == cores.size)
        val out = boxes.toMutableList()
        for (i in out.indices) {
            for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                if (!a.intersects(b)) continue
                val ca = cores[i]
                val cb = cores[j]
                val gapX = max(cb.left - ca.right, ca.left - cb.right)
                val gapY = max(cb.top - ca.bottom, ca.top - cb.bottom)
                if (gapX >= gapY) {
                    if (ca.left + ca.right <= cb.left + cb.right) {
                        val mid = (ca.right + cb.left) / 2
                        out[i] = a.copy(right = min(a.right, max(mid, ca.right)))
                        out[j] = b.copy(left = max(b.left, min(mid, cb.left)))
                    } else {
                        val mid = (cb.right + ca.left) / 2
                        out[j] = b.copy(right = min(b.right, max(mid, cb.right)))
                        out[i] = a.copy(left = max(a.left, min(mid, ca.left)))
                    }
                } else {
                    if (ca.top + ca.bottom <= cb.top + cb.bottom) {
                        val mid = (ca.bottom + cb.top) / 2
                        out[i] = a.copy(bottom = min(a.bottom, max(mid, ca.bottom)))
                        out[j] = b.copy(top = max(b.top, min(mid, cb.top)))
                    } else {
                        val mid = (cb.bottom + ca.top) / 2
                        out[j] = b.copy(bottom = min(b.bottom, max(mid, cb.bottom)))
                        out[i] = a.copy(top = max(a.top, min(mid, ca.top)))
                    }
                }
            }
        }
        return out
    }

    /** Límites [lo, hi) a los que puede crecer un eje: 80 % de la semiextensión del relleno, o el núcleo si está abierto. */
    private fun target(lo: Int, hi: Int, openLo: Boolean, openHi: Boolean, coreLo: Int, coreHi: Int): Pair<Int, Int> {
        // Con un lado abierto no se conoce el centro del globo: se usa el del texto.
        val center = if (!openLo && !openHi) (lo + hi) / 2f else (coreLo + coreHi) / 2f
        val a = if (openLo) coreLo else max(lo, ceil(center - (center - lo) * INTERIOR_FACTOR).toInt())
        val b = if (openHi) coreHi else min(hi, floor(center + (hi - center) * INTERIOR_FACTOR).toInt())
        return min(a, coreLo) to max(b, coreHi)
    }

    /** La caja con margen tal cual, sin fondo liso. */
    private fun flat(core: IntBox, bg: Int): ShapeResult =
        ShapeResult(core.left, core.top, core.right, core.bottom, bg, ColorSampler.pickTextColor(bg), false)

    private fun columnInside(inside: BooleanArray, gw: Int, x: Int, top: Int, bottom: Int): Boolean {
        for (y in top until bottom) if (!inside[y * gw + x]) return false
        return true
    }

    private fun rowInside(inside: BooleanArray, gw: Int, y: Int, left: Int, right: Int): Boolean {
        val row = y * gw
        for (x in left until right) if (!inside[row + x]) return false
        return true
    }

    private fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private fun analysisRegion(
        textLeft: Int,
        textTop: Int,
        textRight: Int,
        textBottom: Int,
        lineHeight: Int,
        imageW: Int,
        imageH: Int,
    ): IntBox {
        val lh = max(1, lineHeight)
        val expX = max(REGION_LH_FACTOR * lh, REGION_W_FACTOR * (textRight - textLeft)).roundToInt()
        val expY = max(REGION_LH_FACTOR * lh, REGION_H_FACTOR * (textBottom - textTop)).roundToInt()
        return IntBox(
            (textLeft - expX).coerceIn(0, imageW),
            (textTop - expY).coerceIn(0, imageH),
            (textRight + expX).coerceIn(0, imageW),
            (textBottom + expY).coerceIn(0, imageH),
        )
    }

    /** Píxeles de una región; [scaleX]/[scaleY] = px del recorte por px de la imagen completa. */
    private class Crop(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val originX: Int,
        val originY: Int,
        val scaleX: Float,
        val scaleY: Float,
    )

    private fun cropFromDecoder(decoder: BitmapRegionDecoder, region: IntBox): Crop? {
        val l = region.left.coerceIn(0, decoder.width)
        val t = region.top.coerceIn(0, decoder.height)
        val r = region.right.coerceIn(0, decoder.width)
        val b = region.bottom.coerceIn(0, decoder.height)
        if (r - l < 1 || b - t < 1) return null
        // Submuestreo en potencias de 2 sin bajar de MAX_ANALYSIS_SIDE: menos memoria, misma rejilla.
        var sample = 1
        while (max(r - l, b - t) / (sample * 2) >= MAX_ANALYSIS_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val bitmap: Bitmap = decoder.decodeRegion(Rect(l, t, r, b), options) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 1 || h < 1) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            Crop(pixels, w, h, l, t, w.toFloat() / (r - l), h.toFloat() / (b - t))
        } finally {
            bitmap.recycle()
        }
    }

    private fun cropFromBitmap(bitmap: Bitmap, scale: Int, region: IntBox): Crop? {
        val l = (region.left / scale).coerceIn(0, bitmap.width)
        val t = (region.top / scale).coerceIn(0, bitmap.height)
        val r = ceilDiv(region.right, scale).coerceIn(0, bitmap.width)
        val b = ceilDiv(region.bottom, scale).coerceIn(0, bitmap.height)
        if (r - l < 1 || b - t < 1) return null
        val w = r - l
        val h = b - t
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, l, t, w, h)
        val inverse = 1f / scale
        return Crop(pixels, w, h, l * scale, t * scale, inverse, inverse)
    }
}
