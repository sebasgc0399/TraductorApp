package com.sebas.traductorapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE `key` = :key")
    suspend fun get(key: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE `key` = :key")
    fun observe(key: String): Flow<SeriesEntity?>

    @Query("SELECT * FROM series ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<SeriesEntity>>

    @Upsert
    suspend fun upsert(series: SeriesEntity)

    @Query("DELETE FROM series WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface PageCacheDao {
    @Query("SELECT * FROM page_cache WHERE url = :url")
    suspend fun get(url: String): PageCacheEntity?

    @Upsert
    suspend fun upsert(page: PageCacheEntity)

    @Query("DELETE FROM page_cache")
    suspend fun clear()
}

@Dao
interface TextCacheDao {
    @Query("SELECT * FROM text_cache WHERE seriesKey = :seriesKey AND textHash IN (:hashes)")
    suspend fun getMany(seriesKey: String, hashes: List<String>): List<TextCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TextCacheEntity>)

    @Query("DELETE FROM text_cache WHERE seriesKey = :seriesKey")
    suspend fun clearSeries(seriesKey: String)

    /** Cuántas traducciones guardadas tiene la serie: si baja a 0, alguien pidió retraducirla. */
    @Query("SELECT COUNT(*) FROM text_cache WHERE seriesKey = :seriesKey")
    fun observeCount(seriesKey: String): Flow<Int>

    @Query("DELETE FROM text_cache")
    suspend fun clear()
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(entry: UsageLogEntity)

    @Query("SELECT * FROM usage_log ORDER BY timestamp ASC")
    suspend fun all(): List<UsageLogEntity>

    @Query(
        """
        SELECT seriesKey, chapter, COUNT(*) AS calls, SUM(bubbles) AS bubbles,
               SUM(inputTokens) AS inputTokens, SUM(cachedTokens) AS cachedTokens,
               SUM(cacheWriteTokens) AS cacheWriteTokens, SUM(outputTokens) AS outputTokens,
               SUM(costUsd) AS costUsd, MAX(timestamp) AS lastTimestamp
        FROM usage_log GROUP BY seriesKey, chapter ORDER BY lastTimestamp DESC
        """
    )
    fun observeByChapter(): Flow<List<ChapterUsage>>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage_log WHERE seriesKey = :seriesKey AND chapter IS :chapter")
    fun observeChapterCost(seriesKey: String, chapter: String?): Flow<Double>

    @Query("DELETE FROM usage_log")
    suspend fun clear()
}
