package com.example.data.places

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.RouteWaypointEntity
import com.example.data.db.SavedRouteEntity
import com.example.data.db.SavedRouteWithWaypoints
import com.example.data.db.UserPlaceEntity
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RoutePoint(
    val latitude: Double,
    val longitude: Double
) {
    fun isValid(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
}

data class RouteWaypointDraft(
    val id: String? = null,
    val placeId: String? = null,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val notes: String = ""
)

data class DecodedRouteGeometry(
    val points: List<RoutePoint>,
    val isMalformed: Boolean
)

data class SavedRouteModel(
    val route: SavedRouteEntity,
    val points: List<RoutePoint>,
    val waypoints: List<RouteWaypointEntity>,
    val hasMalformedGeometry: Boolean = false
)

sealed interface CreatePlaceOutcome {
    data class Created(val place: UserPlaceEntity) : CreatePlaceOutcome
    data class DuplicateNearby(val existing: UserPlaceEntity) : CreatePlaceOutcome
    data class ValidationError(val message: String) : CreatePlaceOutcome
}

/**
 * Codec and geometric calculator for user-owned route polylines.
 * Safely rejects corrupt/incomplete coordinate entries without crashing
 * and never fabricates road-network geometry.
 */
object RouteGeometryCodec {

    fun encode(points: List<RoutePoint>): String {
        val array = JSONArray()
        for (pt in points) {
            if (!pt.isValid()) continue
            val obj = JSONObject()
            obj.put("lat", pt.latitude)
            obj.put("lng", pt.longitude)
            array.put(obj)
        }
        return array.toString()
    }

    fun decode(geometryJson: String): DecodedRouteGeometry {
        val raw = geometryJson.trim()
        if (raw.isEmpty()) {
            return DecodedRouteGeometry(emptyList(), isMalformed = true)
        }
        return try {
            val array = JSONArray(raw)
            val parsed = mutableListOf<RoutePoint>()
            var skippedAny = false
            for (i in 0 until array.length()) {
                val elem = array.opt(i)
                val point: RoutePoint? = when (elem) {
                    is JSONObject -> {
                        if (elem.has("lat") && elem.has("lng")) {
                            val lat = elem.optDouble("lat", Double.NaN)
                            val lng = elem.optDouble("lng", Double.NaN)
                            RoutePoint(lat, lng).takeIf { it.isValid() }
                        } else {
                            null
                        }
                    }
                    is JSONArray -> {
                        if (elem.length() >= 2) {
                            val lat = elem.optDouble(0, Double.NaN)
                            val lng = elem.optDouble(1, Double.NaN)
                            RoutePoint(lat, lng).takeIf { it.isValid() }
                        } else {
                            null
                        }
                    }
                    else -> null
                }
                if (point != null) {
                    parsed.add(point)
                } else {
                    skippedAny = true
                }
            }
            DecodedRouteGeometry(
                points = parsed,
                isMalformed = skippedAny || parsed.size < 2
            )
        } catch (_: Throwable) {
            DecodedRouteGeometry(points = emptyList(), isMalformed = true)
        }
    }

    /**
     * Computes geometric polyline length in meters along the ordered coordinate sequence.
     * Note: This is pure spherical geometry length, NOT a turn-by-turn road routing distance.
     */
    fun computePolylineDistanceMeters(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        var totalMeters = 0.0
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            if (a.isValid() && b.isValid()) {
                totalMeters += haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            }
        }
        return totalMeters
    }

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val h = sin(dLat / 2.0).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLng / 2.0).pow(2)
        return 2.0 * earthRadiusMeters * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun formatDistanceMeters(meters: Double): String {
        if (!meters.isFinite() || meters <= 0.0) return "0 m"
        return if (meters >= 1000.0) {
            String.format(Locale.US, "%.2f km", meters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f m", meters)
        }
    }
}

/**
 * Shared local-first repository for user-owned Places, Placemarks, Saved Routes,
 * and ordered Waypoints stored in AppDatabase (map_app_master.db).
 *
 * Distinct from downloaded OSM POIs (`osm_pois`) and disposable tile caches;
 * all mutating and read queries run off the main thread.
 */
class PlacesAndRoutesRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val placeDao = db.userPlaceDao()
    private val routeDao = db.savedRouteDao()

    fun observePlacemarks(): Flow<List<UserPlaceEntity>> = placeDao.observePlacemarks()

    fun observeAllPlaces(): Flow<List<UserPlaceEntity>> = placeDao.observeAllPlaces()

    fun observeRoutes(): Flow<List<SavedRouteModel>> {
        return routeDao.observeRoutesWithWaypoints().map { rows ->
            rows.map { row -> row.toDomainModel() }
        }
    }

    suspend fun getPlacemarkById(id: String): UserPlaceEntity? = withContext(Dispatchers.IO) {
        placeDao.getPlaceById(id)
    }

    suspend fun getAllPlacemarksOnce(): List<UserPlaceEntity> = withContext(Dispatchers.IO) {
        placeDao.getAllPlacemarksOnce()
    }

    suspend fun findNearbyPlacemarks(
        latitude: Double,
        longitude: Double,
        thresholdDegrees: Double = 0.0001
    ): List<UserPlaceEntity> = withContext(Dispatchers.IO) {
        if (!RoutePoint(latitude, longitude).isValid()) return@withContext emptyList()
        val delta = thresholdDegrees.coerceAtLeast(0.0)
        placeDao.getNearbyPlaces(
            minLat = (latitude - delta).coerceAtLeast(-90.0),
            maxLat = (latitude + delta).coerceAtMost(90.0),
            minLng = (longitude - delta).coerceAtLeast(-180.0),
            maxLng = (longitude + delta).coerceAtMost(180.0)
        ).filter { it.isPlacemark }
    }

    suspend fun createPlacemark(
        name: String,
        latitude: Double,
        longitude: Double,
        description: String = "",
        addressLabel: String? = null,
        category: String = "placemark",
        tags: List<String> = emptyList(),
        linkedModuleRef: String? = null,
        allowDuplicateLocation: Boolean = false
    ): CreatePlaceOutcome = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return@withContext CreatePlaceOutcome.ValidationError("Placemark name cannot be empty.")
        }
        val coord = RoutePoint(latitude, longitude)
        if (!coord.isValid()) {
            return@withContext CreatePlaceOutcome.ValidationError("Invalid geographic coordinates.")
        }

        if (!allowDuplicateLocation) {
            val nearby = findNearbyPlacemarks(latitude, longitude, thresholdDegrees = 0.0001)
            val existing = nearby.firstOrNull()
            if (existing != null) {
                return@withContext CreatePlaceOutcome.DuplicateNearby(existing)
            }
        }

        val now = System.currentTimeMillis()
        val cleanCategory = category.trim().ifEmpty { "placemark" }
        val normalizedTags = tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val entity = UserPlaceEntity(
            id = "place_${UUID.randomUUID()}",
            name = cleanName,
            latitude = latitude,
            longitude = longitude,
            description = description.trim(),
            addressLabel = addressLabel?.trim()?.takeIf { it.isNotEmpty() },
            category = cleanCategory,
            tagsCsv = normalizedTags,
            isPlacemark = true,
            linkedModuleRef = linkedModuleRef,
            createdAt = now,
            updatedAt = now
        )
        placeDao.upsertPlace(entity)
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "PlacesRepository",
            description = "Created placemark '${entity.name}' (${entity.id}) at ${entity.latitude}, ${entity.longitude}"
        )
        CreatePlaceOutcome.Created(entity)
    }

    suspend fun updatePlacemark(
        placeId: String,
        name: String,
        description: String = "",
        addressLabel: String? = null,
        category: String = "placemark",
        tags: List<String> = emptyList(),
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<UserPlaceEntity> = withContext(Dispatchers.IO) {
        val existing = placeDao.getPlaceById(placeId)
            ?: return@withContext Result.failure(IllegalArgumentException("Placemark not found."))
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Placemark name cannot be empty."))
        }
        val nextLat = latitude ?: existing.latitude
        val nextLng = longitude ?: existing.longitude
        if (!RoutePoint(nextLat, nextLng).isValid()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid geographic coordinates."))
        }

        val normalizedTags = tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val updated = existing.copy(
            name = cleanName,
            latitude = nextLat,
            longitude = nextLng,
            description = description.trim(),
            addressLabel = addressLabel?.trim()?.takeIf { it.isNotEmpty() },
            category = category.trim().ifEmpty { "placemark" },
            tagsCsv = normalizedTags,
            updatedAt = System.currentTimeMillis()
        )
        placeDao.upsertPlace(updated)
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "PlacesRepository",
            description = "Updated placemark '${updated.name}' (${updated.id})"
        )
        Result.success(updated)
    }

    suspend fun deletePlacemark(placeId: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = placeDao.deletePlaceById(placeId) > 0
        if (deleted) {
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "PlacesRepository",
                description = "Deleted placemark $placeId"
            )
        }
        deleted
    }

    suspend fun getRouteById(routeId: String): SavedRouteModel? = withContext(Dispatchers.IO) {
        routeDao.getRouteWithWaypoints(routeId)?.toDomainModel()
    }

    suspend fun saveRoute(
        routeId: String? = null,
        name: String,
        description: String = "",
        points: List<RoutePoint>,
        waypoints: List<RouteWaypointDraft> = emptyList()
    ): Result<SavedRouteModel> = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Route name cannot be empty."))
        }
        val validPoints = points.filter { it.isValid() }
        if (validPoints.size < 2) {
            return@withContext Result.failure(
                IllegalArgumentException("A saved route requires at least 2 valid geographic points.")
            )
        }

        val now = System.currentTimeMillis()
        val existing = routeId?.let { routeDao.getRouteWithWaypoints(it)?.route }
        val finalRouteId = existing?.id ?: routeId ?: "route_${UUID.randomUUID()}"
        val encodedGeometry = RouteGeometryCodec.encode(validPoints)
        val distanceMeters = RouteGeometryCodec.computePolylineDistanceMeters(validPoints)

        val routeEntity = SavedRouteEntity(
            id = finalRouteId,
            name = cleanName,
            description = description.trim(),
            geometryJson = encodedGeometry,
            pointCount = validPoints.size,
            totalDistanceMeters = distanceMeters,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        val validWaypoints = waypoints
            .filter { RoutePoint(it.latitude, it.longitude).isValid() }
            .mapIndexed { index, wp ->
                RouteWaypointEntity(
                    id = wp.id?.takeIf { it.isNotBlank() } ?: "wp_${UUID.randomUUID()}",
                    routeId = finalRouteId,
                    placeId = wp.placeId,
                    orderIndex = index,
                    name = wp.name.trim().ifEmpty { "Waypoint ${index + 1}" },
                    latitude = wp.latitude,
                    longitude = wp.longitude,
                    notes = wp.notes.trim(),
                    createdAt = now
                )
            }

        routeDao.saveRouteWithWaypoints(routeEntity, validWaypoints)
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "PlacesRepository",
            description = "Saved route '${routeEntity.name}' ($finalRouteId) with ${validPoints.size} pts, ${validWaypoints.size} waypoints"
        )

        Result.success(
            SavedRouteModel(
                route = routeEntity,
                points = validPoints,
                waypoints = validWaypoints,
                hasMalformedGeometry = false
            )
        )
    }

    suspend fun deleteRoute(routeId: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = routeDao.deleteRouteById(routeId) > 0
        if (deleted) {
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "PlacesRepository",
                description = "Deleted saved route $routeId"
            )
        }
        deleted
    }

    private fun SavedRouteWithWaypoints.toDomainModel(): SavedRouteModel {
        val decoded = RouteGeometryCodec.decode(route.geometryJson)
        val orderedWaypoints = waypoints.sortedBy { it.orderIndex }
        return SavedRouteModel(
            route = route,
            points = decoded.points,
            waypoints = orderedWaypoints,
            hasMalformedGeometry = decoded.isMalformed
        )
    }
}
