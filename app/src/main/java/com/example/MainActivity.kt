package com.example

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.diagnostics.GlobalErrorCatcher
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.network.AppNetworkPolicy
import com.example.ui.screens.MainMapScreen
import com.example.ui.screens.MapViewModel
import com.example.ui.theme.MyApplicationTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class MainActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "Activity onCreate (hash=#${System.identityHashCode(this)})",
            stage = "ACTIVITY_ON_CREATE"
        )

        // Install global error catcher before any system or view initialization
        GlobalErrorCatcher.install()

        // Device hardware and graphics capability diagnostic audit
        logDeviceHardwareDiagnostics()

        // Stage 1: MapLibre initialization started
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "MapLibre initialization started",
            stage = "INIT_START"
        )

        // Initialize MapLibre Native before View hierarchy is created
        MapLibre.getInstance(this)

        // Stage 2: MapLibre initialization completed
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "MapLibre initialization completed (version: 13.7.1)",
            stage = "INIT_COMPLETE"
        )

        // Configure ambient cache size to 500 MB (SDK default is only 50 MB)
        try {
            val offlineManager = OfflineManager.getInstance(this)
            offlineManager.setMaximumAmbientCacheSize(500L * 1024L * 1024L, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MainActivity",
                        description = "MapLibre ambient cache size enlarged to 500 MB",
                        stage = "CACHE_CONFIG"
                    )
                }

                override fun onError(message: String) {
                    DiagnosticRepository.log(
                        severity = LogSeverity.WARNING,
                        source = "MainActivity",
                        description = "Failed to enlarge ambient cache size: $message",
                        stage = "CACHE_CONFIG"
                    )
                }
            })
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MainActivity",
                description = "OfflineManager ambient cache setup exception: ${e.message}",
                throwable = e
            )
        }

        // Synchronize in-app network policy state to MapLibre core
        MapLibre.setConnected(AppNetworkPolicy.isNetworkAllowed())

        // Force light status bar appearance (dark icons) so system bar icons remain readable
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            MyApplicationTheme {
                MainMapScreen(viewModel = mapViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "Activity onStart (hash=#${System.identityHashCode(this)})",
            stage = "ACTIVITY_ON_START"
        )
    }

    override fun onResume() {
        super.onResume()
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "Activity onResume (hash=#${System.identityHashCode(this)})",
            stage = "ACTIVITY_ON_RESUME"
        )
    }

    override fun onPause() {
        super.onPause()
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "Activity onPause (hash=#${System.identityHashCode(this)})",
            stage = "ACTIVITY_ON_PAUSE"
        )
    }

    override fun onStop() {
        super.onStop()
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "Activity onStop (hash=#${System.identityHashCode(this)})",
            stage = "ACTIVITY_ON_STOP"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MainActivity",
            description = "Activity onDestroy (hash=#${System.identityHashCode(this)})",
            stage = "ACTIVITY_ON_DESTROY"
        )
    }

    private fun logDeviceHardwareDiagnostics() {
        try {
            val pm = packageManager
            val hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            val vulkanVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && hasVulkan) {
                val pkgInfo = pm.getPackageInfo("android", PackageManager.GET_CONFIGURATIONS)
                val vFeature = pkgInfo.reqFeatures?.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                vFeature?.reqGlEsVersion?.let { "0x" + Integer.toHexString(it) } ?: "supported"
            } else {
                "not supported"
            }

            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val glEsVersion = am?.deviceConfigurationInfo?.glEsVersion ?: "unknown"

            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MainActivity",
                description = "Hardware: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | GLES: $glEsVersion | Vulkan: $vulkanVersion",
                stage = "DEVICE_HARDWARE"
            )
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MainActivity",
                description = "Failed to query hardware diagnostics: ${e.message}"
            )
        }
    }
}
