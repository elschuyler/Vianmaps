package com.example.data.area

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.OsmBuildingRecord
import com.example.data.db.OsmPoiRecord
import com.example.data.db.OsmRegionRecord
import com.example.data.db.OsmRoadRecord
import com.example.data.db.SavedAreaEntity
import com.example.data.places.RouteGeometryCodec
import com.example.data.places.RoutePoint
import com.example.data.places.SavedRouteModel
import com.example.data.tile.TileCoord
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-first, online-capable Area Details & Persistent Saved Areas Repository.
 *
 * - Immediately queries locally retained Room OSM tables (`osm_pois`, `osm_buildings`,
 *   `osm_roads`, `osm_regions`) and saved user records (`saved_areas`, `user_places`, `saved_routes`)
 *   with zero network dependency and zero GPS permissions.
 * - Uses adaptive local search radius: 250 m in dense areas, expanding to 1 km in sparse areas.
 * - Enriches asynchronously with policy-compliant Nominatim + Wikipedia lookups when online,
 *   caching results safely and deduplicating concurrent requests for the same location.
 */
class AreaDetailsRepository(
    context: Context,
    private val remoteGateway: AreaRemoteLookupGateway = HttpAreaRemoteLookupGateway(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val db = AppDatabase.getInstance(context)
    private val savedAreaDao = db.savedAreaDao()
    private val poiDao = db.osmPoiDao()
    private val buildingDao = db.osmBuildingDao()
    private val roadDao = db.osmRoadDao()
    private val regionDao = db.osmRegionDao()
    private val placeDao = db.userPlaceDao()
    private val routeDao = db.savedRouteDao()

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class CachedRemoteEntry(
        val result: RemoteAreaEnrichmentResult,
        val isNoResult: Boolean,
        val cachedAtMs: Long
    )

    private val remoteCache = object : LinkedHashMap<String, CachedRemoteEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRemoteEntry>?): Boolean {
            return size > 50
        }
    }
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<RemoteAreaEnrichmentResult>>()

    companion object {
        const val DENSE_RADIUS_METERS = 250
        const val SPARSE_RADIUS_METERS = 1000
        private const val DENSE_THRESHOLD_COUNT = 3
        private const val MAX_NEARBY_RESULTS = 12
        const val SUCCESS_CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes for successful enrichments
        const val NO_RESULT_CACHE_TTL_MS = 2 * 60 * 1000L // 2 minutes for verified empty/no-result responses
        private const val NEARBY_SAVED_AREA_MATCH_METERS = 75.0
        private const val NEARBY_PLACEMARK_MATCH_METERS = 40.0
        private const val NEARBY_ROUTE_MATCH_METERS = 45.0
    }

    fun observeSavedAreas(): Flow<List<SavedAreaEntity>> = savedAreaDao.observeSavedAreas()

    suspend fun getSavedAreaById(id: String): SavedAreaEntity? = withContext(Dispatchers.IO) {
        savedAreaDao.getSavedAreaById(id)
    }

    fun clearRemoteCache() {
        synchronized(remoteCache) {
            remoteCache.clear()
        }
        inFlightRequests.clear()
    }

    /**
     * Immediately gathers local Room data around `(latitude, longitude)` with adaptive radius
     * (250 m if dense, expanding to 1 km if fewer than 3 OSM features exist at 250 m).
     */
    suspend fun queryLocalSnapshot(latitude: Double, longitude: Double): AreaDetailsResult = withContext(Dispatchers.IO) {
        if (!RoutePoint(latitude, longitude).isValid()) {
            return@withContext AreaDetailsResult(
                latitude = latitude,
                longitude = longitude,
                errorMessage = "Invalid coordinate selected."
            )
        }

        val matchingSavedArea = findNearbySavedArea(latitude, longitude, NEARBY_SAVED_AREA_MATCH_METERS)
        val localOsm = queryLocalOsmFeatures(latitude, longitude)

        if (matchingSavedArea != null) {
            val savedNearby = SavedAreaNearbyCodec.decode(matchingSavedArea.nearbySummaryJson)
            val mergedNearby = mergeNearbyItems(localOsm.nearbyItems, savedNearby)
            val wiki = if (!matchingSavedArea.wikiTitle.isNullOrBlank() && !matchingSavedArea.wikiSummary.isNullOrBlank()) {
                AreaWikiInfo(
                    title = matchingSavedArea.wikiTitle,
                    summary = matchingSavedArea.wikiSummary,
                    url = matchingSavedArea.wikiUrl.orEmpty(),
                    wikidataId = matchingSavedArea.wikidataId,
                    isReliableMatch = true
                )
            } else {
                null
            }
            val detailsList = buildList {
                if (matchingSavedArea.geographicDetails.isNotBlank()) {
                    addAll(matchingSavedArea.geographicDetails.lines().map { it.trim() }.filter { it.isNotEmpty() })
                }
                addAll(localOsm.geographicDetails)
            }.distinct()

            return@withContext AreaDetailsResult(
                latitude = latitude,
                longitude = longitude,
                savedAreaId = matchingSavedArea.id,
                isSavedSnapshot = true,
                savedAt = matchingSavedArea.updatedAt,
                retrievedAt = matchingSavedArea.retrievedAt,
                provenance = AreaDataProvenance.SAVED_SNAPSHOT,
                radiusUsedMeters = localOsm.radiusUsedMeters,
                locationIdentity = AreaLocationIdentity(
                    primaryName = matchingSavedArea.name,
                    addressLabel = matchingSavedArea.addressLabel,
                    locality = matchingSavedArea.locality,
                    administrativeRegion = matchingSavedArea.region,
                    country = matchingSavedArea.country,
                    featureName = matchingSavedArea.featureName,
                    category = matchingSavedArea.category,
                    attribution = matchingSavedArea.sourceAttribution.ifBlank { "Saved Offline Snapshot" }
                ),
                nearbyPlaces = mergedNearby,
                geographicDetails = detailsList,
                wikiInfo = wiki,
                wikiStatusNote = if (wiki == null) "No Wikipedia summary saved in this snapshot." else null,
                sourceAttribution = matchingSavedArea.sourceAttribution.ifBlank { "Saved Offline Snapshot" },
                userNotes = matchingSavedArea.userNotes
            )
        }

        val primaryLocalName = localOsm.primaryFeatureName
            ?: localOsm.regionNames.firstOrNull()
            ?: localOsm.roadNames.firstOrNull()
            ?: "Selected Location"

        val localityText = localOsm.regionNames.joinToString(", ")

        val identity = if (
            localOsm.primaryFeatureName != null ||
            localOsm.regionNames.isNotEmpty() ||
            localOsm.roadNames.isNotEmpty()
        ) {
            AreaLocationIdentity(
                primaryName = primaryLocalName,
                addressLabel = listOfNotNull(
                    localOsm.roadNames.firstOrNull()?.let { "Near $it" },
                    localityText.takeIf { it.isNotBlank() }
                ).joinToString(" • "),
                locality = localityText,
                administrativeRegion = localOsm.regionNames.drop(1).firstOrNull().orEmpty(),
                country = "",
                featureName = localOsm.primaryFeatureName.orEmpty(),
                category = localOsm.primaryCategory ?: "area",
                attribution = "Local Retained OSM Data"
            )
        } else {
            null
        }

        AreaDetailsResult(
            latitude = latitude,
            longitude = longitude,
            savedAreaId = null,
            isSavedSnapshot = false,
            provenance = AreaDataProvenance.LOCAL_ONLY,
            radiusUsedMeters = localOsm.radiusUsedMeters,
            locationIdentity = identity,
            nearbyPlaces = localOsm.nearbyItems,
            geographicDetails = localOsm.geographicDetails,
            wikiInfo = null,
            wikiStatusNote = null,
            sourceAttribution = if (localOsm.nearbyItems.isNotEmpty() || localOsm.geographicDetails.isNotEmpty()) {
                "Local Retained OSM Data"
            } else {
                "Coordinates Only (No local OSM features in this area)"
            }
        )
    }

    /**
     * Enriches an area result asynchronously from online sources (Nominatim + Wikipedia),
     * deduplicating simultaneous calls for the same coordinate bucket and reusing TTL cache.
     */
    suspend fun enrichWithOnlineData(
        localBaseline: AreaDetailsResult,
        isOnlineAllowed: Boolean,
        forceRefresh: Boolean = false
    ): AreaDetailsResult {
        val lat = localBaseline.latitude
        val lng = localBaseline.longitude
        if (!RoutePoint(lat, lng).isValid()) {
            return localBaseline.copy(isLoadingOnline = false, errorMessage = "Invalid geographic coordinates.")
        }
        if (!isOnlineAllowed) {
            return localBaseline.copy(
                isLoadingOnline = false,
                isOffline = true,
                wikiStatusNote = localBaseline.wikiInfo?.let { null }
                    ?: "Offline mode: only locally stored data is shown."
            )
        }

        val providerKey = remoteGateway.activeProviderCacheKey()
        val cacheKey = buildCacheKey(providerKey, lat, lng, localBaseline.radiusUsedMeters)
        val now = clockMs()
        if (!forceRefresh) {
            val cached = synchronized(remoteCache) {
                val entry = remoteCache[cacheKey]
                if (entry != null) {
                    val ttlMs = if (entry.isNoResult) NO_RESULT_CACHE_TTL_MS else SUCCESS_CACHE_TTL_MS
                    if ((now - entry.cachedAtMs) <= ttlMs) {
                        entry
                    } else {
                        remoteCache.remove(cacheKey)
                        null
                    }
                } else {
                    null
                }
            }
            if (cached != null) {
                return mergeLocalAndRemote(localBaseline, cached.result, isRefresh = false)
            }
        }

        val localHints = localBaseline.nearbyPlaces.take(3).map { it.name }

        val existingDeferred = if (!forceRefresh) inFlightRequests[cacheKey] else null
        val deferred: Deferred<RemoteAreaEnrichmentResult>
        var isCreator = false

        if (existingDeferred != null && existingDeferred.isActive) {
            deferred = existingDeferred
        } else {
            val newDeferred = cacheScope.async {
                remoteGateway.lookupAreaOnline(
                    latitude = lat,
                    longitude = lng,
                    radiusMeters = localBaseline.radiusUsedMeters,
                    localHintNames = localHints
                )
            }
            val prev = inFlightRequests.putIfAbsent(cacheKey, newDeferred)
            if (prev != null && prev.isActive && !forceRefresh) {
                newDeferred.cancel()
                deferred = prev
            } else {
                if (forceRefresh) {
                    inFlightRequests[cacheKey] = newDeferred
                }
                deferred = newDeferred
                isCreator = true
            }
        }

        return try {
            val remote = deferred.await()
            val hasCacheableSuccess = !remote.hasTransientError &&
                remote.fatalError == null &&
                (remote.identity != null || remote.wikiInfo != null || remote.nearbyWikiPlaces.isNotEmpty())
            val hasCacheableNoResult = !remote.hasTransientError &&
                remote.fatalError == null &&
                remote.isValidNoResult &&
                !hasCacheableSuccess

            if (hasCacheableSuccess || hasCacheableNoResult) {
                synchronized(remoteCache) {
                    remoteCache[cacheKey] = CachedRemoteEntry(
                        result = remote,
                        isNoResult = hasCacheableNoResult,
                        cachedAtMs = clockMs()
                    )
                }
            }
            mergeLocalAndRemote(localBaseline, remote, isRefresh = forceRefresh)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            localBaseline.copy(
                isLoadingOnline = false,
                errorMessage = "Online lookup failed: ${e.message ?: "Unknown network error"}"
            )
        } finally {
            if (isCreator) {
                inFlightRequests.remove(cacheKey, deferred)
            }
        }
    }

    suspend fun saveAreaSnapshot(
        result: AreaDetailsResult,
        customName: String? = null,
        selectedNearbyIds: Set<String>? = null,
        userNotes: String = ""
    ): Result<SavedAreaEntity> = withContext(Dispatchers.IO) {
        if (!RoutePoint(result.latitude, result.longitude).isValid()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid geographic coordinates."))
        }

        val existing = result.savedAreaId?.let { savedAreaDao.getSavedAreaById(it) }
            ?: findNearbySavedArea(result.latitude, result.longitude, NEARBY_SAVED_AREA_MATCH_METERS)

        val resolvedName = customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: result.locationIdentity?.primaryName?.takeIf { it.isNotBlank() }
            ?: existing?.name
            ?: String.format(Locale.US, "Area %.4f, %.4f", result.latitude, result.longitude)

        val retainedNearby = if (selectedNearbyIds != null) {
            result.nearbyPlaces.filter { selectedNearbyIds.contains(it.id) }
        } else {
            result.nearbyPlaces.filter { it.selectedForSave }
        }

        val now = System.currentTimeMillis()
        val provenanceStr = when (result.provenance) {
            AreaDataProvenance.SAVED_SNAPSHOT -> existing?.dataProvenance ?: AreaDataProvenance.LOCAL_ONLY.name
            else -> result.provenance.name
        }

        val entity = SavedAreaEntity(
            id = existing?.id ?: "area_${UUID.randomUUID()}",
            name = resolvedName,
            latitude = result.latitude,
            longitude = result.longitude,
            addressLabel = result.locationIdentity?.addressLabel ?: existing?.addressLabel.orEmpty(),
            locality = result.locationIdentity?.locality ?: existing?.locality.orEmpty(),
            region = result.locationIdentity?.administrativeRegion ?: existing?.region.orEmpty(),
            country = result.locationIdentity?.country ?: existing?.country.orEmpty(),
            featureName = result.locationIdentity?.featureName ?: existing?.featureName.orEmpty(),
            category = result.locationIdentity?.category ?: existing?.category ?: "area",
            geographicDetails = result.geographicDetails.joinToString("\n"),
            nearbySummaryJson = SavedAreaNearbyCodec.encode(retainedNearby),
            retainedNearbyCount = retainedNearby.size,
            wikiTitle = result.wikiInfo?.title ?: existing?.wikiTitle,
            wikiSummary = result.wikiInfo?.summary ?: existing?.wikiSummary,
            wikiUrl = result.wikiInfo?.url ?: existing?.wikiUrl,
            wikidataId = result.wikiInfo?.wikidataId ?: existing?.wikidataId,
            sourceAttribution = result.sourceAttribution.ifBlank { existing?.sourceAttribution ?: "Local OSM Database" },
            dataProvenance = provenanceStr,
            userNotes = userNotes.trim(),
            tagsCsv = existing?.tagsCsv.orEmpty(),
            retrievedAt = result.retrievedAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        savedAreaDao.upsertSavedArea(entity)
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "AreaDetailsRepository",
            description = "Saved offline area snapshot '${entity.name}' (${entity.id}) with ${retainedNearby.size} nearby items"
        )
        Result.success(entity)
    }

    suspend fun updateSavedAreaMetadata(
        areaId: String,
        name: String,
        category: String,
        addressLabel: String,
        userNotes: String,
        tagsText: String
    ): Result<SavedAreaEntity> = withContext(Dispatchers.IO) {
        val existing = savedAreaDao.getSavedAreaById(areaId)
            ?: return@withContext Result.failure(IllegalArgumentException("Saved area record not found."))
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Area name cannot be empty."))
        }
        val normalizedTags = tagsText.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val now = clockMs()
        val updated = existing.copy(
            name = cleanName,
            category = category.trim().ifEmpty { "area" },
            addressLabel = addressLabel.trim(),
            userNotes = userNotes.trim(),
            tagsCsv = normalizedTags,
            // Preserve existing.retrievedAt so data retrieval time stays distinct from user edit time.
            retrievedAt = existing.retrievedAt,
            updatedAt = now
        )
        savedAreaDao.upsertSavedArea(updated)
        Result.success(updated)
    }

    suspend fun refreshSavedAreaOnline(
        areaId: String,
        isOnlineAllowed: Boolean
    ): Result<SavedAreaEntity> = withContext(Dispatchers.IO) {
        val existing = savedAreaDao.getSavedAreaById(areaId)
            ?: return@withContext Result.failure(IllegalArgumentException("Saved area not found."))
        if (!isOnlineAllowed) {
            return@withContext Result.failure(IllegalStateException("Internet is disabled or offline. Existing saved snapshot preserved."))
        }

        // Gather pure local OSM baseline without conflating saved snapshot identity as fresh remote data
        val localOsm = queryLocalOsmFeatures(existing.latitude, existing.longitude)
        val localHints = localOsm.nearbyItems.take(3).map { it.name }
        val remote = try {
            remoteGateway.lookupAreaOnline(
                latitude = existing.latitude,
                longitude = existing.longitude,
                radiusMeters = localOsm.radiusUsedMeters,
                localHintNames = localHints
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            return@withContext Result.failure(
                IllegalStateException("Refresh failed (${e.message ?: "Network error"}); existing snapshot preserved.")
            )
        }

        val hasFreshRemoteData = remote.identity != null || remote.wikiInfo != null || remote.nearbyWikiPlaces.isNotEmpty()
        if (remote.fatalError != null || !hasFreshRemoteData) {
            val reason = remote.fatalError
                ?: remote.partialErrors.firstOrNull()
                ?: remote.reverseGeocoderStatusNote
                ?: remote.wikiStatusNote
                ?: "No fresh online data returned"
            return@withContext Result.failure(IllegalStateException("$reason. Existing saved snapshot preserved."))
        }
        if (remote.hasTransientError && remote.identity == null && remote.wikiInfo == null) {
            return@withContext Result.failure(
                IllegalStateException("${remote.partialErrors.firstOrNull() ?: "Online refresh error"}. Existing saved snapshot preserved.")
            )
        }

        val freshBaseline = AreaDetailsResult(
            latitude = existing.latitude,
            longitude = existing.longitude,
            savedAreaId = existing.id,
            isSavedSnapshot = false,
            retrievedAt = clockMs(),
            provenance = AreaDataProvenance.LOCAL_ONLY,
            radiusUsedMeters = localOsm.radiusUsedMeters,
            nearbyPlaces = localOsm.nearbyItems,
            geographicDetails = localOsm.geographicDetails
        )
        val refreshed = mergeLocalAndRemote(freshBaseline, remote, isRefresh = true)

        val now = clockMs()
        val resolvedName = remote.identity?.primaryName?.takeIf { it.isNotBlank() && it != "Selected Location" }
            ?: existing.name
        val mergedGeoDetails = (refreshed.geographicDetails +
            existing.geographicDetails.lines().map { it.trim() }.filter { it.isNotEmpty() })
            .distinct()
            .joinToString("\n")
        val existingNearby = SavedAreaNearbyCodec.decode(existing.nearbySummaryJson)
        val mergedNearby = mergeNearbyItems(
            refreshed.nearbyPlaces.filter { existingNearby.isEmpty() || existingNearby.any { ex -> ex.id == it.id } || it.sourceLabel == "Online Wikipedia" },
            existingNearby
        ).ifEmpty { existingNearby }

        val updated = existing.copy(
            name = resolvedName,
            addressLabel = remote.identity?.addressLabel?.takeIf { it.isNotBlank() } ?: existing.addressLabel,
            locality = remote.identity?.locality?.takeIf { it.isNotBlank() } ?: existing.locality,
            region = remote.identity?.administrativeRegion?.takeIf { it.isNotBlank() } ?: existing.region,
            country = remote.identity?.country?.takeIf { it.isNotBlank() } ?: existing.country,
            featureName = remote.identity?.featureName?.takeIf { it.isNotBlank() } ?: existing.featureName,
            category = remote.identity?.category?.takeIf { it.isNotBlank() } ?: existing.category,
            geographicDetails = mergedGeoDetails,
            nearbySummaryJson = SavedAreaNearbyCodec.encode(mergedNearby),
            retainedNearbyCount = mergedNearby.size,
            wikiTitle = remote.wikiInfo?.title ?: existing.wikiTitle,
            wikiSummary = remote.wikiInfo?.summary ?: existing.wikiSummary,
            wikiUrl = remote.wikiInfo?.url ?: existing.wikiUrl,
            wikidataId = remote.wikiInfo?.wikidataId ?: existing.wikidataId,
            sourceAttribution = refreshed.sourceAttribution.ifBlank { existing.sourceAttribution },
            dataProvenance = refreshed.provenance.name,
            userNotes = existing.userNotes,
            tagsCsv = existing.tagsCsv,
            retrievedAt = now,
            updatedAt = now
        )
        savedAreaDao.upsertSavedArea(updated)
        Result.success(updated)
    }

    suspend fun deleteSavedArea(areaId: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = savedAreaDao.deleteSavedAreaById(areaId) > 0
        if (deleted) {
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "AreaDetailsRepository",
                description = "Deleted saved area $areaId"
            )
        }
        deleted
    }

    suspend fun findNearbySavedArea(
        latitude: Double,
        longitude: Double,
        maxDistanceMeters: Double = NEARBY_SAVED_AREA_MATCH_METERS
    ): SavedAreaEntity? = withContext(Dispatchers.IO) {
        val bbox = SavedAreaNearbyCodec.boundingBoxForRadius(latitude, longitude, maxDistanceMeters)
        savedAreaDao.getNearbySavedAreas(bbox.minLat, bbox.maxLat, bbox.minLng, bbox.maxLng)
            .map { it to RouteGeometryCodec.haversineMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, dist) -> dist <= maxDistanceMeters }
            .minByOrNull { (_, dist) -> dist }
            ?.first
    }

    /**
     * Context-sensitive inspection for Long-Press Map Context Menu.
     * Detects matching Saved Area, Saved Placemark, Saved Route, or nearby Local OSM POI.
     */
    suspend fun findContextMatchesAt(latitude: Double, longitude: Double): ContextCoordinateMatch = withContext(Dispatchers.IO) {
        if (!RoutePoint(latitude, longitude).isValid()) return@withContext ContextCoordinateMatch()

        val savedArea = findNearbySavedArea(latitude, longitude, NEARBY_SAVED_AREA_MATCH_METERS)

        val placeBox = SavedAreaNearbyCodec.boundingBoxForRadius(latitude, longitude, NEARBY_PLACEMARK_MATCH_METERS)
        val nearbyPlacemark = placeDao.getNearbyPlaces(placeBox.minLat, placeBox.maxLat, placeBox.minLng, placeBox.maxLng)
            .map { it to RouteGeometryCodec.haversineMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, dist) -> dist <= NEARBY_PLACEMARK_MATCH_METERS }
            .minByOrNull { (_, dist) -> dist }
            ?.first

        // Find nearest saved route with any point/waypoint close to coordinate
        val routeWithWp = routeDao.getRouteCount()
        val nearbyRoute: SavedRouteModel? = if (routeWithWp > 0) {
            var bestModel: SavedRouteModel? = null
            var bestDist = NEARBY_ROUTE_MATCH_METERS
            // Bounded direct check via SavedRouteDao isn't Flow-based; add simple query or check top routes
            // Use observeRoutes via SavedRouteDao list or check waypoints/points
            val existingModels = getSavedRouteModelsSnapshot()
            for (model in existingModels) {
                val minPtDist = model.points.minOfOrNull {
                    RouteGeometryCodec.haversineMeters(latitude, longitude, it.latitude, it.longitude)
                } ?: Double.MAX_VALUE
                if (minPtDist <= bestDist) {
                    bestDist = minPtDist
                    bestModel = model
                }
            }
            bestModel
        } else {
            null
        }

        val poiBox = SavedAreaNearbyCodec.boundingBoxForRadius(latitude, longitude, 60.0)
        val nearbyPoi = poiDao.getPoisInBoundingBox(poiBox.minLat, poiBox.maxLat, poiBox.minLng, poiBox.maxLng, limit = 25)
            .map { it to RouteGeometryCodec.haversineMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, dist) -> dist <= 60.0 }
            .minByOrNull { (_, dist) -> dist }
            ?.first

        ContextCoordinateMatch(
            savedArea = savedArea,
            placemark = nearbyPlacemark,
            route = nearbyRoute,
            nearbyOsmPoi = nearbyPoi
        )
    }

    private suspend fun getSavedRouteModelsSnapshot(): List<SavedRouteModel> {
        return getSavedRouteModels(routeDao)
    }

    private data class LocalOsmSummary(
        val radiusUsedMeters: Int,
        val nearbyItems: List<AreaNearbyItem>,
        val geographicDetails: List<String>,
        val primaryFeatureName: String?,
        val primaryCategory: String?,
        val regionNames: List<String>,
        val roadNames: List<String>
    )

    private suspend fun queryLocalOsmFeatures(latitude: Double, longitude: Double): LocalOsmSummary {
        // Adaptive radius: try 250 m first
        val denseCandidates = collectLocalNearbyAtRadius(latitude, longitude, DENSE_RADIUS_METERS)
        val (radiusUsed, finalNearby) = if (denseCandidates.size >= DENSE_THRESHOLD_COUNT) {
            DENSE_RADIUS_METERS to denseCandidates.take(MAX_NEARBY_RESULTS)
        } else {
            val sparseCandidates = collectLocalNearbyAtRadius(latitude, longitude, SPARSE_RADIUS_METERS)
            SPARSE_RADIUS_METERS to sparseCandidates.take(MAX_NEARBY_RESULTS)
        }

        val tileCoord = TileCoord.fromLatLng(latitude, longitude, 14)
        val regions: List<OsmRegionRecord> = regionDao.getRegionsByTile(14, tileCoord.x, tileCoord.y, limit = 15)
        val roads: List<OsmRoadRecord> = roadDao.getNamedRoadsByTile(14, tileCoord.x, tileCoord.y, limit = 40)

        val roadNames = roads.mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } }
            .distinct()
            .take(5)
        val regionNames = regions.map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(4)

        val geoDetails = mutableListOf<String>()
        if (regionNames.isNotEmpty()) {
            geoDetails.add("Local boundaries: ${regionNames.joinToString(", ")}")
        }
        if (roadNames.isNotEmpty()) {
            geoDetails.add("Nearby roads in retained tile: ${roadNames.joinToString(", ")}")
        }
        val closestBuilding = buildingDao.getBuildingsInBoundingBox(
            minLat = latitude - 0.001,
            maxLat = latitude + 0.001,
            minLng = longitude - 0.001,
            maxLng = longitude + 0.001,
            limit = 20
        ).minByOrNull { RouteGeometryCodec.haversineMeters(latitude, longitude, it.centroidLat, it.centroidLng) }

        if (closestBuilding != null) {
            val bDesc = buildString {
                append(closestBuilding.name?.takeIf { it.isNotBlank() } ?: "Building (${closestBuilding.buildingType})")
                if (closestBuilding.heightMeters > 0f) {
                    append(" • ~${closestBuilding.heightMeters.toInt()} m height")
                }
            }
            geoDetails.add("Nearest retained building: $bDesc")
        }

        val closestItem = finalNearby.firstOrNull()?.takeIf { it.distanceMeters <= 120.0 }

        return LocalOsmSummary(
            radiusUsedMeters = radiusUsed,
            nearbyItems = finalNearby,
            geographicDetails = geoDetails.distinct(),
            primaryFeatureName = closestItem?.name,
            primaryCategory = closestItem?.category,
            regionNames = regionNames,
            roadNames = roadNames
        )
    }

    private suspend fun collectLocalNearbyAtRadius(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int
    ): List<AreaNearbyItem> {
        val box = SavedAreaNearbyCodec.boundingBoxForRadius(latitude, longitude, radiusMeters.toDouble())
        val pois: List<OsmPoiRecord> = poiDao.getPoisInBoundingBox(
            minLat = box.minLat,
            maxLat = box.maxLat,
            minLng = box.minLng,
            maxLng = box.maxLng,
            limit = 150
        )
        val buildings: List<OsmBuildingRecord> = buildingDao.getBuildingsInBoundingBox(
            minLat = box.minLat,
            maxLat = box.maxLat,
            minLng = box.minLng,
            maxLng = box.maxLng,
            limit = 60
        )

        val items = mutableListOf<AreaNearbyItem>()
        for (poi in pois) {
            val dist = RouteGeometryCodec.haversineMeters(latitude, longitude, poi.latitude, poi.longitude)
            if (dist <= radiusMeters) {
                val catLabel = if (!poi.subCategory.isNullOrBlank() && !poi.subCategory.equals(poi.category, ignoreCase = true)) {
                    "${poi.category} • ${poi.subCategory}"
                } else {
                    poi.category
                }
                items.add(
                    AreaNearbyItem(
                        id = "osm_poi_${poi.osmId}",
                        name = poi.name,
                        category = catLabel,
                        distanceMeters = dist,
                        latitude = poi.latitude,
                        longitude = poi.longitude,
                        sourceLabel = "Local OSM",
                        selectedForSave = true
                    )
                )
            }
        }

        for (bld in buildings) {
            val bName = bld.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val dist = RouteGeometryCodec.haversineMeters(latitude, longitude, bld.centroidLat, bld.centroidLng)
            if (dist <= radiusMeters) {
                items.add(
                    AreaNearbyItem(
                        id = "osm_bld_${bld.osmId}",
                        name = bName,
                        category = "building (${bld.buildingType})",
                        distanceMeters = dist,
                        latitude = bld.centroidLat,
                        longitude = bld.centroidLng,
                        sourceLabel = "Local OSM",
                        selectedForSave = true
                    )
                )
            }
        }

        return items
            .sortedBy { it.distanceMeters }
            .distinctBy { "${it.name.lowercase(Locale.US)}_${it.category.lowercase(Locale.US)}" }
    }

    private fun mergeLocalAndRemote(
        baseline: AreaDetailsResult,
        remote: RemoteAreaEnrichmentResult,
        isRefresh: Boolean
    ): AreaDetailsResult {
        val mergedIdentity = when {
            baseline.isSavedSnapshot && !isRefresh && baseline.locationIdentity != null -> baseline.locationIdentity
            remote.identity != null -> remote.identity
            else -> baseline.locationIdentity
        }

        val mergedNearby = mergeNearbyItems(baseline.nearbyPlaces, remote.nearbyWikiPlaces)
            .sortedBy { it.distanceMeters }
            .take(MAX_NEARBY_RESULTS)

        val mergedGeo = (remote.geographicDetails + baseline.geographicDetails).distinct()
        val hasLocalData = baseline.nearbyPlaces.any { it.sourceLabel == "Local OSM" } ||
            baseline.geographicDetails.isNotEmpty()
        val hasOnlineData = remote.identity != null || remote.wikiInfo != null || remote.nearbyWikiPlaces.isNotEmpty()

        val nextProvenance = when {
            baseline.isSavedSnapshot && (!isRefresh || !hasOnlineData) -> AreaDataProvenance.SAVED_SNAPSHOT
            hasOnlineData && hasLocalData -> AreaDataProvenance.HYBRID_ONLINE_AND_LOCAL
            hasOnlineData -> AreaDataProvenance.ONLINE_LIVE
            else -> AreaDataProvenance.LOCAL_ONLY
        }

        val attribution = when {
            baseline.isSavedSnapshot && !hasOnlineData -> baseline.sourceAttribution
            else -> buildList {
                if (hasLocalData) add("Local Retained OSM")
                addAll(remote.sourceAttributions)
            }.distinct().joinToString(" • ").ifEmpty { baseline.sourceAttribution }
        }

        val partialNote = if (remote.partialErrors.isNotEmpty()) {
            remote.partialErrors.joinToString(" • ")
        } else {
            null
        }

        val nextRetrievedAt = if (hasOnlineData) clockMs() else baseline.retrievedAt

        return baseline.copy(
            retrievedAt = nextRetrievedAt,
            provenance = nextProvenance,
            locationIdentity = mergedIdentity,
            nearbyPlaces = mergedNearby,
            geographicDetails = mergedGeo,
            wikiInfo = remote.wikiInfo ?: baseline.wikiInfo,
            wikiStatusNote = if (remote.wikiInfo != null) null else (remote.wikiStatusNote ?: baseline.wikiStatusNote),
            isLoadingOnline = false,
            isOffline = false,
            partialWarning = partialNote,
            errorMessage = if (!hasOnlineData && !hasLocalData && !baseline.isSavedSnapshot) remote.fatalError else null,
            activeReverseProviderId = remote.providerId,
            reverseGeocoderNote = remote.reverseGeocoderStatusNote,
            sourceAttribution = attribution
        )
    }

    private fun mergeNearbyItems(
        primary: List<AreaNearbyItem>,
        secondary: List<AreaNearbyItem>
    ): List<AreaNearbyItem> {
        val combined = LinkedHashMap<String, AreaNearbyItem>()
        for (item in primary + secondary) {
            val key = item.name.trim().lowercase(Locale.US)
            if (!combined.containsKey(key)) {
                combined[key] = item
            }
        }
        return combined.values.sortedBy { it.distanceMeters }
    }

    private fun buildCacheKey(providerKey: String, lat: Double, lng: Double, radiusMeters: Int): String {
        val roundedLat = kotlin.math.round(lat * 10_000.0) / 10_000.0
        val roundedLng = kotlin.math.round(lng * 10_000.0) / 10_000.0
        val normLat = if (kotlin.math.abs(roundedLat) < 1e-9) 0.0 else roundedLat
        val normLng = if (kotlin.math.abs(roundedLng) < 1e-9) 0.0 else roundedLng
        return String.format(Locale.US, "%s:%.4f,%.4f:%d", providerKey, normLat, normLng, radiusMeters)
    }
}

private suspend fun getSavedRouteModels(
    routeDao: com.example.data.db.SavedRouteDao
): List<SavedRouteModel> {
    return try {
        val rows = routeDao.observeRoutesWithWaypoints().first()
        rows.map { row ->
            val decoded = RouteGeometryCodec.decode(row.route.geometryJson)
            SavedRouteModel(
                route = row.route,
                points = decoded.points,
                waypoints = row.waypoints.sortedBy { it.orderIndex },
                hasMalformedGeometry = decoded.isMalformed
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
