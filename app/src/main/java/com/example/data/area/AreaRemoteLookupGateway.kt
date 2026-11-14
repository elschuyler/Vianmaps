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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RemoteAreaEnrichmentResult(
    val providerId: String = ReverseGeocodingProviderConfig.DISABLED_ID,
    val providerDisplayName: String = "Offline / Disabled",
    val identity: AreaLocationIdentity? = null,
    val nearbyWikiPlaces: List<AreaNearbyItem> = emptyList(),
    val geographicDetails: List<String> = emptyList(),
    val wikiInfo: AreaWikiInfo? = null,
    val wikiStatusNote: String? = null,
    val reverseGeocoderStatusNote: String? = null,
    val isReverseGeocoderEnabled: Boolean = false,
    val hasTransientError: Boolean = false,
    val isValidNoResult: Boolean = false,
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

    fun activeProviderCacheKey(): String = "default"

    fun reverseProviderConfigStatus(): ProviderConfigStatus = ProviderConfigStatus.Ready
}

/**
 * Policy-hardened gateway coordinating pluggable [ReverseGeocodingProvider] + Wikipedia/Wikidata enrichment.
 *
 * Key compliance guarantees:
 * 1. Public `nominatim.openstreetmap.org` is DISABLED in default production config unless a verified
 *    non-placeholder contact AND a central application-wide rate-limiting proxy are provided.
 * 2. Local per-device mutex spacing is documented honestly as client-side pacing only — NOT as
 *    application-wide Nominatim compliance across multiple user devices.
 * 3. Respects HTTP 429 & `Retry-After` cooldowns per provider without retry loops.
 * 4. Wikipedia Geosearch + REST v1 `/page/summary/` validates distance and entity names strictly.
 */
class HttpAreaRemoteLookupGateway(
    val providerRegistry: ReverseGeocodingProviderRegistry = ReverseGeocodingProviderRegistry.createDefault(),
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val wikipediaApiBaseUrl: String = "https://en.wikipedia.org/w/api.php",
    private val wikipediaRestBaseUrl: String = "https://en.wikipedia.org/api/rest_v1",
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val minWikipediaIntervalMs: Long = 200L
) : AreaRemoteLookupGateway {

    private val providerMutexMap = ConcurrentHashMap<String, Mutex>()
    private val lastProviderCallMsMap = ConcurrentHashMap<String, Long>()
    private val cooldownUntilMsMap = ConcurrentHashMap<String, Long>()

    private val wikiMutex = Mutex()
    @Volatile
    private var lastWikiCallMs: Long = 0L

    companion object {
        const val WIKIPEDIA_USER_AGENT = "VianMaps/1.0 (Android; local-first mapping)"
        private const val WIKI_COOLDOWN_KEY = "__wikipedia__"
        private const val RELIABLE_WIKI_CLOSE_METERS = 350.0
        private const val RELIABLE_WIKI_NAME_MATCH_MAX_METERS = 2_000.0
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        private const val MAX_RETRY_AFTER_SECONDS = 3600L

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun activeProviderCacheKey(): String = providerRegistry.activeCacheKeySegment()

    override fun reverseProviderConfigStatus(): ProviderConfigStatus =
        providerRegistry.activeProvider().configurationStatus()

    override suspend fun lookupAreaOnline(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        localHintNames: List<String>
    ): RemoteAreaEnrichmentResult = withContext(Dispatchers.IO) {
        val provider = providerRegistry.activeProvider()
        val providerStatus = provider.configurationStatus()

        val partialErrors = mutableListOf<String>()
        val attributions = mutableListOf<String>()
        val geoDetails = mutableListOf<String>()

        var identity: AreaLocationIdentity? = null
        var nominatimWikiTag: String? = null
        var nominatimWikidataId: String? = null
        var reverseStatusNote: String? = null
        var reverseEnabled = false
        var reverseTransientFailed = false
        var reverseValidNoResult = false

        // 1. Reverse geocoding via active provider (only if valid & permitted)
        when (providerStatus) {
            is ProviderConfigStatus.Disabled -> {
                reverseEnabled = false
                reverseStatusNote = providerStatus.reason
            }
            ProviderConfigStatus.Ready -> {
                reverseEnabled = true
                when (val outcome = executeReverseProvider(provider, latitude, longitude)) {
                    is ReverseGeocodingOutcome.Success -> {
                        identity = outcome.identity
                        nominatimWikiTag = outcome.wikipediaTag
                        nominatimWikidataId = outcome.wikidataId
                        geoDetails.addAll(outcome.geographicDetails)
                        attributions.add(outcome.attribution)
                    }
                    is ReverseGeocodingOutcome.NoResult -> {
                        reverseValidNoResult = true
                        reverseStatusNote = outcome.note
                        attributions.add(outcome.attribution)
                    }
                    is ReverseGeocodingOutcome.TransientError -> {
                        reverseTransientFailed = true
                        partialErrors.add(outcome.message)
                    }
                    is ReverseGeocodingOutcome.Disabled -> {
                        reverseEnabled = false
                        reverseStatusNote = outcome.reason
                    }
                }
            }
        }

        // 2. Wikipedia / Wikidata enrichment (optional, user-triggered, strictly reliability-checked)
        var wikiInfo: AreaWikiInfo? = null
        var wikiStatusNote: String? = null
        val nearbyWikiPlaces = mutableListOf<AreaNearbyItem>()
        var wikiTransientFailed = false

        val wikiCooldownRemainingSec = remainingCooldownSeconds(WIKI_COOLDOWN_KEY)
        if (wikiCooldownRemainingSec > 0L) {
            wikiTransientFailed = true
            partialErrors.add("Wikipedia lookup paused for ${wikiCooldownRemainingSec}s (HTTP 429 Retry-After).")
            wikiStatusNote = "Wikipedia temporarily rate limited."
        } else {
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
                        val summary = fetchWikipediaSummary(
                            lang = lang,
                            title = rawTitle,
                            wikidataId = nominatimWikidataId,
                            distanceMeters = 0.0
                        )
                        if (summary != null) {
                            wikiInfo = summary
                            attributions.add("© Wikipedia contributors (CC BY-SA 4.0)")
                        } else {
                            wikiStatusNote = "Referenced Wikipedia article was ambiguous or unavailable."
                        }
                    }
                }

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
                            if (!attributions.contains("© Wikipedia contributors (CC BY-SA 4.0)")) {
                                attributions.add("© Wikipedia contributors (CC BY-SA 4.0)")
                            }
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
            } catch (httpEx: HttpStatusException) {
                wikiTransientFailed = true
                if (httpEx.code == 429) {
                    val waitSec = recordCooldown(WIKI_COOLDOWN_KEY, httpEx.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS)
                    partialErrors.add("Wikipedia rate limited (HTTP 429). Retry after ${waitSec}s.")
                    wikiStatusNote = "Wikipedia temporarily rate limited."
                } else {
                    partialErrors.add("Wikipedia lookup unavailable: ${httpEx.message}")
                    wikiStatusNote = "Wiki summary temporarily unavailable."
                }
            } catch (e: Throwable) {
                wikiTransientFailed = true
                partialErrors.add("Wikipedia lookup unavailable: ${e.message ?: "Network error"}")
                wikiStatusNote = "Wiki summary temporarily unavailable."
            }
        }

        val hasAnyTransientError = reverseTransientFailed || wikiTransientFailed
        val hasAnyData = identity != null || wikiInfo != null || nearbyWikiPlaces.isNotEmpty()
        val fatal = if (!hasAnyData && hasAnyTransientError) {
            partialErrors.joinToString(" • ")
        } else {
            null
        }
        val validNoResult = !hasAnyData && !hasAnyTransientError && (reverseValidNoResult || !reverseEnabled)

        RemoteAreaEnrichmentResult(
            providerId = provider.providerId,
            providerDisplayName = provider.displayName,
            identity = identity,
            nearbyWikiPlaces = nearbyWikiPlaces,
            geographicDetails = geoDetails.distinct(),
            wikiInfo = wikiInfo,
            wikiStatusNote = wikiStatusNote,
            reverseGeocoderStatusNote = reverseStatusNote,
            isReverseGeocoderEnabled = reverseEnabled,
            hasTransientError = hasAnyTransientError,
            isValidNoResult = validNoResult,
            partialErrors = partialErrors,
            fatalError = fatal,
            sourceAttributions = attributions.distinct()
        )
    }

    private suspend fun executeReverseProvider(
        provider: ReverseGeocodingProvider,
        latitude: Double,
        longitude: Double
    ): ReverseGeocodingOutcome {
        val cooldownRemSec = remainingCooldownSeconds(provider.providerId)
        if (cooldownRemSec > 0L) {
            return ReverseGeocodingOutcome.TransientError(
                providerId = provider.providerId,
                message = "${provider.displayName} paused by Retry-After cooldown (${cooldownRemSec}s remaining).",
                httpCode = 429,
                retryAfterSeconds = cooldownRemSec,
                isRateLimited = true
            )
        }

        // Local per-device pacing only (does NOT enforce cross-device app-wide rate limits).
        val intervalMs = provider.config.minRequestIntervalMs.coerceAtLeast(0L)
        if (intervalMs > 0L) {
            val mutex = providerMutexMap.getOrPut(provider.providerId) { Mutex() }
            mutex.withLock {
                val now = clockMs()
                val lastCall = lastProviderCallMsMap[provider.providerId] ?: 0L
                val waitMs = (lastCall + intervalMs) - now
                if (waitMs > 0L) {
                    delay(waitMs)
                }
                lastProviderCallMsMap[provider.providerId] = clockMs()
            }
        }

        val spec = provider.buildRequestSpec(latitude, longitude)
        return try {
            val body = executeGet(url = spec.url, userAgent = spec.userAgent, extraHeaders = spec.headers)
            provider.parseSuccessResponse(body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (httpEx: HttpStatusException) {
            val retryAfter = httpEx.retryAfterSeconds
            if (httpEx.code == 429 || (httpEx.code == 503 && retryAfter != null)) {
                val applied = recordCooldown(
                    provider.providerId,
                    retryAfter ?: provider.config.defaultCooldownSeconds
                )
                provider.handleHttpError(httpEx.code, httpEx.statusMessage, applied)
            } else {
                provider.handleHttpError(httpEx.code, httpEx.statusMessage, retryAfter)
            }
        } catch (e: Throwable) {
            ReverseGeocodingOutcome.TransientError(
                providerId = provider.providerId,
                message = "${provider.displayName} network error: ${e.message ?: "Request failed"}"
            )
        }
    }

    private fun remainingCooldownSeconds(key: String): Long {
        val until = cooldownUntilMsMap[key] ?: return 0L
        val remainingMs = until - clockMs()
        return if (remainingMs > 0L) {
            ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        } else {
            cooldownUntilMsMap.remove(key, until)
            0L
        }
    }

    private fun recordCooldown(key: String, seconds: Long): Long {
        val bounded = seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
        cooldownUntilMsMap[key] = clockMs() + (bounded * 1000L)
        return bounded
    }

    private data class GeosearchHit(
        val pageId: Long,
        val title: String,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double
    )

    private suspend fun fetchWikipediaGeosearch(lat: Double, lng: Double, radiusMeters: Int): List<GeosearchHit> {
        paceWikipediaCall()
        val url = String.format(
            Locale.US,
            "%s?action=query&list=geosearch&gscoord=%.6f%%7C%.6f&gsradius=%d&gslimit=5&format=json",
            wikipediaApiBaseUrl,
            lat,
            lng,
            radiusMeters.coerceIn(100, 1000)
        )
        val body = executeGet(url = url, userAgent = WIKIPEDIA_USER_AGENT)
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
        paceWikipediaCall()
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
        val body = executeGet(url = url, userAgent = WIKIPEDIA_USER_AGENT)
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

    private suspend fun paceWikipediaCall() {
        if (minWikipediaIntervalMs <= 0L) return
        wikiMutex.withLock {
            val waitMs = (lastWikiCallMs + minWikipediaIntervalMs) - clockMs()
            if (waitMs > 0L) {
                delay(waitMs)
            }
            lastWikiCallMs = clockMs()
        }
    }

    private fun pickReliableGeosearchCandidate(
        hits: List<GeosearchHit>,
        hintNames: List<String>
    ): GeosearchHit? {
        if (hits.isEmpty()) return null
        val normalizedHints = hintNames.map { it.trim().lowercase(Locale.US) }.filter { it.length >= 3 }

        val nameMatched = hits.firstOrNull { hit ->
            val hLower = hit.title.lowercase(Locale.US)
            hit.distanceMeters <= RELIABLE_WIKI_NAME_MATCH_MAX_METERS &&
                normalizedHints.any { hint ->
                    hLower == hint || hLower.contains(hint) || hint.contains(hLower)
                }
        }
        if (nameMatched != null) return nameMatched

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

    private class HttpStatusException(
        val code: Int,
        val statusMessage: String,
        val retryAfterSeconds: Long?
    ) : IOException("HTTP $code $statusMessage")

    private fun parseRetryAfterSeconds(headerValue: String?): Long? {
        val raw = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { secs ->
            return secs.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
        }
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val parsedDate = fmt.parse(raw) ?: return null
            val deltaSeconds = ((parsedDate.time - clockMs()) + 999L) / 1000L
            deltaSeconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun executeGet(
        url: String,
        userAgent: String,
        extraHeaders: Map<String, String> = mapOf("Accept" to "application/json")
    ): String = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
        for ((k, v) in extraHeaders) {
            builder.header(k, v)
        }
        val request = builder.get().build()

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
                        val retryAfter = parseRetryAfterSeconds(res.header("Retry-After"))
                        if (cont.isActive) {
                            cont.resumeWithException(HttpStatusException(res.code, res.message, retryAfter))
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
