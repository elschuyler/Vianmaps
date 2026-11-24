package com.example.map.activation

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.area.AreaDetailsRepository
import com.example.data.area.AreaRemoteLookupGateway
import com.example.data.area.HttpAreaRemoteLookupGateway
import com.example.data.db.AppDatabase
import com.example.data.osm.OsmOfflineDataRepository
import com.example.data.places.PlacesAndRoutesRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.routing.RoutingGraphCoverageIndex
import com.example.data.storage.MapStorageService
import com.example.data.tile.TileStorageManager
import com.example.diagnostics.NetworkDiagnosticsMonitor
import com.example.map.acquisition.VisibleTileCoordinator
import com.example.map.engine.MapEngineController
import com.example.ui.screens.MapViewModel

/**
 * Centralized Map-owned dependency container.
 *
 * Constructed ONLY after the Map capability is activated via [MapActivationBoundary].
 * Owns single shared instances of Map repositories, storage & network services,
 * and creates viewport-scoped [VisibleTileCoordinator] / [MapEngineController] instances
 * backed by those shared repositories.
 */
class MapRuntimeDependencies(
    context: Context,
    private val databaseProvider: (Context) -> AppDatabase = { AppDatabase.getInstance(it) },
    private val remoteGatewayProvider: () -> AreaRemoteLookupGateway = { HttpAreaRemoteLookupGateway() },
    private val preferencesRepositoryProvider: (Context) -> AppPreferencesRepository = { AppPreferencesRepository(it) }
) {
    val appContext: Context = context.applicationContext ?: context

    val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        databaseProvider(appContext)
    }

    val preferencesRepository: AppPreferencesRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        preferencesRepositoryProvider(appContext)
    }

    val tileStorageManager: TileStorageManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TileStorageManager(appContext, database)
    }

    val osmRepository: OsmOfflineDataRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OsmOfflineDataRepository(appContext, database)
    }

    val graphIndex: RoutingGraphCoverageIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoutingGraphCoverageIndex(appContext, database)
    }

    val placesRepository: PlacesAndRoutesRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlacesAndRoutesRepository(appContext, database)
    }

    val areaRemoteGateway: AreaRemoteLookupGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        remoteGatewayProvider()
    }

    val areaRepository: AreaDetailsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AreaDetailsRepository(
            context = appContext,
            remoteGateway = areaRemoteGateway,
            db = database
        )
    }

    val storageService: MapStorageService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MapStorageService(appContext, database)
    }

    val networkMonitor: NetworkDiagnosticsMonitor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NetworkDiagnosticsMonitor(appContext)
    }

    fun createVisibleTileCoordinator(): VisibleTileCoordinator {
        return VisibleTileCoordinator(
            context = appContext,
            tileStorageManager = tileStorageManager,
            osmRepository = osmRepository,
            graphIndex = graphIndex
        )
    }

    fun createEngineController(
        initialLat: Double,
        initialLng: Double,
        initialZoom: Double,
        initialBearing: Double,
        onCameraMoved: (lat: Double, lng: Double, zoom: Double, bearing: Double) -> Unit
    ): MapEngineController {
        return MapEngineController(
            context = appContext,
            initialLat = initialLat,
            initialLng = initialLng,
            initialZoom = initialZoom,
            initialBearing = initialBearing,
            onCameraMoved = onCameraMoved,
            visibleTileCoordinatorFactory = { createVisibleTileCoordinator() }
        )
    }

    fun viewModelFactory(application: Application): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
                    return MapViewModel(application, this@MapRuntimeDependencies) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
