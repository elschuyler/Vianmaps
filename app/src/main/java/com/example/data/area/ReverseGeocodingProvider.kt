package com.example.data.area

import org.json.JSONObject
import java.net.URI
import java.util.Locale

enum class ReverseGeocodingProviderType(val idPrefix: String, val displayLabel: String) {
    DISABLED_NONE("disabled", "Disabled"),
    PUBLIC_NOMINATIM("public_nominatim", "Public OSM Nominatim"),
    NOMINATIM_COMPATIBLE("nominatim_compatible", "Nominatim-Compatible Endpoint"),
    PHOTON_COMPATIBLE("photon_compatible", "Photon GeoJSON Reverse Endpoint")
}

sealed interface ProviderConfigStatus {
    data object Ready : ProviderConfigStatus
    data class Disabled(
        val reason: String,
        val missingRequirements: List<String> = emptyList()
    ) : ProviderConfigStatus
}

/**
 * Validates that application contact information for external geocoding services is
 * genuine and non-placeholder. No fake email addresses, `.invalid` domains, or dummy hosts
 * are accepted.
 */
object ReverseGeocodingContactValidator {
    private val DISALLOWED_SUBSTRINGS = listOf(
        ".invalid",
        "example.com",
        "example.org",
        "example.net",
        "placeholder",
        "changeme",
        "noreply",
        "no-reply",
        "test@test",
        "user@domain"
    )

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    fun isValidGenuineContact(contact: String?): Boolean {
        val trimmed = contact?.trim().orEmpty()
        if (trimmed.length < 6) return false
        val lower = trimmed.lowercase(Locale.US)
        if (DISALLOWED_SUBSTRINGS.any { lower.contains(it) }) return false

        return when {
            lower.startsWith("https://") -> {
                val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
                val host = uri.host?.lowercase(Locale.US).orEmpty()
                host.contains('.') && host != "localhost"
            }
            trimmed.contains('@') -> EMAIL_REGEX.matches(trimmed)
            else -> false
        }
    }

    fun isValidEndpointUrl(baseUrl: String?): Boolean {
        val trimmed = baseUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.trim().orEmpty()
        return (scheme == "https" || scheme == "http") && host.isNotEmpty()
    }
}

data class ReverseGeocodingProviderConfig(
    val providerId: String,
    val displayName: String,
    val providerType: ReverseGeocodingProviderType,
    val baseUrl: String = "",
    val contactInfo: String? = null,
    val hasCentralRateLimitingProxy: Boolean = false,
    val customAttribution: String? = null,
    val minRequestIntervalMs: Long = 1_100L,
    val defaultCooldownSeconds: Long = 60L
) {
    fun validate(): ProviderConfigStatus {
        return when (providerType) {
            ReverseGeocodingProviderType.DISABLED_NONE -> ProviderConfigStatus.Disabled(
                reason = "Reverse geocoding is disabled; using local OSM data and optional Wikipedia summaries.",
                missingRequirements = emptyList()
            )
            ReverseGeocodingProviderType.PUBLIC_NOMINATIM -> {
                val missing = mutableListOf<String>()
                if (!ReverseGeocodingContactValidator.isValidGenuineContact(contactInfo)) {
                    missing.add("Verified project contact address or HTTPS project URL")
                }
                if (!hasCentralRateLimitingProxy) {
                    missing.add("Central application-wide rate-limiting proxy (<= 1 req/s across all users)")
                }
                if (!ReverseGeocodingContactValidator.isValidEndpointUrl(baseUrl)) {
                    missing.add("Valid HTTPS endpoint URL")
                }
                if (missing.isNotEmpty()) {
                    ProviderConfigStatus.Disabled(
                        reason = "Public Nominatim is disabled in production (${missing.joinToString("; ")}).",
                        missingRequirements = missing
                    )
                } else {
                    ProviderConfigStatus.Ready
                }
            }
            ReverseGeocodingProviderType.NOMINATIM_COMPATIBLE,
            ReverseGeocodingProviderType.PHOTON_COMPATIBLE -> {
                val missing = mutableListOf<String>()
                if (!ReverseGeocodingContactValidator.isValidEndpointUrl(baseUrl)) {
                    missing.add("Valid HTTP/HTTPS endpoint URL")
                }
                if (contactInfo != null && !ReverseGeocodingContactValidator.isValidGenuineContact(contactInfo)) {
                    missing.add("Contact string provided is invalid or placeholder")
                }
                if (missing.isNotEmpty()) {
                    ProviderConfigStatus.Disabled(
                        reason = "$displayName configuration is invalid (${missing.joinToString("; ")}).",
                        missingRequirements = missing
                    )
                } else {
                    ProviderConfigStatus.Ready
                }
            }
        }
    }

    companion object {
        const val PUBLIC_NOMINATIM_ID = "public_nominatim"
        const val DISABLED_ID = "disabled"

        /**
         * Production default for Public Nominatim: intentionally disabled because no genuine
         * application contact address is configured in the project and no central application-wide
         * proxy exists to enforce OSM's 1 request/second cross-application policy.
         */
        fun publicNominatimProductionDefault(): ReverseGeocodingProviderConfig {
            return ReverseGeocodingProviderConfig(
                providerId = PUBLIC_NOMINATIM_ID,
                displayName = "OpenStreetMap Nominatim (Public)",
                providerType = ReverseGeocodingProviderType.PUBLIC_NOMINATIM,
                baseUrl = "https://nominatim.openstreetmap.org",
                contactInfo = null,
                hasCentralRateLimitingProxy = false,
                customAttribution = "© OpenStreetMap contributors (Nominatim)",
                minRequestIntervalMs = 1_100L
            )
        }

        fun disabledConfig(): ReverseGeocodingProviderConfig {
            return ReverseGeocodingProviderConfig(
                providerId = DISABLED_ID,
                displayName = "Offline / No Reverse Geocoder",
                providerType = ReverseGeocodingProviderType.DISABLED_NONE
            )
        }
    }
}

sealed interface ReverseGeocodingOutcome {
    data class Success(
        val providerId: String,
        val identity: AreaLocationIdentity,
        val geographicDetails: List<String>,
        val wikipediaTag: String?,
        val wikidataId: String?,
        val attribution: String
    ) : ReverseGeocodingOutcome

    data class NoResult(
        val providerId: String,
        val attribution: String,
        val note: String
    ) : ReverseGeocodingOutcome

    data class TransientError(
        val providerId: String,
        val message: String,
        val httpCode: Int? = null,
        val retryAfterSeconds: Long? = null,
        val isRateLimited: Boolean = false
    ) : ReverseGeocodingOutcome

    data class Disabled(
        val providerId: String,
        val reason: String
    ) : ReverseGeocodingOutcome
}

data class ReverseGeocodingRequestSpec(
    val url: String,
    val userAgent: String,
    val headers: Map<String, String> = mapOf("Accept" to "application/json")
)

interface ReverseGeocodingProvider {
    val config: ReverseGeocodingProviderConfig
    val providerId: String get() = config.providerId
    val displayName: String get() = config.displayName
    val attribution: String

    fun configurationStatus(): ProviderConfigStatus = config.validate()

    fun buildRequestSpec(latitude: Double, longitude: Double): ReverseGeocodingRequestSpec

    fun parseSuccessResponse(body: String): ReverseGeocodingOutcome

    fun handleHttpError(code: Int, message: String, retryAfterSeconds: Long?): ReverseGeocodingOutcome.TransientError {
        val is429 = code == 429
        val msg = when {
            is429 && retryAfterSeconds != null ->
                "$displayName rate limited (HTTP 429). Retry after ${retryAfterSeconds}s."
            is429 ->
                "$displayName rate limited (HTTP 429). Pausing requests cleanly."
            else ->
                "$displayName returned HTTP $code ${message.ifBlank { "error" }}."
        }
        return ReverseGeocodingOutcome.TransientError(
            providerId = providerId,
            message = msg,
            httpCode = code,
            retryAfterSeconds = retryAfterSeconds,
            isRateLimited = is429
        )
    }
}

class DisabledReverseGeocodingProvider(
    override val config: ReverseGeocodingProviderConfig = ReverseGeocodingProviderConfig.disabledConfig()
) : ReverseGeocodingProvider {
    override val attribution: String = "Local Retained OSM Data"

    override fun buildRequestSpec(latitude: Double, longitude: Double): ReverseGeocodingRequestSpec {
        throw IllegalStateException("Provider $providerId is disabled.")
    }

    override fun parseSuccessResponse(body: String): ReverseGeocodingOutcome {
        return ReverseGeocodingOutcome.Disabled(
            providerId = providerId,
            reason = (configurationStatus() as? ProviderConfigStatus.Disabled)?.reason ?: "Provider disabled."
        )
    }
}

/**
 * Provider supporting standard Nominatim `/reverse?format=jsonv2` responses
 * (used only when valid endpoint + contact/proxy policy checks pass, such as a
 * self-hosted/proxied Nominatim-compatible instance or explicitly provisioned configuration).
 */
class NominatimCompatibleReverseProvider(
    override val config: ReverseGeocodingProviderConfig
) : ReverseGeocodingProvider {

    override val attribution: String =
        config.customAttribution?.takeIf { it.isNotBlank() }
            ?: "© OpenStreetMap contributors (Nominatim)"

    override fun buildRequestSpec(latitude: Double, longitude: Double): ReverseGeocodingRequestSpec {
        val url = String.format(
            Locale.US,
            "%s/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1&extratags=1&namedetails=1",
            config.baseUrl.trimEnd('/'),
            latitude,
            longitude
        )
        val ua = buildUserAgent(config)
        return ReverseGeocodingRequestSpec(
            url = url,
            userAgent = ua,
            headers = mapOf("Accept" to "application/json")
        )
    }

    override fun parseSuccessResponse(body: String): ReverseGeocodingOutcome {
        if (body.isBlank()) {
            return ReverseGeocodingOutcome.TransientError(
                providerId = providerId,
                message = "$displayName returned an empty response body."
            )
        }
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return ReverseGeocodingOutcome.TransientError(
                providerId = providerId,
                message = "$displayName returned invalid JSON: ${t.message ?: "parse error"}"
            )
        }

        if (json.has("error")) {
            val errMsg = json.optString("error", "").trim()
            if (errMsg.contains("Unable to geocode", ignoreCase = true) ||
                errMsg.contains("not found", ignoreCase = true)
            ) {
                return ReverseGeocodingOutcome.NoResult(
                    providerId = providerId,
                    attribution = attribution,
                    note = "No named place recorded at this coordinate."
                )
            }
            return ReverseGeocodingOutcome.TransientError(
                providerId = providerId,
                message = "$displayName error: ${errMsg.ifEmpty { "Unknown response error" }}"
            )
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
        val displayNameStr = json.optString("display_name", "").trim()

        if (rawName.isEmpty() && displayNameStr.isEmpty() && addressObj == null) {
            return ReverseGeocodingOutcome.NoResult(
                providerId = providerId,
                attribution = attribution,
                note = "No address or feature data available at this location."
            )
        }

        val streetAddress = listOf(house, road).filter { it.isNotEmpty() }.joinToString(" ")
        val primaryName = rawName
            .ifEmpty { streetAddress }
            .ifEmpty { neighbourhood }
            .ifEmpty { locality }
            .ifEmpty { displayNameStr.substringBefore(",").trim() }
            .ifEmpty { "Selected Location" }

        val localityDisplay = listOf(neighbourhood, locality).filter { it.isNotEmpty() }.distinct().joinToString(", ")
        val composedAddress = listOf(streetAddress, localityDisplay, region, country)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
            .ifEmpty { displayNameStr }

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

        return ReverseGeocodingOutcome.Success(
            providerId = providerId,
            identity = AreaLocationIdentity(
                primaryName = primaryName,
                addressLabel = composedAddress,
                locality = locality.ifEmpty { neighbourhood },
                administrativeRegion = region,
                country = country,
                featureName = if (rawName.isNotEmpty()) rawName else type,
                category = type.ifEmpty { category },
                attribution = attribution
            ),
            geographicDetails = details,
            wikipediaTag = wikipediaTag,
            wikidataId = wikidataId,
            attribution = attribution
        )
    }
}

/**
 * Alternative OSM-compatible reverse geocoding provider supporting Photon GeoJSON `/reverse`
 * (e.g., self-hosted or community Photon endpoints). Handles Photon-specific query formatting,
 * GeoJSON FeatureCollection parsing, empty-feature NoResult handling, and attribution.
 */
class PhotonCompatibleReverseProvider(
    override val config: ReverseGeocodingProviderConfig
) : ReverseGeocodingProvider {

    override val attribution: String =
        config.customAttribution?.takeIf { it.isNotBlank() }
            ?: "© OpenStreetMap contributors (Photon)"

    override fun buildRequestSpec(latitude: Double, longitude: Double): ReverseGeocodingRequestSpec {
        val url = String.format(
            Locale.US,
            "%s/reverse?lat=%.6f&lon=%.6f&limit=1",
            config.baseUrl.trimEnd('/'),
            latitude,
            longitude
        )
        return ReverseGeocodingRequestSpec(
            url = url,
            userAgent = buildUserAgent(config),
            headers = mapOf("Accept" to "application/geo+json, application/json")
        )
    }

    override fun parseSuccessResponse(body: String): ReverseGeocodingOutcome {
        if (body.isBlank()) {
            return ReverseGeocodingOutcome.TransientError(
                providerId = providerId,
                message = "$displayName returned an empty response body."
            )
        }
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return ReverseGeocodingOutcome.TransientError(
                providerId = providerId,
                message = "$displayName returned invalid GeoJSON: ${t.message ?: "parse error"}"
            )
        }

        if (root.has("message") && !root.has("features")) {
            return ReverseGeocodingOutcome.TransientError(
                providerId = providerId,
                message = "$displayName error: ${root.optString("message", "Unknown error")}"
            )
        }

        val features = root.optJSONArray("features")
        if (features == null || features.length() == 0) {
            return ReverseGeocodingOutcome.NoResult(
                providerId = providerId,
                attribution = attribution,
                note = "No nearby named feature found by $displayName."
            )
        }

        val firstProps = features.optJSONObject(0)?.optJSONObject("properties")
            ?: return ReverseGeocodingOutcome.NoResult(
                providerId = providerId,
                attribution = attribution,
                note = "No feature properties returned by $displayName."
            )

        val name = firstProps.optString("name", "").trim()
        val street = firstProps.optString("street", "").trim()
        val houseNumber = firstProps.optString("housenumber", "").trim()
        val district = firstProps.optString("district", "").trim()
        val city = listOf("city", "town", "village", "locality")
            .firstNotNullOfOrNull { k -> firstProps.optString(k, "").trim().takeIf { it.isNotEmpty() } }
            .orEmpty()
        val state = listOf("state", "county")
            .firstNotNullOfOrNull { k -> firstProps.optString(k, "").trim().takeIf { it.isNotEmpty() } }
            .orEmpty()
        val country = firstProps.optString("country", "").trim()
        val osmKey = firstProps.optString("osm_key", "").trim()
        val osmValue = firstProps.optString("osm_value", "").trim()

        val streetLine = listOf(houseNumber, street).filter { it.isNotEmpty() }.joinToString(" ")
        val primaryName = name
            .ifEmpty { streetLine }
            .ifEmpty { district }
            .ifEmpty { city }
            .ifEmpty { "Selected Location" }

        val addressLabel = listOf(streetLine, district, city, state, country)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val details = buildList {
            if (osmKey.isNotEmpty() || osmValue.isNotEmpty()) {
                add("Feature: ${listOf(osmKey, osmValue).filter { it.isNotEmpty() }.joinToString(" / ")}")
            }
            val adminLine = listOf(city, state, country).filter { it.isNotEmpty() }.distinct().joinToString(" • ")
            if (adminLine.isNotEmpty()) {
                add("Administrative area: $adminLine")
            }
        }

        return ReverseGeocodingOutcome.Success(
            providerId = providerId,
            identity = AreaLocationIdentity(
                primaryName = primaryName,
                addressLabel = addressLabel,
                locality = city.ifEmpty { district },
                administrativeRegion = state,
                country = country,
                featureName = name.ifEmpty { osmValue },
                category = osmValue.ifEmpty { osmKey.ifEmpty { "place" } },
                attribution = attribution
            ),
            geographicDetails = details,
            wikipediaTag = null,
            wikidataId = null,
            attribution = attribution
        )
    }
}

private fun buildUserAgent(config: ReverseGeocodingProviderConfig): String {
    val validContact = config.contactInfo?.trim()
        ?.takeIf { ReverseGeocodingContactValidator.isValidGenuineContact(it) }
    return if (validContact != null) {
        "VianMaps/1.0 (Android; local-first mapping; contact=$validContact)"
    } else {
        "VianMaps/1.0 (Android; local-first mapping)"
    }
}

/**
 * Registry and selector for reverse geocoding providers.
 *
 * Architecture honesty note:
 * - No remote configuration service (such as Firebase Remote Config or a backend config API)
 *   exists in this project yet. Therefore `supportsRemoteDynamicSwitching` is explicitly `false`:
 *   switching endpoints without an app update is not active.
 * - No central proxy/rate-limiting backend exists in this project (`hasCentralRateLimitingGateway = false`),
 *   so public `nominatim.openstreetmap.org` remains disabled for production use by default.
 */
class ReverseGeocodingProviderRegistry(
    configs: List<ReverseGeocodingProviderConfig> = listOf(
        ReverseGeocodingProviderConfig.publicNominatimProductionDefault(),
        ReverseGeocodingProviderConfig.disabledConfig()
    ),
    initialProviderId: String = ReverseGeocodingProviderConfig.PUBLIC_NOMINATIM_ID
) {
    val supportsRemoteDynamicSwitching: Boolean = false
    val hasCentralRateLimitingGateway: Boolean = false

    private val providers = LinkedHashMap<String, ReverseGeocodingProvider>()
    @Volatile
    private var currentProviderId: String = initialProviderId

    init {
        configs.forEach { registerConfig(it) }
        if (!providers.containsKey(currentProviderId) && providers.isNotEmpty()) {
            currentProviderId = providers.keys.first()
        }
    }

    fun registerConfig(config: ReverseGeocodingProviderConfig) {
        synchronized(providers) {
            providers[config.providerId] = instantiateProvider(config)
        }
    }

    fun selectProvider(providerId: String): Boolean {
        synchronized(providers) {
            if (!providers.containsKey(providerId)) return false
            currentProviderId = providerId
            return true
        }
    }

    fun activeProvider(): ReverseGeocodingProvider {
        synchronized(providers) {
            return providers[currentProviderId] ?: DisabledReverseGeocodingProvider()
        }
    }

    fun activeProviderId(): String = currentProviderId

    fun activeCacheKeySegment(): String {
        val provider = activeProvider()
        val urlSegment = provider.config.baseUrl.trimEnd('/').lowercase(Locale.US)
        return "${provider.providerId}@${urlSegment.hashCode()}"
    }

    fun registeredProviderIds(): List<String> = synchronized(providers) { providers.keys.toList() }

    private fun instantiateProvider(config: ReverseGeocodingProviderConfig): ReverseGeocodingProvider {
        return when (config.providerType) {
            ReverseGeocodingProviderType.DISABLED_NONE -> DisabledReverseGeocodingProvider(config)
            ReverseGeocodingProviderType.PUBLIC_NOMINATIM,
            ReverseGeocodingProviderType.NOMINATIM_COMPATIBLE -> NominatimCompatibleReverseProvider(config)
            ReverseGeocodingProviderType.PHOTON_COMPATIBLE -> PhotonCompatibleReverseProvider(config)
        }
    }

    companion object {
        fun createDefault(): ReverseGeocodingProviderRegistry = ReverseGeocodingProviderRegistry()
    }
}
