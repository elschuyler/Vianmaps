package com.example.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "retained_tiles",
    primaryKeys = ["providerId", "category", "z", "x", "y"]
)
data class RetainedTileEntity(
    val providerId: String,
    val category: String,
    val z: Int,
    val x: Int,
    val y: Int,
    val filePath: String,
    val byteSize: Long,
    val checksum: String,
    val fetchTimestamp: Long = System.currentTimeMillis(),
    val etag: String? = null,
    val sourceUrl: String = "",
    val attribution: String = "",
    val retentionPermission: String = "PERMITTED",
    val integrityStatus: String = "VALID"
)

@Dao
interface RetainedTileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTile(tile: RetainedTileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTiles(tiles: List<RetainedTileEntity>)

    @Query("SELECT * FROM retained_tiles WHERE providerId = :providerId AND category = :category AND z = :z AND x = :x AND y = :y LIMIT 1")
    suspend fun getTile(providerId: String, category: String, z: Int, x: Int, y: Int): RetainedTileEntity?

    @Query("SELECT * FROM retained_tiles ORDER BY fetchTimestamp DESC")
    fun getAllTilesFlow(): Flow<List<RetainedTileEntity>>

    @Query("SELECT COUNT(*) FROM retained_tiles")
    suspend fun getTileCount(): Int

    @Query("SELECT SUM(byteSize) FROM retained_tiles")
    suspend fun getTotalBytes(): Long?

    @Query("SELECT COUNT(*) FROM retained_tiles WHERE category = :category")
    suspend fun getTileCountByCategory(category: String): Int

    @Query("DELETE FROM retained_tiles WHERE providerId = :providerId AND category = :category AND z = :z AND x = :x AND y = :y")
    suspend fun deleteTile(providerId: String, category: String, z: Int, x: Int, y: Int)

    @Query("SELECT COUNT(*) FROM retained_tiles WHERE providerId = :providerId")
    suspend fun getTileCountByProvider(providerId: String): Int

    @Query("SELECT SUM(byteSize) FROM retained_tiles WHERE providerId = :providerId")
    suspend fun getBytesByProvider(providerId: String): Long?

    @Query("DELETE FROM retained_tiles WHERE providerId = :providerId")
    suspend fun deleteTilesByProvider(providerId: String): Int

    @Query("SELECT DISTINCT providerId FROM retained_tiles")
    suspend fun getAllProviderIds(): List<String>

    @Query("SELECT * FROM retained_tiles WHERE providerId = :providerId")
    suspend fun getTilesByProvider(providerId: String): List<RetainedTileEntity>
}

@Entity(tableName = "osm_pois")
data class OsmPoiRecord(
    @PrimaryKey val osmId: Long,
    val name: String,
    val category: String,
    val subCategory: String? = null,
    val latitude: Double,
    val longitude: Double,
    val sourceTileZ: Int,
    val sourceTileX: Int,
    val sourceTileY: Int,
    val tagsJson: String = "{}",
    val layerName: String = "poi",
    val fetchTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface OsmPoiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPois(pois: List<OsmPoiRecord>)

    @Query("SELECT * FROM osm_pois WHERE name LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY name ASC LIMIT :limit")
    suspend fun searchPois(query: String, limit: Int = 20): List<OsmPoiRecord>

    @Query("SELECT * FROM osm_pois WHERE (name LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%') AND category = :category ORDER BY name ASC LIMIT :limit")
    suspend fun searchPoisByCategory(query: String, category: String, limit: Int = 20): List<OsmPoiRecord>

    @Query("SELECT COUNT(*) FROM osm_pois")
    suspend fun getPoiCount(): Int

    @Query("SELECT * FROM osm_pois WHERE osmId = :osmId LIMIT 1")
    suspend fun getPoiById(osmId: Long): OsmPoiRecord?

    @Query("SELECT * FROM osm_pois ORDER BY name ASC LIMIT :limit")
    suspend fun getAllPois(limit: Int = 100): List<OsmPoiRecord>
}

@Entity(tableName = "osm_buildings")
data class OsmBuildingRecord(
    @PrimaryKey val osmId: Long,
    val name: String? = null,
    val buildingType: String = "yes",
    val heightMeters: Float = 0f,
    val minHeightMeters: Float = 0f,
    val sourceTileZ: Int,
    val sourceTileX: Int,
    val sourceTileY: Int,
    val centroidLat: Double = 0.0,
    val centroidLng: Double = 0.0,
    val geometryJson: String = "[]"
)

@Dao
interface OsmBuildingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuildings(buildings: List<OsmBuildingRecord>)

    @Query("SELECT COUNT(*) FROM osm_buildings")
    suspend fun getBuildingCount(): Int
}

@Entity(tableName = "osm_regions")
data class OsmRegionRecord(
    @PrimaryKey val osmId: Long,
    val name: String,
    val adminLevel: Int = 8,
    val boundaryType: String = "administrative",
    val sourceTileZ: Int,
    val sourceTileX: Int,
    val sourceTileY: Int
)

@Dao
interface OsmRegionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegions(regions: List<OsmRegionRecord>)

    @Query("SELECT COUNT(*) FROM osm_regions")
    suspend fun getRegionCount(): Int
}

@Entity(tableName = "osm_roads")
data class OsmRoadRecord(
    @PrimaryKey val osmId: Long,
    val name: String? = null,
    val highwayClass: String,
    val isOneway: Boolean = false,
    val sourceTileZ: Int,
    val sourceTileX: Int,
    val sourceTileY: Int,
    val geometryJson: String = "[]"
)

@Dao
interface OsmRoadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoads(roads: List<OsmRoadRecord>)

    @Query("SELECT COUNT(*) FROM osm_roads")
    suspend fun getRoadCount(): Int
}

@Entity(
    tableName = "routing_graph_tiles",
    primaryKeys = ["level", "tileIndex"]
)
data class RoutingGraphTileRecord(
    val level: Int,
    val tileIndex: Long,
    val status: String = "NOT_CONFIGURED",
    val filePath: String? = null,
    val byteSize: Long = 0L,
    val lastChecked: Long = System.currentTimeMillis()
)

@Dao
interface RoutingGraphTileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGraphTiles(tiles: List<RoutingGraphTileRecord>)

    @Query("SELECT * FROM routing_graph_tiles WHERE level = :level AND tileIndex = :tileIndex LIMIT 1")
    suspend fun getGraphTile(level: Int, tileIndex: Long): RoutingGraphTileRecord?

    @Query("SELECT COUNT(*) FROM routing_graph_tiles WHERE status = 'DOWNLOADED'")
    suspend fun getDownloadedTileCount(): Int
}

@Database(
    entities = [
        RetainedTileEntity::class,
        OsmPoiRecord::class,
        OsmBuildingRecord::class,
        OsmRegionRecord::class,
        OsmRoadRecord::class,
        RoutingGraphTileRecord::class
    ],
    version = AppDatabaseContract.CURRENT_VERSION,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun retainedTileDao(): RetainedTileDao
    abstract fun osmPoiDao(): OsmPoiDao
    abstract fun osmBuildingDao(): OsmBuildingDao
    abstract fun osmRegionDao(): OsmRegionDao
    abstract fun osmRoadDao(): OsmRoadDao
    abstract fun routingGraphTileDao(): RoutingGraphTileDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppDatabaseContract.DATABASE_NAME
                )
                .fallbackToDestructiveMigration(false)
                .build().also { instance = it }
            }
        }
    }
}
