package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SkyBluePrimary

/**
 * Compact Floating Bottom Navigation Bar:
 * Restored to the 4 original Milestone 1 buttons:
 * 1. Tune / Settings: Tap -> Settings, Long-press -> Quick Settings popover
 * 2. Map: Tap -> Maps & Layers popover
 * 3. PinDrop / Places: Tap -> Places popover, Long-press -> Memory Palace workspace
 * 4. Compass / Tools: Tap -> Toggle North Lock, Long-press -> Tools popover
 *
 * Features:
 * - Ultra-compact, content-tight bar floating 8dp from bottom-left
 * - Enhanced 45% translucency (Color(0x731E293B)) so map imagery shines through
 * - Crisp 0.5dp frosted outline border
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
    val barShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .wrapContentWidth()
            .clip(barShape)
            .background(Color(0x731E293B)) // 45% translucent dark slate for high map visibility
            .border(BorderStroke(0.5.dp, Color(0x44FFFFFF)), barShape)
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .testTag("bottom_nav_bar"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Settings / Tune (Tap -> Settings, Long-press -> Quick Settings)
            BottomBarIconButton(
                icon = Icons.Default.Tune,
                contentDescription = "Settings & Preferences",
                testTag = "nav_button_settings",
                onClick = onSettingsClick,
                onLongClick = onSettingsLongClick
            )

            // 2. Maps & Layers (Tap -> Maps Popover)
            BottomBarIconButton(
                icon = Icons.Default.Map,
                contentDescription = "Map Styles & Layers",
                testTag = "nav_button_maps",
                onClick = onMapsClick
            )

            // 3. Places / Waypoints (Tap -> Places Popover, Long-press -> Memory Palace)
            BottomBarIconButton(
                icon = Icons.Default.PinDrop,
                contentDescription = "Places & Waypoints",
                testTag = "nav_button_places",
                onClick = onPlacesClick,
                onLongClick = onPlacesLongClick
            )

            // 4. Compass / Tools (Tap -> North Lock, Long-press -> Tools Popover)
            BottomBarIconButton(
                icon = if (isNorthLocked) Icons.Default.Lock else Icons.Default.Explore,
                contentDescription = "Compass & Tools",
                testTag = "nav_button_compass_tools",
                tint = if (isNorthLocked) SkyBluePrimary else Color.White,
                onClick = onCompassToggleClick,
                onLongClick = onToolsLongClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(26.dp)
        )
    }
}
