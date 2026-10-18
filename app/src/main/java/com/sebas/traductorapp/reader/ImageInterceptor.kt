package com.sebas.traductorapp.reader

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.sebas.traductorapp.data.ImageDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Descarga las imágenes de página una sola vez con OkHttp (cookies de la WebView, Referer y UA),
 * las guarda en [ImageDiskCache] y se las entrega a la WebView. Ante cualquier problema devuelve
 * null para que la WebView cargue la imagen por su cuenta.
 */
class ImageInterceptor(
    private val http: OkHttpClient,
    private val cache: ImageDiskCache,
    private val userAgent: () -> String,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    private class Fetched(val bytes: ByteArray, val mime: String)

    private class Loaded(val file: File?, val bytes: ByteArray?, val mime: String?, val cached: Boolean)

    /**
     * Se ejecuta en el hilo de IO de la WebView (bloquear está bien). [onCached] se invoca cuando
     * la imagen queda en caché, ya sea recién descargada o porque ya estaba.
     */
    fun intercept(
        request: WebResourceRequest,
        pageUrl: String?,
        onCached: (String) -> Unit,
    ): WebResourceResponse? {
        try {
            if (!request.method.equals("GET", ignoreCase = true)) return null
            val uri = request.url ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val headers: Map<String, String> = request.requestHeaders ?: emptyMap()
            if (!looksLikeImage(uri.path ?: "", headerValue(headers, "Accept"))) return null
            val url = uri.toString()

            cache.get(url)?.let { file ->
                val response = fileResponse(file, url)
                if (response != null) {
                    safeNotify(onCached, url)
                    return response
                }
            }

            val referer = headerValue(headers, "Referer")?.takeIf { it.isNotBlank() } ?: pageUrl
            val loaded = load(url, headers, referer, MIN_CACHE_BYTES) ?: return null
            val response = when {
                loaded.bytes != null -> bytesResponse(loaded.bytes, loaded.mime ?: guessMime(url))
                loaded.file != null -> fileResponse(loaded.file, url)
                else -> null
            }
            if (response != null && loaded.cached) safeNotify(onCached, url)
            return response
        } catch (_: Throwable) {
            return null
        }
    }

    /** Descarga una imagen que la WebView aún no pidió (prefetch). Devuelve el archivo en caché o null. */
    suspend fun fetchToCache(url: String, referer: String?): File? = withContext(Dispatchers.IO) {
        try {
            cache.get(url) ?: load(url, emptyMap(), referer, 1)?.file
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    // Una sola descarga a la vez por URL: si la WebView y el prefetch piden lo mismo, el segundo usa el caché.
    private fun load(url: String, headers: Map<String, String>, referer: String?, minCacheBytes: Int): Loaded? =
        withUrlLock(url) {
            val hit = cache.get(url)
            if (hit != null) {
                Loaded(file = hit, bytes = null, mime = null, cached = true)
            } else {
                val fetched = fetch(url, headers, referer)
                when {
                    fetched == null -> null
                    fetched.bytes.size >= minCacheBytes -> {
                        val file = try {
                            cache.put(url, fetched.bytes)
                        } catch (_: Exception) {
                            null
                        }
                        Loaded(file = file, bytes = fetched.bytes, mime = fetched.mime, cached = file != null)
                    }
                    else -> Loaded(file = null, bytes = fetched.bytes, mime = fetched.mime, cached = false)
                }
            }
        }

    private fun fetch(url: String, headers: Map<String, String>, referer: String?): Fetched? {
        val builder = Request.Builder().url(url).get()
        for ((name, value) in headers) {
            if (name.lowercase() in SKIP_HEADERS) continue
            safeHeader(builder, name, value)
        }
        if (headerValue(headers, "Accept") == null) safeHeader(builder, "Accept", DEFAULT_ACCEPT)
        val cookie = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Throwable) {
            null
        }
        if (!cookie.isNullOrBlank()) safeHeader(builder, "Cookie", cookie)
        if (referer != null && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            safeHeader(builder, "Referer", referer)
        }
        safeHeader(builder, "User-Agent", userAgent())

        return http.newCall(builder.build()).execute().use { resp ->
            // 403, desafío de Cloudflare, etc.: que la WebView lo cargue por su cuenta
            if (resp.code != 200) return null
            if (resp.header("cf-mitigated") != null) return null
            val body = resp.body
            val declared = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val length = body.contentLength()
            if (length > MAX_BYTES) return null
            val bytes = readCapped(body.byteStream(), length) ?: return null
            if (bytes.isEmpty()) return null
            val sniffed = ImageDiskCache.sniffMime(bytes.copyOf(minOf(bytes.size, SNIFF_BYTES)))
            val mime = sniffed ?: declared?.takeIf { it.startsWith("image/") } ?: return null
            Fetched(bytes, mime)
        }
    }

    private fun readCapped(input: InputStream, declaredLength: Long): ByteArray? {
        val initial = if (declaredLength in 1..MAX_BYTES) declaredLength.toInt() else 256 * 1024
        val out = ByteArrayOutputStream(initial)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun fileResponse(file: File, url: String): WebResourceResponse? = try {
        val head = ByteArray(SNIFF_BYTES)
        val n = FileInputStream(file).use { it.read(head) }
        val mime = (if (n > 0) ImageDiskCache.sniffMime(head.copyOf(n)) else null) ?: guessMime(url)
        WebResourceResponse(mime, null, 200, "OK", RESPONSE_HEADERS, FileInputStream(file))
    } catch (_: Exception) {
        null
    }

    private fun bytesResponse(bytes: ByteArray, mime: String): WebResourceResponse =
        WebResourceResponse(mime, null, 200, "OK", RESPONSE_HEADERS, ByteArrayInputStream(bytes))

    private fun <T> withUrlLock(url: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(url) { Any() }
        try {
            return synchronized(lock) { block() }
        } finally {
            locks.remove(url, lock)
        }
    }

    private fun safeNotify(onCached: (String) -> Unit, url: String) {
        try {
            onCached(url)
        } catch (_: Throwable) {
        }
    }

    private fun safeHeader(builder: Request.Builder, name: String, value: String) {
        try {
            builder.header(name, value)
        } catch (_: IllegalArgumentException) {
            // nombre o valor no válido para OkHttp (p. ej. caracteres no ASCII)
        }
    }

    companion object {
        private const val MIN_CACHE_BYTES = 30 * 1024
        private const val MAX_BYTES = 40L * 1024 * 1024
        private const val SNIFF_BYTES = 64
        private const val DEFAULT_ACCEPT = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"

        private val IMAGE_EXTENSIONS = listOf(".webp", ".jpg", ".jpeg", ".png", ".avif", ".gif")

        // Cabeceras que no se copian: las pone OkHttp, las agregamos nosotros, o provocarían 206/304.
        private val SKIP_HEADERS = setOf(
            "cookie", "user-agent", "referer", "host", "connection", "content-length",
            "accept-encoding", "range", "if-range", "if-none-match", "if-modified-since",
            "transfer-encoding", "upgrade", "te", "keep-alive", "proxy-connection",
        )

        private val RESPONSE_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "max-age=86400",
        )

        /** Extensión de imagen en la ruta (ignorando query y fragmento) o cabecera Accept de imagen. */
        fun looksLikeImage(path: String, accept: String?): Boolean {
            val clean = path.substringBefore('?').substringBefore('#').lowercase()
            if (IMAGE_EXTENSIONS.any { clean.endsWith(it) }) return true
            return accept?.trimStart()?.startsWith("image/", ignoreCase = true) == true
        }

        private fun headerValue(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        private fun guessMime(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".gif") -> "image/gif"
                path.endsWith(".avif") -> "image/avif"
                else -> "image/jpeg"
            }
        }
    }
}
