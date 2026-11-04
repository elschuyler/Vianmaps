package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import com.example.ui.theme.SkyBlueBorder
import com.example.ui.theme.SkyBlueLight
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceWhiteTranslucent
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary

/**
 * Milestone 1.1 — Compact floating bottom navigation bar:
 * - Pure crisp white pill background.
 * - Sky blue highlights for selected / active states.
 * - Distinct individual buttons with comfortable touch targets.
 * - Keeps the existing button order: Settings, Maps, Places, Compass/Tools.
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
    val pillShape = RoundedCornerShape(24.dp)

    Row(
        modifier = modifier
            .wrapContentWidth()
            .clip(pillShape)
            .background(SurfaceWhiteTranslucent)
            .border(1.dp, SurfaceBorderLight, pillShape)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("bottom_nav_bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Settings
        NavPillButton(
            label = "Settings",
            icon = Icons.Default.Settings,
            testTag = "nav_button_settings",
            onClick = onSettingsClick,
            onLongClick = onSettingsLongClick
        )

        // 2. Maps
        NavPillButton(
            label = "Maps",
            icon = Icons.Default.Map,
            testTag = "nav_button_maps",
            onClick = onMapsClick,
            onLongClick = null
        )

        // 3. Places
        NavPillButton(
            label = "Places",
            icon = Icons.Default.Place,
            testTag = "nav_button_places",
            onClick = onPlacesClick,
            onLongClick = onPlacesLongClick
        )

        // 4. Compass / Tools (Sky-Blue highlighted when North Locked)
        NavPillButton(
            label = if (isNorthLocked) "Locked" else "Compass",
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
private fun NavPillButton(
    label: String,
    icon: ImageVector,
    testTag: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val buttonShape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .clip(buttonShape)
            .background(if (isActive) SkyBlueLight else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = if (isActive) SkyBlueBorder else androidx.compose.ui.graphics.Color.Transparent,
                shape = buttonShape
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) SkyBluePrimary else TextDarkPrimary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) SkyBluePrimary else TextDarkSecondary
        )
    }
}
