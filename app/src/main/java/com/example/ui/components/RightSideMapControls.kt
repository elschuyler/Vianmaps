package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.IndicatorAlert
import com.example.ui.theme.SkyBluePrimary

/**
 * Right-Side Map Controls:
 * Individual circular floating bubbles with crisp, high-contrast borders:
 * - 48dp circular backplates with 45% translucent dark slate (Color(0x731E293B)).
 * - Solid 1dp frosted border (Color(0x55FFFFFF)) for maximum sharpness over all map layers.
 * - Prominent 32dp Zoom (+ / -) glyphs positioned at the bottom baseline beside the bottom bar.
 * - Telemetry controls (Internet, GPS, Refresh) positioned above with a distinct separator.
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
        CircularBubbleControlButton(
            icon = if (isInternetEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = if (isInternetEnabled) "Internet On" else "Internet Off",
            testTag = "control_internet_toggle",
            iconSize = 24.dp,
            tint = if (isInternetEnabled) Color.White else IndicatorAlert,
            onClick = onToggleInternet
        )

        // 2. GPS / Location mode
        CircularBubbleControlButton(
            icon = if (isLocationActive) Icons.Default.GpsFixed else Icons.Default.GpsOff,
            contentDescription = if (isLocationActive) "GPS Active" else "GPS Off",
            testTag = "control_gps_toggle",
            iconSize = 24.dp,
            tint = if (isLocationActive) SkyBluePrimary else Color.White,
            onClick = onToggleLocation
        )

        // 3. Refresh Maps
        CircularBubbleControlButton(
            icon = Icons.Default.Refresh,
            contentDescription = "Refresh Visible Maps",
            testTag = "control_refresh_maps",
            iconSize = 24.dp,
            tint = Color.White,
            onClick = onRefreshMaps
        )

        // Distinct separation between telemetry utilities and map zoom manipulation
        Spacer(modifier = Modifier.height(8.dp))

        // 4. Zoom In (+) - Sized prominently (32dp) for effortless tapping
        CircularBubbleControlButton(
            icon = Icons.Default.Add,
            contentDescription = "Zoom In",
            testTag = "control_zoom_in",
            iconSize = 32.dp,
            tint = Color.White,
            onClick = onZoomIn
        )

        // 5. Zoom Out (—) - Sits at the bottom baseline directly in the horizontal gap beside the bottom bar
        CircularBubbleControlButton(
            icon = Icons.Default.Remove,
            contentDescription = "Zoom Out",
            testTag = "control_zoom_out",
            iconSize = 32.dp,
            tint = Color.White,
            onClick = onZoomOut
        )
    }
}

/**
 * Individual circular floating bubble with a 45% translucent dark background and solid 1dp frosted border.
 */
@Composable
private fun CircularBubbleControlButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    iconSize: Dp,
    tint: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0x731E293B))
            .border(BorderStroke(1.dp, Color(0x55FFFFFF)), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 24.dp),
                onClick = onClick
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}
