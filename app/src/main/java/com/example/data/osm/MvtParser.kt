package com.example.data.osm

import com.example.data.db.OsmBuildingRecord
import com.example.data.db.OsmPoiRecord
import com.example.data.db.OsmRegionRecord
import com.example.data.db.OsmRoadRecord
import com.example.data.tile.TileCoord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

data class MvtDecodedTile(
    val pois: List<OsmPoiRecord>,
    val buildings: List<OsmBuildingRecord>,
    val regions: List<OsmRegionRecord>,
    val roads: List<OsmRoadRecord>
)

/**
 * Lightweight, zero-dependency pure Kotlin MVT (Mapbox Vector Tile) protocol buffer parser.
 * Safely decodes gzip-compressed and raw PBF tiles into canonical OSM domain entities
 * with tile-relative coordinate normalization to WGS84 lat/lng.
 */
object MvtParser {

    fun parseTile(data: ByteArray, tileCoord: TileCoord): MvtDecodedTile {
        val decompressed = decompressIfNeeded(data)
        val pois = mutableListOf<OsmPoiRecord>()
        val buildings = mutableListOf<OsmBuildingRecord>()
        val regions = mutableListOf<OsmRegionRecord>()
        val roads = mutableListOf<OsmRoadRecord>()

        var pos = 0
        while (pos < decompressed.size) {
            val (tagWire, nextPos) = readVarint(decompressed, pos)
            pos = nextPos
            val tag = (tagWire ushr 3).toInt()
            val wire = (tagWire and 7L).toInt()

            if (wire == 2) {
                val (lenLong, bodyPos) = readVarint(decompressed, pos)
                val len = lenLong.toInt()
                pos = bodyPos + len
                if (tag == 3) { // Layer
                    parseLayer(decompressed, bodyPos, len, tileCoord, pois, buildings, regions, roads)
                }
            } else if (wire == 0) {
                val (_, skipPos) = readVarint(decompressed, pos)
                pos = skipPos
            } else {
                break
            }
        }

        return MvtDecodedTile(pois, buildings, regions, roads)
    }

    private fun decompressIfNeeded(data: ByteArray): ByteArray {
        if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            return try {
                GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    var read: Int
                    while (gzip.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                    out.toByteArray()
                }
            } catch (e: Throwable) {
                data
            }
        }
        return data
    }

    private fun parseLayer(
        data: ByteArray,
        start: Int,
        length: Int,
        tileCoord: TileCoord,
        pois: MutableList<OsmPoiRecord>,
        buildings: MutableList<OsmBuildingRecord>,
        regions: MutableList<OsmRegionRecord>,
        roads: MutableList<OsmRoadRecord>
    ) {
        val end = start + length
        var pos = start
        var layerName = ""
        var extent = 4096
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any>()
        val rawFeatures = mutableListOf<RawFeature>()

        while (pos < end) {
            val (tagWire, nextPos) = readVarint(data, pos)
            pos = nextPos
            val tag = (tagWire ushr 3).toInt()
            val wire = (tagWire and 7L).toInt()

            if (wire == 2) {
                val (lenLong, bodyPos) = readVarint(data, pos)
                val len = lenLong.toInt()
                pos = bodyPos + len
                when (tag) {
                    1 -> layerName = String(data, bodyPos, len, StandardCharsets.UTF_8)
                    3 -> keys.add(String(data, bodyPos, len, StandardCharsets.UTF_8))
                    4 -> parseValue(data, bodyPos, len)?.let { values.add(it) }
                    2 -> rawFeatures.add(parseRawFeature(data, bodyPos, len))
                }
            } else if (wire == 0) {
                val (value, valPos) = readVarint(data, pos)
                pos = valPos
                if (tag == 5) extent = value.toInt() // extent
            } else {
                break
            }
        }

        // Convert raw features based on layer type
        for (rf in rawFeatures) {
            val tagsMap = mutableMapOf<String, String>()
            var i = 0
            while (i + 1 < rf.tags.size) {
                val kIdx = rf.tags[i]
                val vIdx = rf.tags[i + 1]
                if (kIdx in keys.indices && vIdx in values.indices) {
                    tagsMap[keys[kIdx]] = values[vIdx].toString()
                }
                i += 2
            }

            val stableId = rf.id.takeIf { it > 0 } ?: generateFallbackId(tileCoord, layerName, rf.type, tagsMap)

            when (layerName) {
                "poi" -> {
                    val name = tagsMap["name"] ?: tagsMap["name_en"] ?: tagsMap["class"] ?: "POI"
                    val category = tagsMap["class"] ?: tagsMap["subclass"] ?: "general"
                    val (lat, lng) = decodeFirstPoint(rf.geometry, extent, tileCoord)
                    pois.add(
                        OsmPoiRecord(
                            osmId = stableId,
                            name = name,
                            category = category,
                            subCategory = tagsMap["subclass"],
                            latitude = lat,
                            longitude = lng,
                            sourceTileZ = tileCoord.z,
                            sourceTileX = tileCoord.x,
                            sourceTileY = tileCoord.y,
                            tagsJson = tagsMap.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\"" },
                            layerName = layerName
                        )
                    )
                }
                "building" -> {
                    val height = tagsMap["render_height"]?.toFloatOrNull() ?: tagsMap["height"]?.toFloatOrNull() ?: 0f
                    val minHeight = tagsMap["render_min_height"]?.toFloatOrNull() ?: 0f
                    val (lat, lng) = decodeFirstPoint(rf.geometry, extent, tileCoord)
                    buildings.add(
                        OsmBuildingRecord(
                            osmId = stableId,
                            name = tagsMap["name"],
                            buildingType = tagsMap["building"] ?: "yes",
                            heightMeters = height,
                            minHeightMeters = minHeight,
                            sourceTileZ = tileCoord.z,
                            sourceTileX = tileCoord.x,
                            sourceTileY = tileCoord.y,
                            centroidLat = lat,
                            centroidLng = lng
                        )
                    )
                }
                "boundary" -> {
                    val name = tagsMap["name"] ?: tagsMap["name_en"] ?: "Boundary"
                    val adminLevel = tagsMap["admin_level"]?.toIntOrNull() ?: 8
                    regions.add(
                        OsmRegionRecord(
                            osmId = stableId,
                            name = name,
                            adminLevel = adminLevel,
                            boundaryType = tagsMap["boundary"] ?: "administrative",
                            sourceTileZ = tileCoord.z,
                            sourceTileX = tileCoord.x,
                            sourceTileY = tileCoord.y
                        )
                    )
                }
                "transportation", "transportation_name" -> {
                    val highwayClass = tagsMap["class"] ?: tagsMap["highway"] ?: "road"
                    val oneway = tagsMap["oneway"] == "1" || tagsMap["oneway"] == "true"
                    roads.add(
                        OsmRoadRecord(
                            osmId = stableId,
                            name = tagsMap["name"] ?: tagsMap["ref"],
                            highwayClass = highwayClass,
                            isOneway = oneway,
                            sourceTileZ = tileCoord.z,
                            sourceTileX = tileCoord.x,
                            sourceTileY = tileCoord.y
                        )
                    )
                }
            }
        }
    }

    private data class RawFeature(
        val id: Long,
        val tags: List<Int>,
        val type: Int,
        val geometry: List<Int>
    )

    private fun parseRawFeature(data: ByteArray, start: Int, length: Int): RawFeature {
        val end = start + length
        var pos = start
        var id = 0L
        val tags = mutableListOf<Int>()
        var type = 0
        val geometry = mutableListOf<Int>()

        while (pos < end) {
            val (tagWire, nextPos) = readVarint(data, pos)
            pos = nextPos
            val tag = (tagWire ushr 3).toInt()
            val wire = (tagWire and 7L).toInt()

            when (wire) {
                0 -> {
                    val (v, vPos) = readVarint(data, pos)
                    pos = vPos
                    if (tag == 1) id = v
                    else if (tag == 3) type = v.toInt()
                }
                2 -> {
                    val (lenLong, bodyPos) = readVarint(data, pos)
                    val len = lenLong.toInt()
                    pos = bodyPos + len
                    if (tag == 2) { // packed tags
                        var tPos = bodyPos
                        while (tPos < bodyPos + len) {
                            val (tVal, nextT) = readVarint(data, tPos)
                            tags.add(tVal.toInt())
                            tPos = nextT
                        }
                    } else if (tag == 4) { // packed geometry
                        var gPos = bodyPos
                        while (gPos < bodyPos + len) {
                            val (gVal, nextG) = readVarint(data, gPos)
                            geometry.add(gVal.toInt())
                            gPos = nextG
                        }
                    }
                }
                else -> break
            }
        }
        return RawFeature(id, tags, type, geometry)
    }

    private fun parseValue(data: ByteArray, start: Int, length: Int): Any? {
        val end = start + length
        var pos = start
        while (pos < end) {
            val (tagWire, nextPos) = readVarint(data, pos)
            pos = nextPos
            val tag = (tagWire ushr 3).toInt()
            val wire = (tagWire and 7L).toInt()
            when (wire) {
                2 -> {
                    val (lenLong, bodyPos) = readVarint(data, pos)
                    val len = lenLong.toInt()
                    pos = bodyPos + len
                    if (tag == 1) return String(data, bodyPos, len, StandardCharsets.UTF_8)
                }
                0 -> {
                    val (v, valPos) = readVarint(data, pos)
                    pos = valPos
                    return when (tag) {
                        5 -> v // int_value
                        6 -> v // uint_value
                        7 -> (v ushr 1) xor -(v and 1L) // sint_value
                        else -> v
                    }
                }
                else -> break
            }
        }
        return null
    }

    private fun decodeFirstPoint(geom: List<Int>, extent: Int, tile: TileCoord): Pair<Double, Double> {
        var cursorX = 0
        var cursorY = 0
        var idx = 0
        while (idx < geom.size) {
            val cmdInt = geom[idx++]
            val cmd = cmdInt and 7
            val count = cmdInt ushr 3
            if (cmd == 1 && count > 0 && idx + 1 < geom.size) { // MoveTo
                val dx = (geom[idx] ushr 1) xor -(geom[idx] and 1)
                val dy = (geom[idx + 1] ushr 1) xor -(geom[idx + 1] and 1)
                cursorX += dx
                cursorY += dy
                return tilePointToLatLng(cursorX, cursorY, extent, tile)
            }
        }
        // Fallback to tile center
        return tilePointToLatLng(extent / 2, extent / 2, extent, tile)
    }

    fun tilePointToLatLng(px: Int, py: Int, extent: Int, tile: TileCoord): Pair<Double, Double> {
        val n = 1 shl tile.z
        val westLng = tile.x.toDouble() / n * 360.0 - 180.0
        val eastLng = (tile.x + 1).toDouble() / n * 360.0 - 180.0
        val lng = westLng + (px.toDouble() / extent) * (eastLng - westLng)

        val yNorm = (tile.y.toDouble() + py.toDouble() / extent) / n
        val rad = PI * (1.0 - 2.0 * yNorm)
        val lat = Math.toDegrees(atan(sinh(rad)))
        return Pair(lat, lng)
    }

    private fun generateFallbackId(tile: TileCoord, layer: String, type: Int, tags: Map<String, String>): Long {
        val hash = (tile.toString() + layer + type + (tags["name"] ?: "")).hashCode().toLong()
        return (1L shl 62) or (hash and 0x3FFFFFFFFFFFFFFFL)
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var res = 0L
        var shift = 0
        var pos = start
        while (pos < buf.size) {
            val b = buf[pos++].toLong()
            res = res or ((b and 0x7FL) shl shift)
            if ((b and 0x80L) == 0L) break
            shift += 7
        }
        return Pair(res, pos)
    }
}
