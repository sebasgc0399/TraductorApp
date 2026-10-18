package com.sebas.traductorapp.reader

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Intercepta imágenes, bloquea anuncios y evita redirecciones a otros sitios sin toque del usuario. */
class ReaderWebViewClient(
    private val interceptor: ImageInterceptor,
    private val listener: ReaderWebListener,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        return try {
            if (request.url?.host == APP_ASSETS_HOST) {
                appAsset(view, request.url?.path)
            } else if (AdBlocker.isBlocked(request.url?.host)) {
                emptyResponse()
            } else {
                interceptor.intercept(request, listener.currentPageUrl()) { listener.onImageCached(it) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null || !request.isForMainFrame) return false
        val target = request.url ?: return true
        val scheme = target.scheme?.lowercase()
        // intent:, market:, tg:, etc.
        if (scheme != "http" && scheme != "https") return true
        val host = target.host ?: return true
        val current = listener.currentPageUrl() ?: view?.url
        val currentHost = current?.let { Uri.parse(it).host } ?: return false
        if (sameSite(host, currentHost)) return false
        if (AdBlocker.isBlocked(host)) return true
        if (request.hasGesture()) return false
        // Redirección del servidor de una navegación ya permitida (p. ej. el sitio cambió de dominio).
        if (request.isRedirect) return false
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url != null) listener.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url != null) listener.onPageFinished(url)
    }

    private fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        200,
        "OK",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
    )

    /** Sirve las fuentes empaquetadas en assets/fonts para el texto traducido de los globos. */
    private fun appAsset(view: WebView?, path: String?): WebResourceResponse? {
        val context = view?.context ?: return null
        if (path == null || !path.startsWith("/fonts/") || path.contains("..")) return null
        val stream = runCatching { context.assets.open(path.removePrefix("/")) }.getOrNull() ?: return null
        return WebResourceResponse(
            "font/ttf",
            null,
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000"),
            stream,
        )
    }

    companion object {
        private const val APP_ASSETS_HOST = "appassets.androidplatform.net"

        // Segundo nivel genérico de ccTLD: "co.uk", "com.br", "or.jp"...
        private val GENERIC_SLD = setOf(
            "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "mil", "nom", "ltd", "plc", "sch",
        )

        internal fun baseDomain(host: String): String {
            val h = host.trim().trimEnd('.').lowercase().removePrefix("www.")
            if (h.isEmpty() || h.contains(':') || h.all { it.isDigit() || it == '.' }) return h
            val labels = h.split('.')
            if (labels.size <= 2) return h
            val tld = labels[labels.size - 1]
            val sld = labels[labels.size - 2]
            val take = if (tld.length == 2 && sld in GENERIC_SLD) 3 else 2
            return labels.takeLast(take).joinToString(".")
        }

        internal fun sameSite(a: String, b: String): Boolean = baseDomain(a) == baseDomain(b)
    }
}

/** Bloquea popups (requiere setSupportMultipleWindows(true)) y reporta título y progreso. */
class ReaderChromeClient(private val listener: ReaderWebListener) : WebChromeClient() {

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean = false

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (title != null) listener.onTitle(title)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        listener.onProgress(newProgress)
    }
}
