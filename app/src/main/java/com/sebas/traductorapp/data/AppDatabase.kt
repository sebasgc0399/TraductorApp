package com.sebas.traductorapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SeriesEntity::class, PageCacheEntity::class, TextCacheEntity::class, UsageLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun series(): SeriesDao
    abstract fun pageCache(): PageCacheDao
    abstract fun textCache(): TextCacheDao
    abstract fun usage(): UsageDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "traductor.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
