package com.example.data.area

import com.example.data.db.OsmPoiRecord
import com.example.data.db.SavedAreaEntity
import com.example.data.db.UserPlaceEntity
import com.example.data.places.RouteGeometryCodec
import com.example.data.places.SavedRouteModel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

enum class AreaDataProvenance(val label: String) {
    LOCAL_ONLY("Local OSM Only"),
    ONLINE_LIVE("Live Online"),
    HYBRID_ONLINE_AND_LOCAL("Online + Local OSM"),
    SAVED_SNAPSHOT("Saved Offline Snapshot")
}

data class AreaNearbyItem(
    val id: String,
    val name: String,
    val category: String,
    val distanceMeters: Double,
    val latitude: Double,
    val longitude: Double,
    val sourceLabel: String,
    val selectedForSave: Boolean = true
)

data class AreaLocationIdentity(
    val primaryName: String,
    val addressLabel: String = "",
    val locality: String = "",
    val administrativeRegion: String = "",
    val country: String = "",
    val featureName: String = "",
    val category: String = "area",
    val attribution: String = "© OpenStreetMap contributors"
)

data class AreaWikiInfo(
    val title: String,
    val summary: String,
    val url: String,
    val wikidataId: String? = null,
    val distanceMeters: Double? = null,
    val isReliableMatch: Boolean = true,
    val attribution: String = "© Wikipedia contributors (CC BY-SA 4.0)"
)

data class AreaDetailsResult(
    val latitude: Double,
    val longitude: Double,
    val savedAreaId: String? = null,
    val isSavedSnapshot: Boolean = false,
    val savedAt: Long? = null,
    val retrievedAt: Long = System.currentTimeMillis(),
    val provenance: AreaDataProvenance = AreaDataProvenance.LOCAL_ONLY,
    val radiusUsedMeters: Int = 250,
    val locationIdentity: AreaLocationIdentity? = null,
    val nearbyPlaces: List<AreaNearbyItem> = emptyList(),
    val geographicDetails: List<String> = emptyList(),
    val wikiInfo: AreaWikiInfo? = null,
    val wikiStatusNote: String? = null,
    val isLoadingOnline: Boolean = false,
    val isOffline: Boolean = false,
    val partialWarning: String? = null,
    val errorMessage: String? = null,
    val activeReverseProviderId: String = ReverseGeocodingProviderConfig.PUBLIC_NOMINATIM_ID,
    val reverseGeocoderNote: String? = null,
    val sourceAttribution: String = "Local OSM Database",
    val userNotes: String = ""
)

data class ContextCoordinateMatch(
    val savedArea: SavedAreaEntity? = null,
    val placemark: UserPlaceEntity? = null,
    val route: SavedRouteModel? = null,
    val nearbyOsmPoi: OsmPoiRecord? = null
) {
    val hasAnySavedRecord: Boolean
        get() = savedArea != null || placemark != null || route != null
}

/**
 * Tolerant JSON codec for retained nearby places inside [SavedAreaEntity].
 * Safely skips corrupt/incomplete items without throwing.
 */
object SavedAreaNearbyCodec {
    fun encode(items: List<AreaNearbyItem>): String {
        val array = JSONArray()
        for (item in items) {
            if (item.name.isBlank()) continue
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("category", item.category)
            obj.put("distanceMeters", item.distanceMeters)
            obj.put("lat", item.latitude)
            obj.put("lng", item.longitude)
            obj.put("sourceLabel", item.sourceLabel)
            array.put(obj)
        }
        return array.toString()
    }

    fun decode(json: String): List<AreaNearbyItem> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        return try {
            val array = JSONArray(trimmed)
            val result = mutableListOf<AreaNearbyItem>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val lat = obj.optDouble("lat", Double.NaN)
                val lng = obj.optDouble("lng", Double.NaN)
                if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
                val dist = obj.optDouble("distanceMeters", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                result.add(
                    AreaNearbyItem(
                        id = obj.optString("id", "nearby_$i"),
                        name = name,
                        category = obj.optString("category", "place").ifBlank { "place" },
                        distanceMeters = dist,
                        latitude = lat,
                        longitude = lng,
                        sourceLabel = obj.optString("sourceLabel", "Saved Snapshot").ifBlank { "Saved Snapshot" },
                        selectedForSave = true
                    )
                )
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun formatMeters(meters: Double): String = RouteGeometryCodec.formatDistanceMeters(meters)

    fun boundingBoxForRadius(lat: Double, lng: Double, radiusMeters: Double): BoundingBox {
        val degLat = radiusMeters / 111_320.0
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.15)
        val degLng = radiusMeters / (111_320.0 * cosLat)
        return BoundingBox(
            minLat = (lat - degLat).coerceAtLeast(-90.0),
            maxLat = (lat + degLat).coerceAtMost(90.0),
            minLng = (lng - degLng).coerceAtLeast(-180.0),
            maxLng = (lng + degLng).coerceAtMost(180.0)
        )
    }

    data class BoundingBox(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    )
}
