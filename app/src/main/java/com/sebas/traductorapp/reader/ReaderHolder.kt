package com.sebas.traductorapp.reader

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sebas.traductorapp.AppContainer

/**
 * Mantiene vivos el WebView y la sesión mientras el lector esté en la pila de navegación,
 * para que ir al glosario o a ajustes y volver no recargue el capítulo.
 */
class ReaderHolder(private val container: AppContainer, private val initialUrl: String) {
    val session = ReaderSession(container)
    private var webView: WebView? = null

    /** false si el WebView del sistema no soporta el puente JS (habría que actualizar Android System WebView). */
    var bridgeSupported: Boolean = true
        private set

    fun webView(): WebView? = webView

    fun obtainWebView(context: Context): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = createWebView(context)
        webView = wv
        wv.loadUrl(initialUrl)
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        session.userAgent = settings.userAgentString

        webViewClient = ReaderWebViewClient(session.interceptor, session)
        webChromeClient = ReaderChromeClient(session)
        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> session.onScroll(scrollY - oldScrollY, scrollY) }

        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        bridgeSupported = supported
        if (supported) {
            WebViewCompat.addWebMessageListener(this, "TrBridge", setOf("*")) { _, message, _, isMainFrame, replyProxy ->
                session.onJsMessage(message.data, isMainFrame, replyProxy)
            }
            val script = context.assets.open("reader.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
        } else {
            Log.e("ReaderHolder", "WebView sin WEB_MESSAGE_LISTENER / DOCUMENT_START_SCRIPT")
        }
    }

    fun destroy() {
        session.close()
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
    }
}
