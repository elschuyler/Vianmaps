package com.example.data.osm

import com.example.data.db.OsmPoiRecord
import kotlin.math.abs

/**
 * Category-Based POI Highlight definitions, MVT-backed category mappings,
 * viewport bounding-box geometry helpers (including rotation and antimeridian splits),
 * and zoom pause constants.
 */
object PoiCategoryHighlightDefaults {
    /**
     * Initial zoom threshold below which category highlighting pauses automatically.
     *
     * Note: This is intentionally distinct from VisibleTileCoordinator's z=14 vector-tile
     * acquisition cap (tiles acquired at z14 continue to supply POIs at z14..z22+).
     * Tune this threshold during physical-device QA if urban POI density feels too busy
     * at z13.0 or too narrow if pushed up to z14.0.
     */
    const val MIN_POI_HIGHLIGHT_ZOOM = 13.0
    const val CAMERA_SETTLE_DEBOUNCE_MS = 750L
    const val SELECTION_CHANGE_DEBOUNCE_MS = 80L
    const val TILE_INGESTION_COALESCE_MS = 150L
}

enum class PoiHighlightCategory(
    val id: String,
    val displayName: String,
    val markerColorHex: String,
    val sqlTokens: Set<String>,
    val caveatNote: String? = null
) {
    SHOP(
        id = "shop",
        displayName = "Shop",
        markerColorHex = "#6366F1",
        sqlTokens = setOf("shop")
    ),
    GROCERY(
        id = "grocery",
        displayName = "Grocery",
        markerColorHex = "#10B981",
        sqlTokens = setOf("grocery", "supermarket", "convenience", "greengrocer", "deli")
    ),
    RESTAURANT(
        id = "restaurant",
        displayName = "Restaurant",
        markerColorHex = "#F59E0B",
        sqlTokens = setOf("restaurant")
    ),
    CAFE(
        id = "cafe",
        displayName = "Cafe",
        markerColorHex = "#14B8A6",
        sqlTokens = setOf("cafe")
    ),
    PHARMACY(
        id = "pharmacy",
        displayName = "Pharmacy",
        markerColorHex = "#F43F5E",
        sqlTokens = setOf("pharmacy")
    ),
    HOSPITAL(
        id = "hospital",
        displayName = "Hospital",
        markerColorHex = "#DC2626",
        sqlTokens = setOf("hospital"),
        caveatNote = "Excludes pharmacies tagged under hospital class"
    ),
    BUS_STOP(
        id = "bus_stop",
        displayName = "Bus Stop",
        markerColorHex = "#8B5CF6",
        sqlTokens = setOf("bus_stop", "bus_station", "bus")
    ),
    ATM(
        id = "atm",
        displayName = "ATM",
        markerColorHex = "#0EA5E9",
        sqlTokens = setOf("atm"),
        caveatNote = "Standalone ATM features only; ATMs embedded inside bank nodes are not separated in vector tiles"
    ),
    FUEL(
        id = "fuel",
        displayName = "Fuel",
        markerColorHex = "#EA580C",
        sqlTokens = setOf("fuel")
    ),
    SCHOOL(
        id = "school",
        displayName = "School",
        markerColorHex = "#EC4899",
        sqlTokens = setOf("school")
    ),
    PARKING(
        id = "parking",
        displayName = "Parking",
        markerColorHex = "#475569",
        sqlTokens = setOf("parking")
    );

    fun matches(poi: OsmPoiRecord): Boolean {
        val cat = poi.category.trim().lowercase()
        val sub = poi.subCategory?.trim()?.lowercase().orEmpty()
        return when (this) {
            SHOP -> cat == "shop" || sub == "shop"
            GROCERY -> cat == "grocery" || sub in GROCERY_SUB_TOKENS
            RESTAURANT -> cat == "restaurant" || sub == "restaurant"
            CAFE -> cat == "cafe" || sub == "cafe"
            // OpenMapTiles frequently encodes pharmacies as class="hospital", subclass="pharmacy".
            PHARMACY -> cat == "pharmacy" || sub == "pharmacy"
            // Do not misclassify pharmacies (or unrelated medical subclasses) grouped under hospital class.
            HOSPITAL -> sub == "hospital" || (cat == "hospital" && (sub.isEmpty() || sub == "hospital"))
            BUS_STOP -> sub == "bus_stop" || sub == "bus_station" ||
                (cat == "bus" && (sub.isEmpty() || sub == "bus_stop" || sub == "bus_station"))
            ATM -> cat == "atm" || sub == "atm"
            FUEL -> cat == "fuel" || sub == "fuel"
            SCHOOL -> sub == "school" || (cat == "school" && (sub.isEmpty() || sub == "school"))
            PARKING -> cat == "parking" || sub == "parking"
        }
    }

    companion object {
        private val GROCERY_SUB_TOKENS = setOf("grocery", "supermarket", "convenience", "greengrocer", "deli")

        fun fromId(id: String): PoiHighlightCategory? {
            return entries.firstOrNull { it.id == id }
        }

        fun collectSqlTokens(selectedCategories: Collection<PoiHighlightCategory>): List<String> {
            if (selectedCategories.isEmpty()) return emptyList()
            return selectedCategories.flatMap { it.sqlTokens }.distinct().sorted()
        }

        fun matchSelectedCategories(
            poi: OsmPoiRecord,
            selectedCategories: List<PoiHighlightCategory>
        ): List<PoiHighlightCategory> {
            return selectedCategories.filter { it.matches(poi) }
        }
    }
}

data class CategoryHighlightedPoi(
    val poi: OsmPoiRecord,
    val primaryCategory: PoiHighlightCategory,
    val matchedCategoryIds: List<String>
)

data class PoiLatLngCorner(
    val latitude: Double,
    val longitude: Double
)

/**
 * Geographic viewport bounding box preserving antimeridian crossing when [westLng] > [eastLng].
 */
data class PoiViewportBounds(
    val southLat: Double,
    val northLat: Double,
    val westLng: Double,
    val eastLng: Double
) {
    val crossesAntimeridian: Boolean
        get() = westLng > eastLng

    fun isValid(): Boolean {
        return southLat.isFinite() &&
            northLat.isFinite() &&
            westLng.isFinite() &&
            eastLng.isFinite() &&
            southLat <= northLat &&
            southLat >= -90.0 &&
            northLat <= 90.0 &&
            westLng in -180.0..180.0 &&
            eastLng in -180.0..180.0
    }

    /**
     * Returns 1 contiguous longitude span `[westLng, eastLng]` normally, or 2 spans
     * `[westLng, 180.0]` and `[-180.0, eastLng]` when crossing the antimeridian.
     */
    fun longitudeRanges(): List<Pair<Double, Double>> {
        return if (crossesAntimeridian) {
            listOf(westLng to 180.0, -180.0 to eastLng)
        } else {
            listOf(westLng to eastLng)
        }
    }

    companion object {
        fun normalizeLongitude(lng: Double): Double {
            if (!lng.isFinite()) return 0.0
            var normalized = ((lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            if (abs(normalized - (-180.0)) < 1e-9 && lng > 0.0) {
                normalized = 180.0
            }
            return if (normalized == -0.0) 0.0 else normalized
        }

        /**
         * Computes an axis-aligned viewport bounding box enclosing all four screen corners
         * (handling rotated viewports correctly) and detecting ±180° antimeridian wrapping.
         */
        fun fromCorners(corners: List<PoiLatLngCorner>): PoiViewportBounds? {
            val valid = corners.filter {
                it.latitude.isFinite() &&
                    it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0
            }
            if (valid.isEmpty()) return null

            val south = valid.minOf { it.latitude }.coerceIn(-90.0, 90.0)
            val north = valid.maxOf { it.latitude }.coerceIn(-90.0, 90.0)
            val normLngs = valid.map { normalizeLongitude(it.longitude) }
            val rawMin = normLngs.min()
            val rawMax = normLngs.max()

            return if (rawMax - rawMin <= 180.0) {
                PoiViewportBounds(
                    southLat = south,
                    northLat = north,
                    westLng = rawMin,
                    eastLng = rawMax
                )
            } else {
                // Antimeridian crossing: shift negative longitudes by +360° into contiguous [0, 360+)
                val shifted = normLngs.map { if (it < 0.0) it + 360.0 else it }
                val west = normalizeLongitude(shifted.min())
                val east = normalizeLongitude(shifted.max())
                PoiViewportBounds(
                    southLat = south,
                    northLat = north,
                    westLng = west,
                    eastLng = east
                )
            }
        }
    }
}
