package com.example.map.activation

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

sealed interface MapDeactivationResult {
    data object Deactivated : MapDeactivationResult
    data object AlreadyInactive : MapDeactivationResult
}

/**
 * Host-facing activation seam for the Map capability.
 * Keeps host components decoupled from MapLibre, OfflineManager,
 * MapViewModel, MapEngineController, and map-specific repositories,
 * while supporting independent per-session activation and teardown.
 */
interface MapActivationBoundary {
    val state: StateFlow<MapActivationState>
    val isRuntimeInitialized: Boolean

    fun activate(context: Context): MapActivationResult
    fun deactivate(): MapDeactivationResult

    @Composable
    fun Content(
        modifier: Modifier = Modifier,
        onOpenNotes: () -> Unit = {}
    )
}

/**
 * Seam for native MapLibre runtime initialization so activation coordination
 * and failure paths can be verified deterministically in JVM tests.
 */
fun interface MapRuntimeInitializer {
    fun initialize(appContext: Context)
}

/**
 * Tracks process-level MapLibre native SDK initialization independently of
 * per-session Map activation/deactivation. Native MapLibre stays initialized for
 * the lifetime of the Android process once created, while Map sessions can be
 * deactivated and recreated cleanly.
 */
object ProcessMapRuntimeState {
    private val lock = Any()

    @Volatile
    private var initialized: Boolean = false

    private val initAttempts = AtomicInteger(0)

    val isInitialized: Boolean
        get() = initialized || safeHasMapLibreInstance()

    val initializationCount: Int
        get() = initAttempts.get()

    internal fun ensureInitialized(initBlock: () -> Unit): Boolean {
        if (isInitialized) return false
        synchronized(lock) {
            if (isInitialized) return false
            initBlock()
            initialized = true
            initAttempts.incrementAndGet()
            return true
        }
    }

    internal fun resetForTestingOnly() {
        synchronized(lock) {
            initialized = false
            initAttempts.set(0)
        }
    }

    private fun safeHasMapLibreInstance(): Boolean {
        return try {
            MapLibre.hasInstance()
        } catch (_: Throwable) {
            false
        }
    }
}

object DefaultMapRuntimeInitializer : MapRuntimeInitializer {
    private const val AMBIENT_CACHE_LIMIT_BYTES = 500L * 1024L * 1024L

    override fun initialize(appContext: Context) {
        val newlyInitialized = ProcessMapRuntimeState.ensureInitialized {
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
        }

        if (!newlyInitialized) {
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MapActivation",
                description = "Reusing process-level MapLibre runtime for new Map session",
                stage = "INIT_REUSED"
            )
        }

        if (ProcessMapRuntimeState.isInitialized) {
            try {
                MapLibre.setConnected(AppNetworkPolicy.isNetworkAllowed())
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * Coordinates lazy, thread-safe Map activation and deactivation.
 * Tracks process-level runtime initialization separately from per-session
 * [MapRuntimeDependencies], dedicated [ViewModelStore], and active Map UI composition.
 */
class MapActivationCoordinator(
    private val runtimeInitializer: MapRuntimeInitializer = DefaultMapRuntimeInitializer,
    private val dependenciesFactory: (Context) -> MapRuntimeDependencies = { appCtx ->
        MapRuntimeDependencies(appCtx)
    },
    private val activeContentWithDeps: (@Composable (Modifier, MapRuntimeDependencies) -> Unit)? = null,
    private val activeSessionContent: (
        @Composable (
            Modifier,
            MapRuntimeDependencies,
            MapViewModel,
            () -> Unit,
            ((() -> Unit)?) -> Unit
        ) -> Unit
    )? = null
) : MapActivationBoundary {

    constructor(
        runtimeInitializer: MapRuntimeInitializer = DefaultMapRuntimeInitializer,
        activeContent: @Composable (Modifier) -> Unit
    ) : this(
        runtimeInitializer = runtimeInitializer,
        activeContentWithDeps = { mod, _ -> activeContent(mod) }
    )

    private class SessionViewModelStoreOwner : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    private data class ActiveMapSession(
        val sessionId: Long,
        val dependencies: MapRuntimeDependencies,
        val storeOwner: SessionViewModelStoreOwner
    )

    private val initLock = Any()
    private val useProcessGlobalTracker = (runtimeInitializer === DefaultMapRuntimeInitializer)

    @Volatile
    private var localRuntimeInitialized: Boolean = false

    private val sessionSequence = AtomicLong(0L)
    private val _sessionActivationCount = AtomicInteger(0)
    private val _deactivationCount = AtomicInteger(0)

    private val _state = MutableStateFlow<MapActivationState>(MapActivationState.Inactive)
    override val state: StateFlow<MapActivationState> = _state.asStateFlow()

    private val _activeSession = MutableStateFlow<ActiveMapSession?>(null)

    @Volatile
    private var sessionFlushHook: Pair<Long, () -> Unit>? = null

    @Volatile
    private var sessionControllerTeardownHook: Pair<Long, () -> Unit>? = null

    override val isRuntimeInitialized: Boolean
        get() = if (useProcessGlobalTracker) {
            ProcessMapRuntimeState.isInitialized
        } else {
            localRuntimeInitialized
        }

    internal val sessionActivationCount: Int
        get() = _sessionActivationCount.get()

    internal val deactivationCount: Int
        get() = _deactivationCount.get()

    internal fun dependenciesOrNull(): MapRuntimeDependencies? = _activeSession.value?.dependencies

    internal fun sessionViewModelStoreOrNull(): ViewModelStore? = _activeSession.value?.storeOwner?.viewModelStore

    internal fun activeSessionIdOrZero(): Long = _activeSession.value?.sessionId ?: 0L

    private fun registerSessionFlushHook(sessionId: Long, hook: (() -> Unit)?) {
        sessionFlushHook = if (hook != null && _activeSession.value?.sessionId == sessionId) {
            sessionId to hook
        } else {
            null
        }
    }

    private fun clearSessionFlushHookIfMatches(sessionId: Long) {
        if (sessionFlushHook?.first == sessionId) {
            sessionFlushHook = null
        }
    }

    private fun registerControllerTeardownHook(sessionId: Long, hook: (() -> Unit)?) {
        sessionControllerTeardownHook = if (hook != null && _activeSession.value?.sessionId == sessionId) {
            sessionId to hook
        } else {
            null
        }
    }

    override fun activate(context: Context): MapActivationResult {
        if (_state.value is MapActivationState.Active && _activeSession.value != null) {
            return MapActivationResult.AlreadyActive
        }

        return synchronized(initLock) {
            if (_state.value is MapActivationState.Active && _activeSession.value != null) {
                return@synchronized MapActivationResult.AlreadyActive
            }

            _state.value = MapActivationState.Activating
            try {
                val appContext = context.applicationContext ?: context
                if (useProcessGlobalTracker) {
                    runtimeInitializer.initialize(appContext)
                } else if (!localRuntimeInitialized) {
                    runtimeInitializer.initialize(appContext)
                    localRuntimeInitialized = true
                }

                val existingSession = _activeSession.value
                val session = existingSession ?: ActiveMapSession(
                    sessionId = sessionSequence.incrementAndGet(),
                    dependencies = dependenciesFactory(appContext),
                    storeOwner = SessionViewModelStoreOwner()
                )
                _activeSession.value = session
                _sessionActivationCount.incrementAndGet()
                _state.value = MapActivationState.Active

                DiagnosticRepository.log(
                    severity = LogSeverity.INFO,
                    source = "MapActivation",
                    description = "Map session activated (#${session.sessionId})",
                    stage = "MAP_ACTIVATED"
                )
                MapActivationResult.Activated
            } catch (ce: kotlinx.coroutines.CancellationException) {
                _activeSession.value = null
                _state.value = MapActivationState.Inactive
                throw ce
            } catch (t: Throwable) {
                val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                _activeSession.value = null
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

    override fun deactivate(): MapDeactivationResult {
        if (_state.value is MapActivationState.Inactive && _activeSession.value == null) {
            return MapDeactivationResult.AlreadyInactive
        }

        return synchronized(initLock) {
            val session = _activeSession.value
            if (_state.value is MapActivationState.Inactive && session == null) {
                return@synchronized MapDeactivationResult.AlreadyInactive
            }

            val flushHook = sessionFlushHook?.takeIf { session == null || it.first == session.sessionId }?.second
            val teardownHook = sessionControllerTeardownHook?.takeIf { session == null || it.first == session.sessionId }?.second
            sessionFlushHook = null
            sessionControllerTeardownHook = null

            // 1. Flush any pending camera/preference writes before ViewModelScope is cancelled
            if (flushHook != null) {
                try {
                    flushHook.invoke()
                } catch (t: Throwable) {
                    DiagnosticRepository.log(
                        severity = LogSeverity.WARNING,
                        source = "MapActivation",
                        description = "Camera/preference flush note during deactivation: ${t.message}",
                        throwable = t
                    )
                }
            }

            // 2. Stop tile acquisition and detach map engine callbacks
            if (teardownHook != null) {
                try {
                    teardownHook.invoke()
                } catch (t: Throwable) {
                    DiagnosticRepository.log(
                        severity = LogSeverity.WARNING,
                        source = "MapActivation",
                        description = "Controller teardown note during deactivation: ${t.message}",
                        throwable = t
                    )
                }
            }

            // 3. Clear per-session ViewModelStore (triggers MapViewModel.onCleared() & networkMonitor.unregister())
            if (session != null) {
                try {
                    session.storeOwner.viewModelStore.clear()
                } catch (t: Throwable) {
                    DiagnosticRepository.log(
                        severity = LogSeverity.WARNING,
                        source = "MapActivation",
                        description = "Session ViewModelStore clear exception: ${t.message}",
                        throwable = t
                    )
                }
            }

            // 4. Release session dependencies and mark Inactive (process-level SDK remains initialized)
            _activeSession.value = null
            _state.value = MapActivationState.Inactive
            _deactivationCount.incrementAndGet()

            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MapActivation",
                description = "Map session deactivated (#${session?.sessionId ?: 0}); runtimeInitialized=$isRuntimeInitialized",
                stage = "MAP_DEACTIVATED"
            )
            MapDeactivationResult.Deactivated
        }
    }

    @Composable
    override fun Content(
        modifier: Modifier,
        onOpenNotes: () -> Unit
    ) {
        val currentState by state.collectAsStateWithLifecycle()
        val session by _activeSession.collectAsStateWithLifecycle()
        val activeSession = session
        when {
            currentState is MapActivationState.Active && activeSession != null -> {
                CompositionLocalProvider(LocalViewModelStoreOwner provides activeSession.storeOwner) {
                    key(activeSession.sessionId) {
                        val custom = activeContentWithDeps
                        if (custom != null) {
                            custom(modifier, activeSession.dependencies)
                        } else {
                            val context = LocalContext.current
                            val app = (context.applicationContext as? Application)
                                ?: (context as? Application)
                                ?: error("Application context required for MapViewModel activation")
                            val mapViewModel: MapViewModel = viewModel(
                                factory = activeSession.dependencies.viewModelFactory(app)
                            )
                            DisposableEffect(activeSession.sessionId, mapViewModel) {
                                registerSessionFlushHook(activeSession.sessionId) {
                                    mapViewModel.flushPendingStateBlocking()
                                }
                                onDispose {
                                    clearSessionFlushHookIfMatches(activeSession.sessionId)
                                }
                            }
                            val customSessionUi = activeSessionContent
                            if (customSessionUi != null) {
                                customSessionUi(
                                    modifier,
                                    activeSession.dependencies,
                                    mapViewModel,
                                    onOpenNotes
                                ) { teardown ->
                                    registerControllerTeardownHook(activeSession.sessionId, teardown)
                                }
                            } else {
                                MainMapScreen(
                                    viewModel = mapViewModel,
                                    modifier = modifier,
                                    onOpenNotes = onOpenNotes,
                                    onRegisterControllerTeardown = { teardown ->
                                        registerControllerTeardownHook(activeSession.sessionId, teardown)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            else -> Box(modifier = modifier.fillMaxSize())
        }
    }
}

/**
 * Lazy host-facing entry point ensuring no Map SDK or Map UI components are
 * initialized until [activate] is explicitly invoked, and supporting clean deactivation.
 */
object MapFeatureActivation : MapActivationBoundary {
    private val coordinator: MapActivationCoordinator by lazy { MapActivationCoordinator() }

    override val state: StateFlow<MapActivationState>
        get() = coordinator.state

    override val isRuntimeInitialized: Boolean
        get() = coordinator.isRuntimeInitialized

    override fun activate(context: Context): MapActivationResult = coordinator.activate(context)

    override fun deactivate(): MapDeactivationResult = coordinator.deactivate()

    @Composable
    override fun Content(
        modifier: Modifier,
        onOpenNotes: () -> Unit
    ) {
        coordinator.Content(modifier = modifier, onOpenNotes = onOpenNotes)
    }
}
