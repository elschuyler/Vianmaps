package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.map.source.MapSourceRepository
import com.example.map.source.MapStyleDefinition
import com.example.ui.screens.ActiveModal
import com.example.ui.theme.IndicatorAlert
import com.example.ui.theme.SkyBlueBorder
import com.example.ui.theme.SkyBlueDim
import com.example.ui.theme.SkyBlueLight
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceOffWhite
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.SurfaceWhiteTranslucent
import com.example.ui.theme.TextDarkMuted
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary

import androidx.compose.material.icons.filled.Terminal
import com.example.ui.screens.MapUiState

@Composable
fun AppModalManager(
    activeModal: ActiveModal?,
    quickSettingsOpen: Boolean,
    currentStyle: MapStyleDefinition,
    uiState: MapUiState,
    isDeviceOnline: Boolean,
    connectionType: String,
    onSelectStyle: (MapStyleDefinition) -> Unit,
    onDismissModal: () -> Unit,
    onDismissQuickSettings: () -> Unit,
    onOpenTool: (name: String, desc: String) -> Unit,
    onOpenMemoryPalace: () -> Unit,
    onOpenLogKeeper: () -> Unit,
    onBackToSettings: () -> Unit
) {
    if (quickSettingsOpen) {
        FloatingPopupDialog(onDismiss = onDismissQuickSettings) {
            QuickSettingsContent(onClose = onDismissQuickSettings)
        }
    }

    when (activeModal) {
        is ActiveModal.Settings -> {
            FullScreenModal(title = "Settings", onClose = onDismissModal) {
                SettingsScreenContent(
                    onOpenLogKeeper = {
                        onDismissModal()
                        onOpenLogKeeper()
                    }
                )
            }
        }
        is ActiveModal.LogKeeper -> {
            FullScreenModal(title = "Log Keeper", onClose = onBackToSettings) {
                LogKeeperScreenContent(
                    uiState = uiState,
                    isDeviceOnline = isDeviceOnline,
                    connectionType = connectionType
                )
            }
        }
        is ActiveModal.MapsPopup -> {
            FloatingPopupDialog(onDismiss = onDismissModal) {
                MapsPopupContent(
                    currentStyle = currentStyle,
                    onSelectStyle = onSelectStyle,
                    onClose = onDismissModal
                )
            }
        }
        is ActiveModal.PlacesPopup -> {
            FloatingPopupDialog(onDismiss = onDismissModal) {
                PlacesPopupContent(
                    onOpenMemoryPalace = {
                        onDismissModal()
                        onOpenMemoryPalace()
                    },
                    onClose = onDismissModal
                )
            }
        }
        is ActiveModal.MemoryPalaceWorkspace -> {
            FullScreenModal(title = "Memory Palace (Spatial Notes)", onClose = onDismissModal) {
                MemoryPalaceContent()
            }
        }
        is ActiveModal.ToolsPopup -> {
            FloatingPopupDialog(onDismiss = onDismissModal) {
                ToolsPopupContent(
                    onSelectTool = { name, desc ->
                        onDismissModal()
                        onOpenTool(name, desc)
                    },
                    onClose = onDismissModal
                )
            }
        }
        is ActiveModal.PlaceholderTool -> {
            FloatingPopupDialog(onDismiss = onDismissModal) {
                ToolPlaceholderContent(name = activeModal.name, description = activeModal.description, onClose = onDismissModal)
            }
        }
        null -> Unit
    }
}

/**
 * In-Canvas Anchored Popover Panel:
 * - Appears as a compact card anchored directly above the bottom navigation bar.
 * - Leaves the map and right-side zoom controls visible and fully interactive.
 * - Zero window-dimming scrim.
 * - Pure white surface with crisp borders, rounded 16dp corners, and Sky Blue accents.
 * - Fullscreen transparent backdrop allows effortless dismissal on tap outside.
 */
@Composable
fun FloatingPopupDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val popoverShape = RoundedCornerShape(16.dp)

    // Transparent non-dimming backdrop to capture outside taps for dismissal
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        // Popover card positioned directly above the left-anchored bottom navigation bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 8.dp, bottom = 58.dp)
                .widthIn(min = 280.dp, max = 340.dp)
                .heightIn(max = 420.dp)
                .clip(popoverShape)
                .background(SurfaceWhite)
                .border(1.dp, SurfaceBorderLight, popoverShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = true,
                    onClick = { /* Consume clicks inside card to prevent dismiss */ }
                )
                .padding(14.dp)
        ) {
            content()
        }
    }
}

@Composable
fun FullScreenModal(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceWhite)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextDarkPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
            }
            HorizontalDivider(color = SurfaceBorderLight)
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun QuickSettingsContent(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick Settings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Share Map Image action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceOffWhite)
                .clickable { /* Action */ }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Share Map Image (Visible Viewport)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextDarkPrimary
                )
                Text(
                    text = "[Pending MapLibre Viewport Surface Capture integration in Milestone 2]",
                    fontSize = 10.sp,
                    color = SkyBlueDim
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceOffWhite)
                .clickable { onClose() }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Display & Gesture Calibration", fontSize = 13.sp, color = TextDarkPrimary)
        }
    }
}

@Composable
fun MapsPopupContent(
    currentStyle: MapStyleDefinition,
    onSelectStyle: (MapStyleDefinition) -> Unit,
    onClose: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Maps", "Layers", "Custom")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Map Sources & Styles",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SurfaceOffWhite,
            contentColor = SkyBluePrimary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == index) SkyBluePrimary else TextDarkSecondary
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            0 -> {
                Text("Base Styles (2D)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                MapSourceRepository.availableStyles.forEach { style ->
                    val isSelected = style.id == currentStyle.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) SkyBlueLight else SurfaceOffWhite)
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) SkyBlueBorder else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectStyle(style) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                style.displayName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) SkyBlueDim else TextDarkPrimary
                            )
                            Text(style.attribution, fontSize = 10.sp, color = TextDarkSecondary)
                            if (style.isTemporaryDevelopmentStyle) {
                                Text("Dev Style: Non-persistent tile license", fontSize = 9.sp, color = TextDarkMuted)
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = SkyBluePrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = TextDarkMuted, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("3D Mode: Planned for future milestone", fontSize = 10.sp, color = TextDarkMuted)
                }
            }
            1 -> {
                Text("Map Overlays & Layers", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Independent layers (weather radar, contour lines, hiking tracks) are currently unavailable in Milestone 1.", fontSize = 11.sp, color = TextDarkSecondary)
            }
            2 -> {
                Text("Custom Maps Workspace", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Custom MBTiles raster packs, GeoJSON imports, and offline packages will be configured here.", fontSize = 11.sp, color = TextDarkSecondary)
            }
        }
    }
}

@Composable
fun PlacesPopupContent(
    onOpenMemoryPalace: () -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Places & Records", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        PlaceItemRow(title = "Placemarks", icon = Icons.Default.PinDrop, subtitle = "0 saved landmarks")
        PlaceItemRow(title = "Routes", icon = Icons.Default.Route, subtitle = "0 recorded trails")
        PlaceItemRow(title = "Notes", icon = Icons.Default.Description, subtitle = "Spatial map notes")

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onOpenMemoryPalace,
            colors = ButtonDefaults.buttonColors(
                containerColor = SkyBluePrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Memory Palace Workspace", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlaceItemRow(title: String, icon: ImageVector, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceOffWhite)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDarkPrimary)
            Text(subtitle, fontSize = 10.sp, color = TextDarkSecondary)
        }
    }
}

@Composable
fun ToolsPopupContent(
    onSelectTool: (name: String, desc: String) -> Unit,
    onClose: () -> Unit
) {
    val tools = listOf(
        Pair("Search", "Search locations, coordinates, and placemarks"),
        Pair("Highlight", "Area and polygon boundary selection"),
        Pair("Measure", "Distance and terrain elevation calculation"),
        Pair("Navigate", "Compass bearings and azimuth waypoints"),
        Pair("Weather", "Atmospheric pressure and radar preview"),
        Pair("Field Tools", "Surveying grid, offset markers, and dead reckoning"),
        Pair("Signal & Information", "RF telemetry, network diagnostics, and GPS status")
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tools Menu", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(tools) { (name, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceOffWhite)
                        .clickable { onSelectTool(name, desc) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDarkPrimary)
                        Text(desc, fontSize = 10.sp, color = TextDarkSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreenContent(onOpenLogKeeper: () -> Unit = {}) {
    Column {
        Text("Application Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SkyBluePrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("MapLibre Native Android Engine: Version 13.7.1", fontSize = 12.sp, color = TextDarkPrimary)
        Text("Rendering Mode: 2D Orthographic MapView", fontSize = 11.sp, color = TextDarkSecondary)
        Text("Tile Cache Policy: Normal visible session cache (No bulk prefetching)", fontSize = 11.sp, color = TextDarkSecondary)

        Spacer(modifier = Modifier.height(16.dp))
        Text("System Diagnostics & Telemetry", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SkyBluePrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceOffWhite)
                .border(1.dp, SkyBlueBorder, RoundedCornerShape(12.dp))
                .clickable { onOpenLogKeeper() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Terminal, contentDescription = "Log Keeper", tint = SkyBluePrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Open Log Keeper Workspace",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
                Text(
                    text = "Live diagnostic ring buffer, MapLibre SDK events, filtered search & export",
                    fontSize = 10.sp,
                    color = TextDarkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Data & Privacy Boundaries", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SkyBluePrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("• No cloud accounts, server sync, or external tracking", fontSize = 11.sp, color = TextDarkSecondary)
        Text("• Location is accessed ONLY when explicitly enabled by user", fontSize = 11.sp, color = TextDarkSecondary)
        Text("• User records isolated into managed storage boundary", fontSize = 11.sp, color = TextDarkSecondary)
    }
}

@Composable
fun MemoryPalaceContent() {
    Column {
        Text("Memory Palace Workspace (Starting in Notes)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Milestone 1 Placeholder:", fontWeight = FontWeight.SemiBold, color = SkyBluePrimary)
        Text("The full Spatial Notes and Memory Palace editor will be implemented in future milestones. Structured records will bind directly to the shared Room database.", fontSize = 12.sp, color = TextDarkSecondary)
    }
}

@Composable
fun ToolPlaceholderContent(name: String, description: String, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(description, fontSize = 12.sp, color = TextDarkSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Feature scheduled for upcoming release phase. Clean architecture boundaries and repository bindings are in place.", fontSize = 11.sp, color = SkyBlueDim)
    }
}
