package com.example.map.engine

import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import org.maplibre.android.maps.MapView

/**
 * Abstract lifecycle target for [MapView] so lifecycle sequencing can be
 * enforced cleanly in production and verified deterministically in JVM tests.
 */
interface MapViewLifecycleTarget {
    fun onCreate()
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
}

/**
 * Production [MapViewLifecycleTarget] adapter delegating directly to MapLibre's [MapView].
 */
class NativeMapViewLifecycleTarget(
    private val mapView: MapView
) : MapViewLifecycleTarget {
    override fun onCreate() {
        mapView.onCreate(null)
    }

    override fun onStart() {
        mapView.onStart()
    }

    override fun onResume() {
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
    }

    override fun onStop() {
        mapView.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        mapView.onLowMemory()
    }
}

/**
 * State-aware lifecycle coordinator enforcing the MapLibre [MapView] contract:
 * `onCreate -> onStart -> onResume -> onPause -> onStop -> onDestroy`.
 *
 * Guarantees that internal Compose disposal while the host Activity remains resumed
 * still dispatches `onPause()` and `onStop()` before `onDestroy()`, executes [beforeDestroy]
 * (e.g., releasing [MapEngineController] and stopping tile acquisition) before native destruction,
 * avoids duplicate `onPause`/`onStop` when the Activity already paused/stopped, and keeps
 * destruction idempotent across overlapping Activity and Compose teardown paths.
 */
class MapViewLifecycleCoordinator(
    private val target: MapViewLifecycleTarget,
    private val viewIdLabel: String = "MapView"
) {
    var isCreated: Boolean = false
        private set

    var isStarted: Boolean = false
        private set

    var isResumed: Boolean = false
        private set

    var isDestroyed: Boolean = false
        private set

    var destructionReason: String? = null
        private set

    fun dispatchCreate() {
        if (isDestroyed || isCreated) return
        target.onCreate()
        isCreated = true
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainMapScreen",
            description = "MapView 'onCreate()' completed ($viewIdLabel)",
            stage = "MAPVIEW_ONCREATE"
        )
    }

    fun dispatchStart() {
        if (isDestroyed || isStarted) return
        if (!isCreated) {
            dispatchCreate()
        }
        target.onStart()
        isStarted = true
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainMapScreen",
            description = "MapView.onStart() dispatched ($viewIdLabel)",
            stage = "LIFECYCLE_STARTED"
        )
    }

    fun dispatchResume() {
        if (isDestroyed || isResumed) return
        if (!isStarted) {
            dispatchStart()
        }
        target.onResume()
        isResumed = true
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainMapScreen",
            description = "MapView.onResume() dispatched ($viewIdLabel)",
            stage = "LIFECYCLE_RESUMED"
        )
    }

    fun dispatchPause() {
        if (isDestroyed || !isResumed) return
        try {
            target.onPause()
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MainMapScreen",
                description = "MapView.onPause() dispatched ($viewIdLabel)",
                stage = "LIFECYCLE_PAUSED"
            )
        } finally {
            isResumed = false
        }
    }

    fun dispatchStop() {
        if (isDestroyed || !isStarted) return
        if (isResumed) {
            dispatchPause()
        }
        try {
            target.onStop()
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MainMapScreen",
                description = "MapView.onStop() dispatched ($viewIdLabel)",
                stage = "LIFECYCLE_STOPPED"
            )
        } finally {
            isStarted = false
        }
    }

    fun dispatchLowMemory() {
        if (isDestroyed || !isCreated) return
        target.onLowMemory()
    }

    fun dispatchDestroy(
        reason: String,
        beforeDestroy: () -> Unit = {}
    ): Boolean {
        if (isDestroyed) {
            DiagnosticRepository.log(
                severity = LogSeverity.DEBUG,
                source = "MainMapScreen",
                description = "Ignored redundant destruction request from $reason (already destroyed by $destructionReason)"
            )
            return false
        }

        destructionReason = reason
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainMapScreen",
            description = "MapView destruction initiated by: $reason ($viewIdLabel)",
            stage = "DESTROYED"
        )

        // 1. Release controller & stop tile acquisition BEFORE touching MapView teardown
        try {
            beforeDestroy()
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MainMapScreen",
                description = "Exception in beforeDestroy ($reason): ${e.message}",
                throwable = e
            )
        }

        // 2. Satisfy MapLibre MapView contract: onPause() -> onStop() -> onDestroy()
        if (isResumed) {
            try {
                dispatchPause()
            } catch (e: Throwable) {
                isResumed = false
                DiagnosticRepository.log(
                    severity = LogSeverity.WARNING,
                    source = "MainMapScreen",
                    description = "Exception during MapView.onPause ($reason): ${e.message}",
                    throwable = e
                )
            }
        }

        if (isStarted) {
            try {
                dispatchStop()
            } catch (e: Throwable) {
                isStarted = false
                DiagnosticRepository.log(
                    severity = LogSeverity.WARNING,
                    source = "MainMapScreen",
                    description = "Exception during MapView.onStop ($reason): ${e.message}",
                    throwable = e
                )
            }
        }

        isDestroyed = true
        isCreated = false
        try {
            target.onDestroy()
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MainMapScreen",
                description = "Exception during MapView.onDestroy ($reason): ${e.message}",
                throwable = e
            )
        }
        return true
    }
}
