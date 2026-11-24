package com.example.map.activation

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.network.AppNetworkPolicy
import com.example.ui.screens.MainMapScreen
import com.example.ui.screens.MapViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : MapDeactivationResult
}

/**
 * Host-facing activation seam for the Map capability.
 * Keeps host components decoupled from MapLibre, OfflineManager,
 * MapViewModel, MapEngineController, and map-specific repositories,
 * while supporting independent per-session activation and ordered async teardown.
 */
interface MapActivationBoundary {
    val state: StateFlow<MapActivationState>
    val isRuntimeInitialized: Boolean

    fun activate(context: Context): MapActivationResult
    suspend fun deactivate(): MapDeactivationResult

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
    private val deactivationMutex = Mutex()
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
    private var sessionFlushHook: Pair<Long, suspend () -> Result<Unit>>? = null

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

    private fun registerSessionFlushHook(sessionId: Long, hook: (suspend () -> Result<Unit>)?) {
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

    override suspend fun deactivate(): MapDeactivationResult {
        if (_state.value is MapActivationState.Inactive && _activeSession.value == null) {
            return MapDeactivationResult.AlreadyInactive
        }

        return deactivationMutex.withLock {
            val initialSnapshot = synchronized(initLock) {
                val session = _activeSession.value
                if (_state.value is MapActivationState.Inactive && session == null) {
                    null
                } else {
                    val flushHook = sessionFlushHook?.takeIf { session == null || it.first == session.sessionId }?.second
                    Pair(session, flushHook)
                }
            } ?: return@withLock MapDeactivationResult.AlreadyInactive

            val (_, flushHook) = initialSnapshot

            // 1. Ordered asynchronous flush BEFORE cancelling ViewModelScope or releasing session.
            //    Never blocks UI thread; surfaces cancellation or persistence errors explicitly.
            if (flushHook != null) {
                val flushResult = try {
                    flushHook.invoke()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }

                if (flushResult.isFailure) {
                    val cause = flushResult.exceptionOrNull()
                    val reason = cause?.message?.takeIf { it.isNotBlank() }
                        ?: cause?.javaClass?.simpleName
                        ?: "Failed to persist pending map state"
                    DiagnosticRepository.log(
                        severity = LogSeverity.ERROR,
                        source = "MapActivation",
                        description = "Deactivation aborted because state persistence failed: $reason",
                        stage = "MAP_DEACTIVATION_FAILED",
                        throwable = cause
                    )
                    return@withLock MapDeactivationResult.Failed(message = reason, cause = cause)
                }
            }

            // 2. Finalize session release only after flush completed cleanly
            synchronized(initLock) {
                val session = _activeSession.value
                if (_state.value is MapActivationState.Inactive && session == null) {
                    return@synchronized MapDeactivationResult.AlreadyInactive
                }

                val teardownHook = sessionControllerTeardownHook?.takeIf { session == null || it.first == session.sessionId }?.second
                sessionFlushHook = null
                sessionControllerTeardownHook = null

                // Stop tile acquisition and detach map engine callbacks
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

                // Clear per-session ViewModelStore (triggers MapViewModel.onCleared() & networkMonitor.unregister())
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

                // Release session dependencies and mark Inactive (process-level SDK remains initialized)
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
    }

    @Composable
    override fun Content(
        modifier: Modifier,
        onOpenNotes: () -> Unit
    ) {
        val currentState by state.collectAsStateWithLifecycle()
        val session by _activeSession.collectAsStateWithLifecycle()
        val activeSession = session
        when (val st = currentState) {
            is MapActivationState.Active -> {
                if (activeSession != null) {
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
                                        mapViewModel.prepareAndFlushForDeactivation()
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
                } else {
                    MapActivationLoadingScreen(
                        modifier = modifier,
                        onOpenNotes = onOpenNotes
                    )
                }
            }
            is MapActivationState.Activating -> {
                MapActivationLoadingScreen(
                    modifier = modifier,
                    onOpenNotes = onOpenNotes
                )
            }
            is MapActivationState.Failed -> {
                val context = LocalContext.current
                MapActivationRecoveryScreen(
                    failureMessage = st.message,
                    onRetry = { activate(context) },
                    onOpenNotes = onOpenNotes,
                    modifier = modifier
                )
            }
            is MapActivationState.Inactive -> {
                Box(modifier = modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun MapActivationLoadingScreen(
    modifier: Modifier = Modifier,
    onOpenNotes: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("map_activation_loading_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.map_activation_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onOpenNotes != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = onOpenNotes,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("map_loading_open_notes_button")
                ) {
                    Text(text = stringResource(id = R.string.map_activation_open_notes))
                }
            }
        }
    }
}

@Composable
fun MapActivationRecoveryScreen(
    failureMessage: String,
    onRetry: () -> Unit,
    onOpenNotes: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("map_activation_error_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(id = R.string.map_activation_error_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = failureMessage.ifBlank {
                        stringResource(id = R.string.map_activation_error_fallback)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("map_activation_error_message")
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    OutlinedButton(
                        onClick = onOpenNotes,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("map_activation_open_notes_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(id = R.string.map_activation_open_notes))
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("map_activation_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(id = R.string.map_activation_retry))
                    }
                }
            }
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

    override suspend fun deactivate(): MapDeactivationResult = coordinator.deactivate()

    @Composable
    override fun Content(
        modifier: Modifier,
        onOpenNotes: () -> Unit
    ) {
        coordinator.Content(modifier = modifier, onOpenNotes = onOpenNotes)
    }
}
