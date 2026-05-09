package com.example.weatherapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocationEntity::class,
        ForecastCacheEntity::class,
        MarineCacheEntity::class,
        ProviderStatusEntity::class,
        WidgetSourcePreferenceEntity::class,
        ForecastSourcePreferenceEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun forecastCacheDao(): ForecastCacheDao
    abstract fun marineCacheDao(): MarineCacheDao
    abstract fun providerStatusDao(): ProviderStatusDao
    abstract fun widgetSourcePreferenceDao(): WidgetSourcePreferenceDao
    abstract fun forecastSourcePreferenceDao(): ForecastSourcePreferenceDao

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
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `forecast_source_preferences` (
                        `locationId` INTEGER NOT NULL,
                        `selectedProviderId` TEXT NOT NULL,
                        PRIMARY KEY(`locationId`),
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_forecast_source_preferences_locationId` ON `forecast_source_preferences` (`locationId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE locations ADD COLUMN countryCode TEXT")
                db.execSQL("ALTER TABLE forecast_cache RENAME TO forecast_cache_old")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `forecast_cache` (
                        `locationId` INTEGER NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `fetchedAtEpochMillis` INTEGER NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `normalisedJson` TEXT NOT NULL,
                        PRIMARY KEY(`locationId`, `providerId`),
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO forecast_cache(locationId, providerId, fetchedAtEpochMillis, rawJson, normalisedJson)
                    SELECT locationId, 'open_meteo', fetchedAtEpochMillis, rawJson, '' FROM forecast_cache_old
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE forecast_cache_old")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_forecast_cache_locationId` ON `forecast_cache` (`locationId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `provider_status` (
                        `locationId` INTEGER NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `lastFetchedAtEpochMillis` INTEGER,
                        `lastSuccessAtEpochMillis` INTEGER,
                        `lastError` TEXT,
                        PRIMARY KEY(`locationId`, `providerId`),
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_status_locationId` ON `provider_status` (`locationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_status_providerId` ON `provider_status` (`providerId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `widget_source_preferences` (
                        `locationId` INTEGER NOT NULL,
                        `selectedProviderId` TEXT NOT NULL,
                        PRIMARY KEY(`locationId`),
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_widget_source_preferences_locationId` ON `widget_source_preferences` (`locationId`)")
            }
        }
    }
}
