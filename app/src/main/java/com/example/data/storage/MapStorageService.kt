package com.example.data.storage

import android.content.Context
import com.example.data.db.AppDatabase

/**
 * Map-owned storage accounting and per-provider cache-clearing collaborator.
 * Keeps Room database resolution and [StorageCalculationManager] orchestration
 * out of [com.example.ui.screens.MapViewModel].
 */
class MapStorageService(
    context: Context,
    private val database: AppDatabase
) {
    private val appContext: Context = context.applicationContext ?: context

    suspend fun calculateStorageReport(): OverallStorageReport {
        return StorageCalculationManager.calculateStorage(appContext, database)
    }

    suspend fun clearProviderCache(providerId: String): ProviderClearResult {
        return StorageCalculationManager.clearProviderTiles(appContext, database, providerId)
    }
}
