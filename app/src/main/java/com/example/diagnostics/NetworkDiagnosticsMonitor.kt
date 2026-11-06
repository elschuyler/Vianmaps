package com.example.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event-driven network monitor using Android ConnectivityManager callbacks.
 * Does not continuously poll.
 */
class NetworkDiagnosticsMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isDeviceOnline = MutableStateFlow(false)
    val isDeviceOnline: StateFlow<Boolean> = _isDeviceOnline.asStateFlow()

    private val _connectionType = MutableStateFlow("UNKNOWN")
    val connectionType: StateFlow<String> = _connectionType.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val type = detectType(network)
            _isDeviceOnline.value = true
            _connectionType.value = type
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "NetworkMonitor",
                description = "Device network became AVAILABLE ($type)"
            )
        }

        override fun onLost(network: Network) {
            _isDeviceOnline.value = false
            _connectionType.value = "NONE"
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "NetworkMonitor",
                description = "Device network connection was LOST"
            )
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val type = detectType(network)
            _connectionType.value = type
            _isDeviceOnline.value = hasInternet && isValidated
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)

            // Initial snapshot
            val active = connectivityManager?.activeNetwork
            val caps = connectivityManager?.getNetworkCapabilities(active)
            val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            _isDeviceOnline.value = isOnline
            _connectionType.value = active?.let { detectType(it) } ?: "NONE"

            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "NetworkMonitor",
                description = "Initial network state: ${if (isOnline) "ONLINE (${_connectionType.value})" else "OFFLINE"}"
            )
        } catch (e: Exception) {
            DiagnosticRepository.log(
                severity = LogSeverity.ERROR,
                source = "NetworkMonitor",
                description = "Failed to register network callback",
                throwable = e
            )
        }
    }

    private fun detectType(network: Network): String {
        val caps = connectivityManager?.getNetworkCapabilities(network) ?: return "UNKNOWN"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
    }

    fun unregister() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }
}
