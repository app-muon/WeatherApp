package com.example.weatherapp.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val country: String?,
    val adminArea: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String?,
    val displayOrder: Int
)

@Entity(
    tableName = "forecast_cache",
    primaryKeys = ["locationId"],
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("locationId")]
)
data class ForecastCacheEntity(
    val locationId: Long,
    val fetchedAtEpochMillis: Long,
    val rawJson: String
)

data class LocationWithForecastCache(
    @Embedded val location: LocationEntity,
    @Relation(parentColumn = "id", entityColumn = "locationId")
    val forecastCache: ForecastCacheEntity?
)

