package com.example.network

import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
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
 * All logging is centralized in [DiagnosticRepository], with [diagnosticsLog]
 * maintained as an adapter for backward compatibility.
 */
object AppNetworkPolicy {
    private val _isInternetEnabled = MutableStateFlow(true)
    val isInternetEnabled: StateFlow<Boolean> = _isInternetEnabled.asStateFlow()

    private val _diagnosticsLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticsLog: StateFlow<List<String>> = _diagnosticsLog.asStateFlow()

    fun setInternetEnabled(enabled: Boolean) {
        _isInternetEnabled.value = enabled
        val msg = "Network policy toggled to: " + if (enabled) "ONLINE" else "OFFLINE (App Blocked)"
        logDiagnostic(msg)

        DiagnosticRepository.log(
            severity = if (enabled) LogSeverity.INFO else LogSeverity.WARNING,
            source = "AppNetworkPolicy",
            description = msg
        )
    }

    fun isNetworkAllowed(): Boolean = _isInternetEnabled.value

    fun logDiagnostic(message: String) {
        val timestamp = System.currentTimeMillis()
        val entry = "[$timestamp] $message"
        _diagnosticsLog.value = (_diagnosticsLog.value + entry).takeLast(50)
    }
}
