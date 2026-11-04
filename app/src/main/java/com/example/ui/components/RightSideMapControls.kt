package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentRed
import com.example.ui.theme.AccentTeal
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SurfaceBorderDark

/**
 * Exactly five right-side map controls in strict order top to bottom:
 * 1. Internet On/Off
 * 2. GPS / Location mode
 * 3. Refresh Maps
 * 4. Zoom In
 * 5. Zoom Out
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
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(SlateDark800.copy(alpha = 0.94f))
            .border(1.dp, SurfaceBorderDark, shape)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Internet On/Off
        MapControlButton(
            icon = if (isInternetEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = if (isInternetEnabled) "Internet On (App level)" else "Internet Off (App level)",
            testTag = "control_internet_toggle",
            tint = if (isInternetEnabled) AccentGreen else AccentRed,
            onClick = onToggleInternet
        )

        // 2. GPS / Location mode
        MapControlButton(
            icon = if (isLocationActive) Icons.Default.GpsFixed else Icons.Default.GpsOff,
            contentDescription = if (isLocationActive) "GPS Location Active" else "GPS Location Off",
            testTag = "control_gps_toggle",
            tint = if (isLocationActive) AccentTeal else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onToggleLocation
        )

        // 3. Refresh Maps
        MapControlButton(
            icon = Icons.Default.Refresh,
            contentDescription = "Refresh Visible Maps",
            testTag = "control_refresh_maps",
            tint = AccentAmber,
            onClick = onRefreshMaps
        )

        // 4. Zoom In
        MapControlButton(
            icon = Icons.Default.Add,
            contentDescription = "Zoom In",
            testTag = "control_zoom_in",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onZoomIn
        )

        // 5. Zoom Out
        MapControlButton(
            icon = Icons.Default.Remove,
            contentDescription = "Zoom Out",
            testTag = "control_zoom_out",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onZoomOut
        )
    }
}

@Composable
private fun MapControlButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
