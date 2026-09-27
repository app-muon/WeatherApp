package com.example.weatherapp

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.weatherapp.data.db.WeatherDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), WeatherDatabase::class.java
    )

    @Test fun migrate5To7PreservesLocationsPreferencesAndCaches() {
        val name = "weather-migration-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO locations (id,name,country,countryCode,adminArea,latitude,longitude,timezone,displayOrder,widgetOrder) VALUES (1,'Madrid','Spain','ES',NULL,40.4,-3.7,'Europe/Madrid',0,0)")
            execSQL("INSERT INTO forecast_source_preferences VALUES (1,'aemet')")
            execSQL("INSERT INTO widget_source_preferences VALUES (1,'aemet')")
            execSQL("INSERT INTO forecast_cache VALUES (1,'aemet',1234,'raw','normalised')")
            execSQL("INSERT INTO provider_status VALUES (1,'aemet',1234,1234,NULL)")
            execSQL("INSERT INTO marine_cache VALUES (1,5678,'marine')")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, WeatherDatabase.MIGRATION_5_6, WeatherDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT name,revision,widgetOrder FROM locations").use {
                assertTrue(it.moveToFirst())
                assertEquals("Madrid", it.getString(0))
                assertEquals(0L, it.getLong(1))
                assertEquals(0, it.getInt(2))
            }
            for (table in listOf("forecast_source_preferences", "widget_source_preferences")) {
                db.query("SELECT selectedProviderId FROM " + table).use {
                    assertTrue(it.moveToFirst()); assertEquals("aemet", it.getString(0))
                }
            }
            db.query("SELECT normalisedJson FROM forecast_cache").use {
                assertTrue(it.moveToFirst()); assertEquals("normalised", it.getString(0))
            }
            db.query("SELECT lastAttemptAtEpochMillis,lastSuccessAtEpochMillis,lastError,unavailableAtEpochMillis FROM marine_status").use {
                assertTrue(it.moveToFirst()); assertEquals(5678L, it.getLong(0))
                assertEquals(5678L, it.getLong(1)); assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
            }
            db.query("SELECT rawJson FROM marine_cache").use {
                assertTrue(it.moveToFirst()); assertEquals("marine", it.getString(0))
            }
        }
    }

    @Test fun migrate6To7PreservesMarineFailuresAndLocationRevisions() {
        val name = "weather-marine-migration-test"
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO locations (id,name,latitude,longitude,displayOrder,revision) VALUES (1,'Coast',40.4,-3.7,0,4)")
            execSQL("INSERT INTO marine_cache VALUES (1,1234,'marine')")
            execSQL("INSERT INTO marine_status VALUES (1,5678,1234,'Offline')")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, WeatherDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT lastAttemptAtEpochMillis,lastSuccessAtEpochMillis,lastError,unavailableAtEpochMillis FROM marine_status").use {
                assertTrue(it.moveToFirst())
                assertEquals(5678L, it.getLong(0))
                assertEquals(1234L, it.getLong(1))
                assertEquals("Offline", it.getString(2))
                assertTrue(it.isNull(3))
            }
            db.query("SELECT revision FROM locations").use { assertTrue(it.moveToFirst()); assertEquals(4L, it.getLong(0)) }
            db.query("SELECT rawJson FROM marine_cache").use { assertTrue(it.moveToFirst()); assertEquals("marine", it.getString(0)) }
        }
    }
}
