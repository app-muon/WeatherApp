package com.example.weatherapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations ORDER BY displayOrder ASC")
    fun observeLocations(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations ORDER BY displayOrder ASC")
    suspend fun getLocations(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE widgetOrder IS NOT NULL ORDER BY widgetOrder ASC LIMIT 2")
    suspend fun getWidgetLocations(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE displayOrder = :displayOrder LIMIT 1")
    suspend fun getByDisplayOrder(displayOrder: Int): LocationEntity?

    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LocationEntity?

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COALESCE(MAX(displayOrder), -1) FROM locations")
    suspend fun getMaxDisplayOrder(): Int

    @Query("SELECT COUNT(*) FROM locations WHERE widgetOrder IS NOT NULL")
    suspend fun countWidgetLocations(): Int

    @Query("UPDATE locations SET widgetOrder = NULL WHERE widgetOrder = :widgetOrder")
    suspend fun clearWidgetOrder(widgetOrder: Int)

    @Query("UPDATE locations SET widgetOrder = NULL WHERE id = :locationId")
    suspend fun clearWidgetOrderForLocation(locationId: Long)

    @Query("UPDATE locations SET widgetOrder = :widgetOrder WHERE id = :locationId")
    suspend fun setWidgetOrder(locationId: Long, widgetOrder: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(location: LocationEntity): Long

    @Transaction
    @Query("SELECT * FROM locations ORDER BY displayOrder ASC")
    fun observeLocationsWithCache(): Flow<List<LocationWithCaches>>

    @Transaction
    @Query("SELECT * FROM locations ORDER BY displayOrder ASC")
    suspend fun getLocationsWithCache(): List<LocationWithCaches>

    @Transaction
    @Query("SELECT * FROM locations WHERE widgetOrder IS NOT NULL ORDER BY widgetOrder ASC LIMIT 2")
    suspend fun getWidgetLocationsWithCache(): List<LocationWithCaches>
}

@Dao
interface ForecastCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: ForecastCacheEntity)

    @Query("DELETE FROM forecast_cache WHERE locationId = :locationId")
    suspend fun deleteForLocation(locationId: Long)

    @Query("SELECT * FROM forecast_cache WHERE locationId = :locationId AND providerId = :providerId LIMIT 1")
    suspend fun get(locationId: Long, providerId: String): ForecastCacheEntity?
}

@Dao
interface ProviderStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: ProviderStatusEntity)

    @Query("SELECT * FROM provider_status WHERE locationId = :locationId AND providerId = :providerId LIMIT 1")
    suspend fun get(locationId: Long, providerId: String): ProviderStatusEntity?
}

@Dao
interface WidgetSourcePreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: WidgetSourcePreferenceEntity)

    @Query("SELECT * FROM widget_source_preferences WHERE locationId = :locationId LIMIT 1")
    suspend fun get(locationId: Long): WidgetSourcePreferenceEntity?
}

@Dao
interface ForecastSourcePreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: ForecastSourcePreferenceEntity)

    @Query("SELECT * FROM forecast_source_preferences WHERE locationId = :locationId LIMIT 1")
    suspend fun get(locationId: Long): ForecastSourcePreferenceEntity?
}

@Dao
interface MarineCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: MarineCacheEntity)

    @Query("DELETE FROM marine_cache WHERE locationId = :locationId")
    suspend fun deleteForLocation(locationId: Long)
}
