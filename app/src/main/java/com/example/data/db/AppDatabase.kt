package com.example.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

data class CategoryCountTuple(
    val category: String,
    val count: Int
)

@Dao
interface OsmPoiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPois(pois: List<OsmPoiRecord>)

    @Query("""
        SELECT * FROM osm_pois
        WHERE name LIKE '%' || :escapedQuery || '%' ESCAPE '\'
           OR category LIKE '%' || :escapedQuery || '%' ESCAPE '\'
           OR COALESCE(subCategory, '') LIKE '%' || :escapedQuery || '%' ESCAPE '\'
        ORDER BY
            CASE
                WHEN LOWER(name) = LOWER(:rawQuery) THEN 0
                WHEN LOWER(name) LIKE LOWER(:escapedQuery) || '%' ESCAPE '\' THEN 1
                ELSE 2
            END,
            name ASC
        LIMIT :limit
    """)
    suspend fun searchPois(escapedQuery: String, rawQuery: String = escapedQuery, limit: Int = 20): List<OsmPoiRecord>

    @Query("""
        SELECT * FROM osm_pois
        WHERE (LOWER(category) = LOWER(:category) OR LOWER(COALESCE(subCategory, '')) = LOWER(:category))
          AND (name LIKE '%' || :escapedQuery || '%' ESCAPE '\'
               OR category LIKE '%' || :escapedQuery || '%' ESCAPE '\'
               OR COALESCE(subCategory, '') LIKE '%' || :escapedQuery || '%' ESCAPE '\')
        ORDER BY
            CASE
                WHEN LOWER(name) = LOWER(:rawQuery) THEN 0
                WHEN LOWER(name) LIKE LOWER(:escapedQuery) || '%' ESCAPE '\' THEN 1
                ELSE 2
            END,
            name ASC
        LIMIT :limit
    """)
    suspend fun searchPoisByCategory(
        escapedQuery: String,
        category: String,
        rawQuery: String = escapedQuery,
        limit: Int = 20
    ): List<OsmPoiRecord>

    @Query("""
        SELECT * FROM osm_pois
        WHERE LOWER(category) = LOWER(:category)
           OR LOWER(COALESCE(subCategory, '')) = LOWER(:category)
        ORDER BY name ASC
        LIMIT :limit
    """)
    suspend fun getPoisByCategoryOnly(category: String, limit: Int = 20): List<OsmPoiRecord>

    @Query("""
        SELECT LOWER(category) AS category, COUNT(*) AS count
        FROM osm_pois
        WHERE TRIM(category) != ''
        GROUP BY LOWER(category)
        ORDER BY count DESC, category ASC
        LIMIT :limit
    """)
    suspend fun getCategoryCounts(limit: Int = 25): List<CategoryCountTuple>

    @Query("SELECT COUNT(*) FROM osm_pois")
    suspend fun getPoiCount(): Int

    @Query("SELECT * FROM osm_pois WHERE osmId = :osmId LIMIT 1")
    suspend fun getPoiById(osmId: Long): OsmPoiRecord?

    @Query("SELECT * FROM osm_pois ORDER BY name ASC LIMIT :limit")
    suspend fun getAllPois(limit: Int = 100): List<OsmPoiRecord>

    @Query("DELETE FROM osm_pois")
    suspend fun clearAllPois()
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

@Entity(
    tableName = "user_places",
    indices = [
        Index(value = ["isPlacemark"]),
        Index(value = ["updatedAt"])
    ]
)
data class UserPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val description: String = "",
    val addressLabel: String? = null,
    val category: String = "placemark",
    val tagsCsv: String = "",
    val isPlacemark: Boolean = true,
    val linkedModuleRef: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface UserPlaceDao {
    @Query("SELECT * FROM user_places WHERE isPlacemark = 1 ORDER BY updatedAt DESC")
    fun observePlacemarks(): Flow<List<UserPlaceEntity>>

    @Query("SELECT * FROM user_places ORDER BY updatedAt DESC")
    fun observeAllPlaces(): Flow<List<UserPlaceEntity>>

    @Query("SELECT * FROM user_places WHERE id = :id LIMIT 1")
    suspend fun getPlaceById(id: String): UserPlaceEntity?

    @Query("SELECT * FROM user_places WHERE isPlacemark = 1 ORDER BY updatedAt DESC")
    suspend fun getAllPlacemarksOnce(): List<UserPlaceEntity>

    @Query(
        """
        SELECT * FROM user_places
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLng AND :maxLng
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getNearbyPlaces(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<UserPlaceEntity>

    @Query("SELECT COUNT(*) FROM user_places WHERE isPlacemark = 1")
    suspend fun getPlacemarkCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlace(place: UserPlaceEntity)

    @Query("DELETE FROM user_places WHERE id = :id")
    suspend fun deletePlaceById(id: String): Int

    @Query("DELETE FROM user_places")
    suspend fun clearAllPlaces()
}

@Entity(
    tableName = "saved_routes",
    indices = [Index(value = ["updatedAt"])]
)
data class SavedRouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val geometryJson: String,
    val pointCount: Int,
    val totalDistanceMeters: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "route_waypoints",
    foreignKeys = [
        ForeignKey(
            entity = SavedRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserPlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["placeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["routeId"]),
        Index(value = ["placeId"])
    ]
)
data class RouteWaypointEntity(
    @PrimaryKey val id: String,
    val routeId: String,
    val placeId: String? = null,
    val orderIndex: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class SavedRouteWithWaypoints(
    @Embedded val route: SavedRouteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "routeId"
    )
    val waypoints: List<RouteWaypointEntity>
)

@Dao
interface SavedRouteDao {
    @Transaction
    @Query("SELECT * FROM saved_routes ORDER BY updatedAt DESC")
    fun observeRoutesWithWaypoints(): Flow<List<SavedRouteWithWaypoints>>

    @Transaction
    @Query("SELECT * FROM saved_routes WHERE id = :routeId LIMIT 1")
    suspend fun getRouteWithWaypoints(routeId: String): SavedRouteWithWaypoints?

    @Query("SELECT COUNT(*) FROM saved_routes")
    suspend fun getRouteCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(route: SavedRouteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoints(waypoints: List<RouteWaypointEntity>)

    @Query("DELETE FROM route_waypoints WHERE routeId = :routeId")
    suspend fun deleteWaypointsForRoute(routeId: String)

    @Query("DELETE FROM saved_routes WHERE id = :routeId")
    suspend fun deleteRouteById(routeId: String): Int

    @Query("DELETE FROM saved_routes")
    suspend fun clearAllRoutes()

    @Transaction
    suspend fun saveRouteWithWaypoints(
        route: SavedRouteEntity,
        waypoints: List<RouteWaypointEntity>
    ) {
        upsertRoute(route)
        deleteWaypointsForRoute(route.id)
        if (waypoints.isNotEmpty()) {
            insertWaypoints(waypoints)
        }
    }
}

@Database(
    entities = [
        RetainedTileEntity::class,
        OsmPoiRecord::class,
        OsmBuildingRecord::class,
        OsmRegionRecord::class,
        OsmRoadRecord::class,
        RoutingGraphTileRecord::class,
        UserPlaceEntity::class,
        SavedRouteEntity::class,
        RouteWaypointEntity::class
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
    abstract fun userPlaceDao(): UserPlaceDao
    abstract fun savedRouteDao(): SavedRouteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_places` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `description` TEXT NOT NULL,
                        `addressLabel` TEXT,
                        `category` TEXT NOT NULL,
                        `tagsCsv` TEXT NOT NULL,
                        `isPlacemark` INTEGER NOT NULL,
                        `linkedModuleRef` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_places_isPlacemark` ON `user_places` (`isPlacemark`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_places_updatedAt` ON `user_places` (`updatedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_routes` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `geometryJson` TEXT NOT NULL,
                        `pointCount` INTEGER NOT NULL,
                        `totalDistanceMeters` REAL NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_routes_updatedAt` ON `saved_routes` (`updatedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `route_waypoints` (
                        `id` TEXT NOT NULL,
                        `routeId` TEXT NOT NULL,
                        `placeId` TEXT,
                        `orderIndex` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `notes` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`routeId`) REFERENCES `saved_routes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`placeId`) REFERENCES `user_places`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_waypoints_routeId` ON `route_waypoints` (`routeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_waypoints_placeId` ON `route_waypoints` (`placeId`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppDatabaseContract.DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(false)
                    .build().also { instance = it }
            }
        }
    }
}
