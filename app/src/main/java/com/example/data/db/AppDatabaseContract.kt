package com.example.data.db

/**
 * Shared Database Architecture Boundary for future modules (Places, Notes, Routes, Tools).
 *
 * NOTE: The product direction is one shared local Room database for structured
 * user-owned records. Per project instructions, avoid premature schemas until
 * those record workflows are implemented. This boundary documents and reserves
 * the single database identifier and migration path.
 */
object AppDatabaseContract {
    const val DATABASE_NAME = "map_app_master.db"
    const val CURRENT_VERSION = 1

    // Future shared tables:
    // - placemarks_table
    // - routes_table
    // - spatial_notes_table
    // - memory_palace_table
}
