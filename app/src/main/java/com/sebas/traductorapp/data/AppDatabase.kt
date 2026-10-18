package com.sebas.traductorapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SeriesEntity::class, PageCacheEntity::class, TextCacheEntity::class, UsageLogEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun series(): SeriesDao
    abstract fun pageCache(): PageCacheDao
    abstract fun textCache(): TextCacheDao
    abstract fun usage(): UsageDao

    companion object {
        /** 1→2: favoritas en la biblioteca. Las migraciones son explícitas: nunca se borra el glosario ni las cachés. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "traductor.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
