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
 * Diagnostic sink for [MapViewLifecycleCoordinator] lifecycle stage events.
 * Defaults to [DiagnosticRepository.log] in production and can be intercepted in tests.
 */
fun interface MapViewLifecycleDiagnosticSink {
    fun log(
        severity: LogSeverity,
        source: String,
        description: String,
        stage: String?,
        throwable: Throwable?
    )
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
 * avoids duplicate `onPause`/`onStop` when the Activity already paused/stopped, keeps
 * destruction idempotent (never calling native `onDestroy()` twice, even after partial failure),
 * and logs `DESTROYING` before teardown and `DESTROYED` only after native `onDestroy()` succeeds
 * (or `DESTROY_FAILED` with failing stage and throwable without swallowing the exception).
 */
class MapViewLifecycleCoordinator(
    private val target: MapViewLifecycleTarget,
    private val viewIdLabel: String = "MapView",
    private val diagnosticSink: MapViewLifecycleDiagnosticSink = defaultDiagnosticSink()
) {
    enum class DestructionState {
        ACTIVE,
        DESTROYING,
        DESTROYED,
        FAILED
    }

    var isCreated: Boolean = false
        private set

    var isStarted: Boolean = false
        private set

    var isResumed: Boolean = false
        private set

    var destructionState: DestructionState = DestructionState.ACTIVE
        private set

    val isDestroyed: Boolean
        get() = destructionState == DestructionState.DESTROYED

    val hasDestroyFailed: Boolean
        get() = destructionState == DestructionState.FAILED

    val isTerminated: Boolean
        get() = destructionState != DestructionState.ACTIVE

    var destructionReason: String? = null
        private set

    var failureStage: String? = null
        private set

    var lastDestroyFailure: Throwable? = null
        private set

    private var nativeDestroyInvoked: Boolean = false

    fun dispatchCreate() {
        if (isTerminated || isCreated) return
        target.onCreate()
        isCreated = true
        emitDiagnostic(
            severity = LogSeverity.INFO,
            description = "MapView 'onCreate()' completed ($viewIdLabel)",
            stage = "MAPVIEW_ONCREATE"
        )
    }

    fun dispatchStart() {
        if (isTerminated || isStarted) return
        if (!isCreated) {
            dispatchCreate()
        }
        target.onStart()
        isStarted = true
        emitDiagnostic(
            severity = LogSeverity.INFO,
            description = "MapView.onStart() dispatched ($viewIdLabel)",
            stage = "LIFECYCLE_STARTED"
        )
    }

    fun dispatchResume() {
        if (isTerminated || isResumed) return
        if (!isStarted) {
            dispatchStart()
        }
        target.onResume()
        isResumed = true
        emitDiagnostic(
            severity = LogSeverity.INFO,
            description = "MapView.onResume() dispatched ($viewIdLabel)",
            stage = "LIFECYCLE_RESUMED"
        )
    }

    fun dispatchPause() {
        if (isTerminated || !isResumed) return
        pauseTarget()
    }

    fun dispatchStop() {
        if (isTerminated || !isStarted) return
        if (isResumed) {
            pauseTarget()
        }
        stopTarget()
    }

    fun dispatchLowMemory() {
        if (isTerminated || !isCreated) return
        target.onLowMemory()
    }

    fun dispatchDestroy(
        reason: String,
        beforeDestroy: () -> Unit = {}
    ): Boolean {
        if (isTerminated) {
            emitDiagnostic(
                severity = LogSeverity.DEBUG,
                description = "Ignored redundant destruction request from $reason (state=$destructionState, initialReason=$destructionReason)"
            )
            return false
        }

        destructionState = DestructionState.DESTROYING
        destructionReason = reason
        emitDiagnostic(
            severity = LogSeverity.INFO,
            description = "MapView destruction started by: $reason ($viewIdLabel)",
            stage = "DESTROYING"
        )

        var firstFailure: Throwable? = null
        var firstFailureStage: String? = null

        fun recordFailure(stageName: String, error: Throwable) {
            if (firstFailure == null) {
                firstFailure = error
                firstFailureStage = stageName
            } else if (error !== firstFailure) {
                try {
                    firstFailure?.addSuppressed(error)
                } catch (_: Throwable) {
                }
            }
        }

        // 1. Release controller & stop tile acquisition BEFORE touching MapView teardown
        try {
            beforeDestroy()
        } catch (e: Throwable) {
            recordFailure("BEFORE_DESTROY", e)
        }

        // 2. Satisfy MapLibre MapView contract: onPause() -> onStop() -> onDestroy()
        if (isResumed) {
            try {
                pauseTarget()
            } catch (e: Throwable) {
                recordFailure("ON_PAUSE", e)
            }
        }

        if (isStarted) {
            try {
                stopTarget()
            } catch (e: Throwable) {
                recordFailure("ON_STOP", e)
            }
        }

        if (!nativeDestroyInvoked) {
            nativeDestroyInvoked = true
            try {
                target.onDestroy()
            } catch (e: Throwable) {
                recordFailure("ON_DESTROY", e)
            } finally {
                isCreated = false
            }
        }

        val failure = firstFailure
        if (failure != null) {
            destructionState = DestructionState.FAILED
            failureStage = firstFailureStage
            lastDestroyFailure = failure
            emitDiagnostic(
                severity = LogSeverity.ERROR,
                description = "MapView destruction failed at stage $firstFailureStage ($reason) ($viewIdLabel): ${failure.message ?: failure.javaClass.simpleName}",
                stage = "DESTROY_FAILED",
                throwable = failure
            )
            throw failure
        }

        destructionState = DestructionState.DESTROYED
        emitDiagnostic(
            severity = LogSeverity.INFO,
            description = "MapView destruction completed by: $reason ($viewIdLabel)",
            stage = "DESTROYED"
        )
        return true
    }

    private fun pauseTarget() {
        try {
            target.onPause()
            emitDiagnostic(
                severity = LogSeverity.INFO,
                description = "MapView.onPause() dispatched ($viewIdLabel)",
                stage = "LIFECYCLE_PAUSED"
            )
        } finally {
            isResumed = false
        }
    }

    private fun stopTarget() {
        try {
            target.onStop()
            emitDiagnostic(
                severity = LogSeverity.INFO,
                description = "MapView.onStop() dispatched ($viewIdLabel)",
                stage = "LIFECYCLE_STOPPED"
            )
        } finally {
            isStarted = false
        }
    }

    private fun emitDiagnostic(
        severity: LogSeverity,
        description: String,
        stage: String? = null,
        throwable: Throwable? = null
    ) {
        diagnosticSink.log(
            severity = severity,
            source = "MainMapScreen",
            description = description,
            stage = stage,
            throwable = throwable
        )
    }

    companion object {
        private fun defaultDiagnosticSink(): MapViewLifecycleDiagnosticSink {
            return MapViewLifecycleDiagnosticSink { severity, source, description, stage, throwable ->
                DiagnosticRepository.log(
                    severity = severity,
                    source = source,
                    description = description,
                    stage = stage,
                    throwable = throwable
                )
            }
        }
    }
}
