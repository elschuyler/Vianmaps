package com.example.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide network policy service.
 * Enforces in-app Internet On/Off state.
 *
 * NOTE: Controls app-owned network activity ONLY; it does not change the
 * device's Wi-Fi or cellular connections.
 *
 * For third-party SDKs like MapLibre Native: MapLibre C++ core uses internal
 * HTTP loaders. While app-owned network features strictly check [isNetworkAllowed],
 * external SDK network isolation is tracked and reported via [diagnosticsLog].
 */
object AppNetworkPolicy {
    private val _isInternetEnabled = MutableStateFlow(true)
    val isInternetEnabled: StateFlow<Boolean> = _isInternetEnabled.asStateFlow()

    private val _diagnosticsLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticsLog: StateFlow<List<String>> = _diagnosticsLog.asStateFlow()

    fun setInternetEnabled(enabled: Boolean) {
        _isInternetEnabled.value = enabled
        logDiagnostic("Network policy toggled to: " + if (enabled) "ONLINE" else "OFFLINE (App Blocked)")
    }

    fun isNetworkAllowed(): Boolean = _isInternetEnabled.value

    fun logDiagnostic(message: String) {
        val timestamp = System.currentTimeMillis()
        val entry = "[$timestamp] $message"
        _diagnosticsLog.value = (_diagnosticsLog.value + entry).takeLast(50)
    }
}
