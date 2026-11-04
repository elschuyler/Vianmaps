package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.screens.MainMapScreen
import com.example.ui.screens.MapViewModel
import com.example.ui.theme.MyApplicationTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize MapLibre Native before View hierarchy is created
        MapLibre.getInstance(this)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainMapScreen(viewModel = mapViewModel)
            }
        }
    }
}
