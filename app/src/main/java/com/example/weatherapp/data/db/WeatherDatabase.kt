package com.example.weatherapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocationEntity::class, ForecastCacheEntity::class, MarineCacheEntity::class],
    version = 3,
    exportSchema = true
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun forecastCacheDao(): ForecastCacheDao
    abstract fun marineCacheDao(): MarineCacheDao

    companion object {
        @Volatile private var instance: WeatherDatabase? = null

        fun getInstance(context: Context): WeatherDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WeatherDatabase::class.java,
                    "weather.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE locations ADD COLUMN widgetOrder INTEGER")
                db.execSQL("UPDATE locations SET widgetOrder = displayOrder WHERE displayOrder IN (0, 1)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `marine_cache` (
                        `locationId` INTEGER NOT NULL,
                        `fetchedAtEpochMillis` INTEGER NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        PRIMARY KEY(`locationId`),
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_marine_cache_locationId` ON `marine_cache` (`locationId`)")
            }
        }
    }
}
