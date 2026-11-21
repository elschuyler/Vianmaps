package com.example.map.activation

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.network.AppNetworkPolicy
import com.example.ui.screens.MainMapScreen
import com.example.ui.screens.MapViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

sealed interface MapActivationState {
    data object Inactive : MapActivationState
    data object Activating : MapActivationState
    data object Active : MapActivationState
    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : MapActivationState
}

sealed interface MapActivationResult {
    data object Activated : MapActivationResult
    data object AlreadyActive : MapActivationResult
    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : MapActivationResult
}

/**
 * Host-facing Stage 1 activation seam for the Map capability.
 * Keeps host components decoupled from MapLibre, OfflineManager,
 * MapViewModel, MapEngineController, and map-specific repositories.
 */
interface MapActivationBoundary {
    val state: StateFlow<MapActivationState>

    fun activate(context: Context): MapActivationResult

    @Composable
    fun Content(modifier: Modifier = Modifier)
}

/**
 * Seam for native MapLibre runtime initialization so activation coordination
 * and failure paths can be verified deterministically in JVM tests.
 */
fun interface MapRuntimeInitializer {
    fun initialize(appContext: Context)
}

object DefaultMapRuntimeInitializer : MapRuntimeInitializer {
    private const val AMBIENT_CACHE_LIMIT_BYTES = 500L * 1024L * 1024L

    override fun initialize(appContext: Context) {
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapActivation",
            description = "MapLibre initialization started",
            stage = "INIT_START"
        )

        MapLibre.getInstance(appContext)

        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapActivation",
            description = "MapLibre initialization completed (version: 13.7.1)",
            stage = "INIT_COMPLETE"
        )

        try {
            val offlineManager = OfflineManager.getInstance(appContext)
            offlineManager.setMaximumAmbientCacheSize(
                AMBIENT_CACHE_LIMIT_BYTES,
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        DiagnosticRepository.log(
                            severity = LogSeverity.INFO,
                            source = "MapActivation",
                            description = "MapLibre ambient cache size enlarged to 500 MB",
                            stage = "CACHE_CONFIG"
                        )
                    }

                    override fun onError(message: String) {
                        DiagnosticRepository.log(
                            severity = LogSeverity.WARNING,
                            source = "MapActivation",
                            description = "Failed to enlarge ambient cache size: $message",
                            stage = "CACHE_CONFIG"
                        )
                    }
                }
            )
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MapActivation",
                description = "OfflineManager ambient cache setup exception: ${e.message}",
                throwable = e
            )
        }

        MapLibre.setConnected(AppNetworkPolicy.isNetworkAllowed())
    }
}

/**
 * Coordinates lazy, thread-safe, once-per-process Map runtime initialization
 * and owns [MapRuntimeDependencies] + lazy [MapViewModel] / [MainMapScreen]
 * construction behind [MapActivationState.Active].
 */
class MapActivationCoordinator(
    private val runtimeInitializer: MapRuntimeInitializer = DefaultMapRuntimeInitializer,
    private val dependenciesFactory: (Context) -> MapRuntimeDependencies = { appCtx ->
        MapRuntimeDependencies(appCtx)
    },
    private val activeContentWithDeps: (@Composable (Modifier, MapRuntimeDependencies) -> Unit)? = null
) : MapActivationBoundary {

    constructor(
        runtimeInitializer: MapRuntimeInitializer = DefaultMapRuntimeInitializer,
        activeContent: @Composable (Modifier) -> Unit
    ) : this(
        runtimeInitializer = runtimeInitializer,
        activeContentWithDeps = { mod, _ -> activeContent(mod) }
    )

    private val initLock = Any()
    private val _state = MutableStateFlow<MapActivationState>(MapActivationState.Inactive)
    override val state: StateFlow<MapActivationState> = _state.asStateFlow()

    @Volatile
    private var activeDependencies: MapRuntimeDependencies? = null

    internal fun dependenciesOrNull(): MapRuntimeDependencies? = activeDependencies

    override fun activate(context: Context): MapActivationResult {
        if (_state.value is MapActivationState.Active) {
            return MapActivationResult.AlreadyActive
        }

        return synchronized(initLock) {
            if (_state.value is MapActivationState.Active) {
                return@synchronized MapActivationResult.AlreadyActive
            }

            _state.value = MapActivationState.Activating
            try {
                val appContext = context.applicationContext ?: context
                runtimeInitializer.initialize(appContext)
                if (activeDependencies == null) {
                    activeDependencies = dependenciesFactory(appContext)
                }
                _state.value = MapActivationState.Active
                MapActivationResult.Activated
            } catch (ce: kotlinx.coroutines.CancellationException) {
                _state.value = MapActivationState.Inactive
                throw ce
            } catch (t: Throwable) {
                val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                _state.value = MapActivationState.Failed(message = reason, cause = t)
                DiagnosticRepository.log(
                    severity = LogSeverity.ERROR,
                    source = "MapActivation",
                    description = "Map activation failed: $reason",
                    stage = "MAP_ACTIVATION_FAILED",
                    throwable = t
                )
                MapActivationResult.Failed(message = reason, cause = t)
            }
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val currentState by state.collectAsStateWithLifecycle()
        val deps = activeDependencies
        when {
            currentState is MapActivationState.Active && deps != null -> {
                val custom = activeContentWithDeps
                if (custom != null) {
                    custom(modifier, deps)
                } else {
                    val context = LocalContext.current
                    val app = (context.applicationContext as? Application)
                        ?: (context as? Application)
                        ?: error("Application context required for MapViewModel activation")
                    val mapViewModel: MapViewModel = viewModel(factory = deps.viewModelFactory(app))
                    MainMapScreen(viewModel = mapViewModel, modifier = modifier)
                }
            }
            else -> Box(modifier = modifier.fillMaxSize())
        }
    }
}

/**
 * Lazy host-facing entry point ensuring no Map SDK or Map UI components are
 * initialized until [activate] is explicitly invoked.
 */
object MapFeatureActivation : MapActivationBoundary {
    private val coordinator: MapActivationCoordinator by lazy { MapActivationCoordinator() }

    override val state: StateFlow<MapActivationState>
        get() = coordinator.state

    override fun activate(context: Context): MapActivationResult = coordinator.activate(context)

    @Composable
    override fun Content(modifier: Modifier) {
        coordinator.Content(modifier)
    }
}
