package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.IndicatorAlert
import com.example.ui.theme.SkyBlueBorder
import com.example.ui.theme.SkyBlueLight
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceWhiteTranslucent
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary

/**
 * Milestone 1.1 — Right-side map controls:
 * - Removed the shared vertical container/bubble background.
 * - Each control appears independently as a floating circular white button.
 * - White surface with Sky Blue icons and active highlights.
 * - Respects the strict order top to bottom:
 *   1. Internet On/Off
 *   2. GPS / Location mode
 *   3. Refresh Maps
 *   4. Zoom In
 *   5. Zoom Out
 */
@Composable
fun RightSideMapControls(
    isInternetEnabled: Boolean,
    isLocationActive: Boolean,
    onToggleInternet: () -> Unit,
    onToggleLocation: () -> Unit,
    onRefreshMaps: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Internet On/Off
        IndependentMapControlButton(
            icon = if (isInternetEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = if (isInternetEnabled) "Internet On (App level)" else "Internet Off (App level)",
            testTag = "control_internet_toggle",
            isActive = isInternetEnabled,
            tint = if (isInternetEnabled) SkyBluePrimary else IndicatorAlert,
            onClick = onToggleInternet
        )

        // 2. GPS / Location mode
        IndependentMapControlButton(
            icon = if (isLocationActive) Icons.Default.GpsFixed else Icons.Default.GpsOff,
            contentDescription = if (isLocationActive) "GPS Location Active" else "GPS Location Off",
            testTag = "control_gps_toggle",
            isActive = isLocationActive,
            tint = if (isLocationActive) SkyBluePrimary else TextDarkSecondary,
            onClick = onToggleLocation
        )

        // 3. Refresh Maps
        IndependentMapControlButton(
            icon = Icons.Default.Refresh,
            contentDescription = "Refresh Visible Maps",
            testTag = "control_refresh_maps",
            isActive = false,
            tint = SkyBluePrimary,
            onClick = onRefreshMaps
        )

        // 4. Zoom In
        IndependentMapControlButton(
            icon = Icons.Default.Add,
            contentDescription = "Zoom In",
            testTag = "control_zoom_in",
            isActive = false,
            tint = TextDarkPrimary,
            onClick = onZoomIn
        )

        // 5. Zoom Out
        IndependentMapControlButton(
            icon = Icons.Default.Remove,
            contentDescription = "Zoom Out",
            testTag = "control_zoom_out",
            isActive = false,
            tint = TextDarkPrimary,
            onClick = onZoomOut
        )
    }
}

@Composable
private fun IndependentMapControlButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    isActive: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val buttonShape = CircleShape

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(buttonShape)
            .background(if (isActive) SkyBlueLight else SurfaceWhiteTranslucent)
            .border(
                width = 1.dp,
                color = if (isActive) SkyBlueBorder else SurfaceBorderLight,
                shape = buttonShape
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(40.dp)
                .testTag(testTag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
