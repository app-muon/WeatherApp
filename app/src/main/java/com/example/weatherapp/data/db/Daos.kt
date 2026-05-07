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
    fun observeLocationsWithCache(): Flow<List<LocationWithForecastCache>>

    @Transaction
    @Query("SELECT * FROM locations ORDER BY displayOrder ASC")
    suspend fun getLocationsWithCache(): List<LocationWithForecastCache>

    @Transaction
    @Query("SELECT * FROM locations WHERE widgetOrder IS NOT NULL ORDER BY widgetOrder ASC LIMIT 2")
    suspend fun getWidgetLocationsWithCache(): List<LocationWithForecastCache>
}

@Dao
interface ForecastCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: ForecastCacheEntity)

    @Query("DELETE FROM forecast_cache WHERE locationId = :locationId")
    suspend fun deleteForLocation(locationId: Long)
}
