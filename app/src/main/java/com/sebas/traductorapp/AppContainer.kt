package com.sebas.traductorapp

import android.content.Context
import com.sebas.traductorapp.data.AppDatabase
import com.sebas.traductorapp.data.ImageDiskCache
import com.sebas.traductorapp.data.SettingsRepository
import com.sebas.traductorapp.pipeline.PageProcessor
import com.sebas.traductorapp.translate.OpenAiTranslator
import com.sebas.traductorapp.translate.Translator
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Dependencias compartidas de la app (inyección manual). */
class AppContainer(val appContext: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val db: AppDatabase = AppDatabase.create(appContext)

    val settings = SettingsRepository(appContext)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val imageCache = ImageDiskCache(File(appContext.cacheDir, "images"), maxBytes = 400L * 1024 * 1024)

    val translator: Translator = OpenAiTranslator(http)

    val pageProcessor: PageProcessor by lazy { PageProcessor() }

    /** Serializa las lecturas-modificaciones-escrituras de una serie (lector, resumen y editor de glosario). */
    val seriesLock = Mutex()
}
