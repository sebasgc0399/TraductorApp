package com.sebas.traductorapp.reader

/** Eventos del lector WebView hacia la capa de la app. */
interface ReaderWebListener {
    fun onPageStarted(url: String)

    fun onPageFinished(url: String)

    fun onTitle(title: String)

    fun onProgress(progress: Int)

    /** Una imagen quedó disponible en el caché de disco. Se llama desde un hilo de fondo de la WebView. */
    fun onImageCached(url: String)

    /** URL de la página actual. Debe ser segura entre hilos (respaldada por @Volatile). */
    fun currentPageUrl(): String?
}
