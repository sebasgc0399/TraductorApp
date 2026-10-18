package com.sebas.traductorapp

import android.app.Application
import kotlin.concurrent.thread

class TraductorApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        thread(name = "image-cache-trim", isDaemon = true) { container.imageCache.trim() }
    }
}
