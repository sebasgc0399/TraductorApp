package com.sebas.traductorapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Serie leída. El glosario se guarda en orden de inserción para que el prefijo del prompt sea estable. */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val key: String,
    val title: String,
    /** JSON de List<GlossaryEntry> en orden de inserción. */
    val glossaryJson: String = "[]",
    val summary: String = "",
    val lastUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/** OCR ya hecho de una imagen (por URL). Releer un capítulo no vuelve a correr el OCR. */
@Entity(tableName = "page_cache", indices = [Index("seriesKey")])
data class PageCacheEntity(
    @PrimaryKey val url: String,
    val seriesKey: String,
    val chapter: String?,
    /** JSON de PageAnalysis. */
    val analysisJson: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Traducción de un texto normalizado dentro de una serie. Lo repetido nunca se reenvía a la IA. */
@Entity(tableName = "text_cache", primaryKeys = ["seriesKey", "textHash"])
data class TextCacheEntity(
    val seriesKey: String,
    val textHash: String,
    val sourceText: String,
    val translated: String,
    val model: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Una llamada a la IA, para analizar el consumo. */
@Entity(tableName = "usage_log", indices = [Index("seriesKey"), Index("timestamp")])
data class UsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val seriesKey: String,
    val chapter: String?,
    val model: String,
    val bubbles: Int,
    val inputTokens: Int,
    val cachedTokens: Int,
    val cacheWriteTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val latencyMs: Long,
    val costUsd: Double,
)

/** Agregado por capítulo para la pantalla de consumo. */
data class ChapterUsage(
    val seriesKey: String,
    val chapter: String?,
    val calls: Int,
    val bubbles: Int,
    val inputTokens: Int,
    val cachedTokens: Int,
    val cacheWriteTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val lastTimestamp: Long,
)
