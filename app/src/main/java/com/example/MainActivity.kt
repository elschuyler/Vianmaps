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
import com.example.diagnostics.GlobalErrorCatcher
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.map.activation.MapActivationBoundary
import com.example.map.activation.MapFeatureActivation
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val mapFeature: MapActivationBoundary = MapFeatureActivation

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

        // Explicitly request Map activation through the host-facing activation seam
        mapFeature.activate(this)

        // Force light status bar appearance (dark icons) so system bar icons remain readable
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            MyApplicationTheme {
                mapFeature.Content()
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
