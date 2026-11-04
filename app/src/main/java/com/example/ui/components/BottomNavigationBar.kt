package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentTeal
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SurfaceBorderDark

/**
 * Exactly four bottom-navigation buttons, left to right:
 * 1. Settings (tap -> full Settings, long-press -> quick-Settings)
 * 2. Maps (tap -> Maps popup with Maps, Layers, Custom tabs)
 * 3. Places (tap -> Places popup, long-press -> Memory Palace workspace placeholder in Notes)
 * 4. Compass / Tools (tap -> toggles North Lock / Free Rotation, long-press -> Tools popup list)
 */
@Composable
fun BottomNavigationBar(
    isNorthLocked: Boolean,
    onSettingsClick: () -> Unit,
    onSettingsLongClick: () -> Unit,
    onMapsClick: () -> Unit,
    onPlacesClick: () -> Unit,
    onPlacesLongClick: () -> Unit,
    onCompassToggleClick: () -> Unit,
    onToolsLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(SlateDark800.copy(alpha = 0.96f))
            .border(1.dp, SurfaceBorderDark, containerShape)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("bottom_nav_bar"),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Settings
        BottomNavButton(
            label = "Settings",
            icon = Icons.Default.Settings,
            testTag = "nav_button_settings",
            onClick = onSettingsClick,
            onLongClick = onSettingsLongClick
        )

        // 2. Maps
        BottomNavButton(
            label = "Maps",
            icon = Icons.Default.Map,
            testTag = "nav_button_maps",
            onClick = onMapsClick,
            onLongClick = null
        )

        // 3. Places
        BottomNavButton(
            label = "Places",
            icon = Icons.Default.Place,
            testTag = "nav_button_places",
            onClick = onPlacesClick,
            onLongClick = onPlacesLongClick
        )

        // 4. Compass / Tools
        BottomNavButton(
            label = if (isNorthLocked) "North Lock" else "Compass",
            icon = if (isNorthLocked) Icons.Default.Lock else Icons.Default.Explore,
            testTag = "nav_button_compass_tools",
            isActive = isNorthLocked,
            onClick = onCompassToggleClick,
            onLongClick = onToolsLongClick
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BottomNavButton(
    label: String,
    icon: ImageVector,
    testTag: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) AccentTeal else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) AccentTeal else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
