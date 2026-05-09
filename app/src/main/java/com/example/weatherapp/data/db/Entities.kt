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
    val countryCode: String?,
    val adminArea: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String?,
    val displayOrder: Int,
    val widgetOrder: Int?
)

@Entity(
    tableName = "forecast_cache",
    primaryKeys = ["locationId", "providerId"],
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
    val providerId: String,
    val fetchedAtEpochMillis: Long,
    val rawJson: String,
    val normalisedJson: String
)

@Entity(
    tableName = "provider_status",
    primaryKeys = ["locationId", "providerId"],
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("locationId"), Index("providerId")]
)
data class ProviderStatusEntity(
    val locationId: Long,
    val providerId: String,
    val lastFetchedAtEpochMillis: Long?,
    val lastSuccessAtEpochMillis: Long?,
    val lastError: String?
)

@Entity(
    tableName = "widget_source_preferences",
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
data class WidgetSourcePreferenceEntity(
    val locationId: Long,
    val selectedProviderId: String
)

@Entity(
    tableName = "marine_cache",
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
data class MarineCacheEntity(
    val locationId: Long,
    val fetchedAtEpochMillis: Long,
    val rawJson: String
)

data class LocationWithForecastCache(
    @Embedded val location: LocationEntity,
    @Relation(parentColumn = "id", entityColumn = "locationId")
    val forecastCache: ForecastCacheEntity?
)

@Entity(
    tableName = "forecast_source_preferences",
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
data class ForecastSourcePreferenceEntity(
    val locationId: Long,
    val selectedProviderId: String
)

data class LocationWithCaches(
    @Embedded val location: LocationEntity,
    @Relation(entity = ForecastCacheEntity::class, parentColumn = "id", entityColumn = "locationId")
    val forecastCaches: List<ForecastCacheEntity>,
    @Relation(entity = MarineCacheEntity::class, parentColumn = "id", entityColumn = "locationId")
    val marineCache: MarineCacheEntity?,
    @Relation(entity = ProviderStatusEntity::class, parentColumn = "id", entityColumn = "locationId")
    val providerStatuses: List<ProviderStatusEntity>,
    @Relation(entity = WidgetSourcePreferenceEntity::class, parentColumn = "id", entityColumn = "locationId")
    val widgetSourcePreference: WidgetSourcePreferenceEntity?,
    @Relation(entity = ForecastSourcePreferenceEntity::class, parentColumn = "id", entityColumn = "locationId")
    val forecastSourcePreference: ForecastSourcePreferenceEntity?
)
