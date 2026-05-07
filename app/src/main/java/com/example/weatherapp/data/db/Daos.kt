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

    @Query("SELECT * FROM locations WHERE displayOrder = :displayOrder LIMIT 1")
    suspend fun getByDisplayOrder(displayOrder: Int): LocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(location: LocationEntity): Long

    @Transaction
    @Query("SELECT * FROM locations ORDER BY displayOrder ASC")
    fun observeLocationsWithCache(): Flow<List<LocationWithForecastCache>>

    @Transaction
    @Query("SELECT * FROM locations ORDER BY displayOrder ASC")
    suspend fun getLocationsWithCache(): List<LocationWithForecastCache>
}

@Dao
interface ForecastCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: ForecastCacheEntity)

    @Query("DELETE FROM forecast_cache WHERE locationId = :locationId")
    suspend fun deleteForLocation(locationId: Long)
}

