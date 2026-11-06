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
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install global error catcher before any system or view initialization
        GlobalErrorCatcher.install()

        // Initialize MapLibre Native before View hierarchy is created
        MapLibre.getInstance(this)

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
