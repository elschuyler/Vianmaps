package com.example.data.area

import com.example.data.places.RouteGeometryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RemoteAreaEnrichmentResult(
    val identity: AreaLocationIdentity? = null,
    val nearbyWikiPlaces: List<AreaNearbyItem> = emptyList(),
    val geographicDetails: List<String> = emptyList(),
    val wikiInfo: AreaWikiInfo? = null,
    val wikiStatusNote: String? = null,
    val partialErrors: List<String> = emptyList(),
    val fatalError: String? = null,
    val sourceAttributions: List<String> = emptyList()
)

interface AreaRemoteLookupGateway {
    suspend fun lookupAreaOnline(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        localHintNames: List<String>
    ): RemoteAreaEnrichmentResult
}

/**
 * Policy-compliant online lookup gateway using:
 * 1. OpenStreetMap Nominatim `/reverse` (rate-limited <= 1 req/sec, descriptive User-Agent).
 * 2. MediaWiki Geosearch + Wikipedia REST v1 `/page/summary/` (zero API key, CC BY-SA 4.0).
 *
 * Strictly validates Wikipedia matches against distance and entity-name overlap
 * so ambiguous or distant pages are never presented as false matches.
 */
class HttpAreaRemoteLookupGateway(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val nominatimBaseUrl: String = "https://nominatim.openstreetmap.org",
    private val wikipediaApiBaseUrl: String = "https://en.wikipedia.org/w/api.php",
    private val wikipediaRestBaseUrl: String = "https://en.wikipedia.org/api/rest_v1",
    private val minNominatimIntervalMs: Long = 1_100L
) : AreaRemoteLookupGateway {

    private val nominatimMutex = Mutex()
    @Volatile
    private var lastNominatimCallMs: Long = 0L

    companion object {
        const val APP_USER_AGENT = "VianMaps/1.0 (Android; local-first mapping; contact=vianmaps-app@example.invalid)"
        private const val RELIABLE_WIKI_CLOSE_METERS = 350.0
        private const val RELIABLE_WIKI_NAME_MATCH_MAX_METERS = 2_000.0

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun lookupAreaOnline(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        localHintNames: List<String>
    ): RemoteAreaEnrichmentResult = withContext(Dispatchers.IO) {
        val partialErrors = mutableListOf<String>()
        val attributions = mutableListOf<String>()
        val geoDetails = mutableListOf<String>()

        var identity: AreaLocationIdentity? = null
        var nominatimWikiTag: String? = null
        var nominatimWikidataId: String? = null

        // 1. Reverse geocoding via Nominatim
        try {
            val rev = fetchNominatimReverse(latitude, longitude)
            if (rev != null) {
                identity = rev.identity
                nominatimWikiTag = rev.wikipediaTag
                nominatimWikidataId = rev.wikidataId
                geoDetails.addAll(rev.geographicDetails)
                attributions.add("© OpenStreetMap contributors (Nominatim)")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            partialErrors.add("Reverse geocoding unavailable: ${e.message ?: "Network error"}")
        }

        // 2. Wikipedia / Wikidata enrichment (optional, strictly reliability-checked)
        var wikiInfo: AreaWikiInfo? = null
        var wikiStatusNote: String? = null
        val nearbyWikiPlaces = mutableListOf<AreaNearbyItem>()

        try {
            val candidateHints = buildList {
                identity?.primaryName?.takeIf { it.isNotBlank() }?.let { add(it) }
                identity?.locality?.takeIf { it.isNotBlank() }?.let { add(it) }
                identity?.featureName?.takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(localHintNames.filter { it.isNotBlank() })
            }

            if (!nominatimWikiTag.isNullOrBlank()) {
                val parsed = parseWikipediaTag(nominatimWikiTag)
                if (parsed != null) {
                    val (lang, rawTitle) = parsed
                    val summary = fetchWikipediaSummary(lang = lang, title = rawTitle, wikidataId = nominatimWikidataId, distanceMeters = 0.0)
                    if (summary != null) {
                        wikiInfo = summary
                        attributions.add("© Wikipedia contributors (CC BY-SA 4.0)")
                    } else {
                        wikiStatusNote = "Referenced Wikipedia article was ambiguous or unavailable."
                    }
                }
            }

            // Always check geosearch when radius > 0 to surface nearby documented landmarks cleanly
            val geosearchHits = fetchWikipediaGeosearch(latitude, longitude, radiusMeters.coerceIn(250, 1000))
            for (hit in geosearchHits) {
                nearbyWikiPlaces.add(
                    AreaNearbyItem(
                        id = "wiki_${hit.pageId}",
                        name = hit.title,
                        category = "Wikipedia landmark",
                        distanceMeters = hit.distanceMeters,
                        latitude = hit.latitude,
                        longitude = hit.longitude,
                        sourceLabel = "Online Wikipedia",
                        selectedForSave = false
                    )
                )
            }
            if (geosearchHits.isNotEmpty() && !attributions.contains("© Wikipedia contributors (CC BY-SA 4.0)")) {
                attributions.add("© Wikipedia contributors (CC BY-SA 4.0)")
            }

            if (wikiInfo == null) {
                val bestCandidate = pickReliableGeosearchCandidate(geosearchHits, candidateHints)
                if (bestCandidate != null) {
                    val summary = fetchWikipediaSummary(
                        lang = "en",
                        title = bestCandidate.title,
                        wikidataId = nominatimWikidataId,
                        distanceMeters = bestCandidate.distanceMeters
                    )
                    if (summary != null) {
                        wikiInfo = summary
                    } else {
                        wikiStatusNote = "Nearby Wikipedia match is a disambiguation page or lacks a summary."
                    }
                } else if (geosearchHits.isEmpty()) {
                    wikiStatusNote = "No nearby Wikipedia article found within ${radiusMeters}m."
                } else {
                    wikiStatusNote = "Multiple or distant Wikipedia entries nearby; no single reliable match."
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            partialErrors.add("Wikipedia lookup unavailable: ${e.message ?: "Network error"}")
            wikiStatusNote = "Wiki summary temporarily unavailable."
        }

        val fatal = if (identity == null && nearbyWikiPlaces.isEmpty() && wikiInfo == null && partialErrors.isNotEmpty()) {
            partialErrors.joinToString(" • ")
        } else {
            null
        }

        RemoteAreaEnrichmentResult(
            identity = identity,
            nearbyWikiPlaces = nearbyWikiPlaces,
            geographicDetails = geoDetails.distinct(),
            wikiInfo = wikiInfo,
            wikiStatusNote = wikiStatusNote,
            partialErrors = if (fatal == null) partialErrors else emptyList(),
            fatalError = fatal,
            sourceAttributions = attributions.distinct()
        )
    }

    private data class NominatimParsed(
        val identity: AreaLocationIdentity,
        val geographicDetails: List<String>,
        val wikipediaTag: String?,
        val wikidataId: String?
    )

    private suspend fun fetchNominatimReverse(latitude: Double, longitude: Double): NominatimParsed? {
        nominatimMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (lastNominatimCallMs + minNominatimIntervalMs) - now
            if (waitMs > 0L) {
                delay(waitMs)
            }
            lastNominatimCallMs = System.currentTimeMillis()
        }

        val url = String.format(
            Locale.US,
            "%s/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1&extratags=1&namedetails=1",
            nominatimBaseUrl.trimEnd('/'),
            latitude,
            longitude
        )
        val body = executeGet(url)
        val json = JSONObject(body)
        if (json.has("error")) {
            return null
        }

        val addressObj = json.optJSONObject("address")
        val extraObj = json.optJSONObject("extratags")

        val road = addressObj?.optString("road", "")?.trim().orEmpty()
        val house = addressObj?.optString("house_number", "")?.trim().orEmpty()
        val neighbourhood = listOf("neighbourhood", "suburb", "quarter", "borough")
            .firstNotNullOfOrNull { key -> addressObj?.optString(key, "")?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val locality = listOf("city", "town", "village", "municipality", "hamlet")
            .firstNotNullOfOrNull { key -> addressObj?.optString(key, "")?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val region = listOf("state", "province", "region", "county", "state_district")
            .firstNotNullOfOrNull { key -> addressObj?.optString(key, "")?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val country = addressObj?.optString("country", "")?.trim().orEmpty()

        val rawName = json.optString("name", "").trim()
        val category = json.optString("category", "").trim().ifEmpty { "place" }
        val type = json.optString("type", "").trim()
        val displayName = json.optString("display_name", "").trim()

        val streetAddress = listOf(house, road).filter { it.isNotEmpty() }.joinToString(" ")
        val primaryName = rawName
            .ifEmpty { streetAddress }
            .ifEmpty { neighbourhood }
            .ifEmpty { locality }
            .ifEmpty { displayName.substringBefore(",").trim() }
            .ifEmpty { "Selected Location" }

        val localityDisplay = listOf(neighbourhood, locality).filter { it.isNotEmpty() }.distinct().joinToString(", ")
        val composedAddress = listOf(streetAddress, localityDisplay, region, country)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
            .ifEmpty { displayName }

        val details = mutableListOf<String>()
        if (type.isNotEmpty() && type != "yes") {
            details.add("OSM feature: $category / $type")
        }
        val adminSummary = listOf(locality, region, country).filter { it.isNotEmpty() }.distinct().joinToString(" • ")
        if (adminSummary.isNotEmpty()) {
            details.add("Administrative area: $adminSummary")
        }

        val wikipediaTag = extraObj?.optString("wikipedia", "")?.trim()?.takeIf { it.isNotEmpty() }
        val wikidataId = extraObj?.optString("wikidata", "")?.trim()?.takeIf { it.isNotEmpty() }
        if (!wikidataId.isNullOrBlank()) {
            details.add("Wikidata entity: $wikidataId")
        }

        return NominatimParsed(
            identity = AreaLocationIdentity(
                primaryName = primaryName,
                addressLabel = composedAddress,
                locality = locality.ifEmpty { neighbourhood },
                administrativeRegion = region,
                country = country,
                featureName = if (rawName.isNotEmpty()) rawName else type,
                category = type.ifEmpty { category },
                attribution = "© OpenStreetMap contributors (Nominatim)"
            ),
            geographicDetails = details,
            wikipediaTag = wikipediaTag,
            wikidataId = wikidataId
        )
    }

    private data class GeosearchHit(
        val pageId: Long,
        val title: String,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double
    )

    private suspend fun fetchWikipediaGeosearch(lat: Double, lng: Double, radiusMeters: Int): List<GeosearchHit> {
        val url = String.format(
            Locale.US,
            "%s?action=query&list=geosearch&gscoord=%.6f%%7C%.6f&gsradius=%d&gslimit=5&format=json",
            wikipediaApiBaseUrl,
            lat,
            lng,
            radiusMeters.coerceIn(100, 1000)
        )
        val body = executeGet(url)
        val json = JSONObject(body)
        val query = json.optJSONObject("query") ?: return emptyList()
        val arr = query.optJSONArray("geosearch") ?: return emptyList()
        val out = mutableListOf<GeosearchHit>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title", "").trim()
            val hLat = item.optDouble("lat", Double.NaN)
            val hLng = item.optDouble("lon", Double.NaN)
            if (title.isEmpty() || !hLat.isFinite() || !hLng.isFinite()) continue
            val dist = item.optDouble("dist", Double.NaN)
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: RouteGeometryCodec.haversineMeters(lat, lng, hLat, hLng)
            out.add(
                GeosearchHit(
                    pageId = item.optLong("pageid", i.toLong()),
                    title = title,
                    latitude = hLat,
                    longitude = hLng,
                    distanceMeters = dist
                )
            )
        }
        return out.sortedBy { it.distanceMeters }
    }

    private suspend fun fetchWikipediaSummary(
        lang: String,
        title: String,
        wikidataId: String?,
        distanceMeters: Double?
    ): AreaWikiInfo? {
        val cleanLang = lang.trim().lowercase(Locale.US).ifEmpty { "en" }.take(8)
        val normalizedTitle = title.trim().replace(' ', '_')
        if (normalizedTitle.isEmpty()) return null
        val encodedTitle = URLEncoder.encode(normalizedTitle, "UTF-8").replace("+", "%20")

        val base = if (cleanLang == "en") {
            wikipediaRestBaseUrl.trimEnd('/')
        } else {
            "https://$cleanLang.wikipedia.org/api/rest_v1"
        }
        val url = "$base/page/summary/$encodedTitle"
        val body = executeGet(url)
        val json = JSONObject(body)
        val pageType = json.optString("type", "").trim()
        if (pageType.equals("disambiguation", ignoreCase = true) || pageType.equals("no-extract", ignoreCase = true)) {
            return null
        }
        val resolvedTitle = json.optString("title", title).trim()
        val extract = json.optString("extract", "").trim()
        if (resolvedTitle.isEmpty() || extract.isEmpty()) return null

        val contentUrls = json.optJSONObject("content_urls")
        val mobileUrl = contentUrls?.optJSONObject("mobile")?.optString("page", "")?.trim().orEmpty()
        val desktopUrl = contentUrls?.optJSONObject("desktop")?.optString("page", "")?.trim().orEmpty()
        val articleUrl = mobileUrl.ifEmpty { desktopUrl }.ifEmpty { "https://$cleanLang.wikipedia.org/wiki/$encodedTitle" }
        val resolvedWikidata = json.optString("wikibase_item", "").trim().ifEmpty { wikidataId.orEmpty() }.takeIf { it.isNotEmpty() }

        return AreaWikiInfo(
            title = resolvedTitle,
            summary = extract,
            url = articleUrl,
            wikidataId = resolvedWikidata,
            distanceMeters = distanceMeters,
            isReliableMatch = true
        )
    }

    private fun pickReliableGeosearchCandidate(
        hits: List<GeosearchHit>,
        hintNames: List<String>
    ): GeosearchHit? {
        if (hits.isEmpty()) return null
        val normalizedHints = hintNames.map { it.trim().lowercase(Locale.US) }.filter { it.length >= 3 }

        // Priority 1: Explicit name match with local/Nominatim identity within reasonable distance
        val nameMatched = hits.firstOrNull { hit ->
            val hLower = hit.title.lowercase(Locale.US)
            hit.distanceMeters <= RELIABLE_WIKI_NAME_MATCH_MAX_METERS &&
                normalizedHints.any { hint ->
                    hLower == hint || hLower.contains(hint) || hint.contains(hLower)
                }
        }
        if (nameMatched != null) return nameMatched

        // Priority 2: Closest distinct feature strictly within 350m and clearly closer than runner-up
        val closest = hits.first()
        if (closest.distanceMeters <= RELIABLE_WIKI_CLOSE_METERS) {
            val second = hits.getOrNull(1)
            if (second == null || (second.distanceMeters - closest.distanceMeters) >= 40.0 || closest.distanceMeters <= 120.0) {
                return closest
            }
        }
        return null
    }

    private fun parseWikipediaTag(tag: String): Pair<String, String>? {
        val trimmed = tag.trim()
        val idx = trimmed.indexOf(':')
        if (idx <= 0 || idx >= trimmed.lastIndex) return null
        val lang = trimmed.substring(0, idx).trim()
        val title = trimmed.substring(idx + 1).trim()
        if (lang.isEmpty() || title.isEmpty()) return null
        return lang to title
    }

    private suspend fun executeGet(url: String): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", APP_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val call = client.newCall(request)
        cont.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Throwable) {}
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        if (cont.isActive) {
                            cont.resumeWithException(IOException("HTTP ${res.code} ${res.message}"))
                        }
                        return
                    }
                    val text = res.body?.string().orEmpty()
                    if (cont.isActive) {
                        cont.resume(text)
                    }
                }
            }
        })
    }
}
