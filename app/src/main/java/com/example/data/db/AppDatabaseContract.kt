package com.example.data.db

/**
 * Shared Database Architecture Boundary for Vian Maps local-first modules.
 *
 * All structured user-owned records (Places, Placemarks, Saved Routes, Waypoints)
 * share this single persistent SQLite database alongside cached OSM feature indices,
 * keeping user records cleanly separated from disposable tile caches and upgraded
 * through explicit Room schema migrations.
 */
object AppDatabaseContract {
    const val DATABASE_NAME = "map_app_master.db"
    const val CURRENT_VERSION = 3

    // Active shared tables (v3):
    // - retained_tiles
    // - osm_pois, osm_buildings, osm_regions, osm_roads
    // - routing_graph_tiles
    // - user_places (Shared Place & Placemark records)
    // - saved_routes (Ordered user-created route geometry)
    // - route_waypoints (Ordered waypoints linked to saved_routes / optional user_places)
    // - saved_areas (Persistent offline Area Details snapshots, separate from tile caches)
}
