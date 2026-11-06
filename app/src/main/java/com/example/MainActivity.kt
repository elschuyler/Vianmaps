package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.diagnostics.GlobalErrorCatcher
import com.example.ui.screens.MainMapScreen
import com.example.ui.screens.MapViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.network.AppNetworkPolicy
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install global error catcher before any system or view initialization
        GlobalErrorCatcher.install()

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
}
