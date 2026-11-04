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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditLocation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.map.source.MapSourceRepository
import com.example.map.source.MapStyleDefinition
import com.example.ui.screens.ActiveModal
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentTeal
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SurfaceBorderDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalManager(
    activeModal: ActiveModal?,
    quickSettingsOpen: Boolean,
    currentStyle: MapStyleDefinition,
    onSelectStyle: (MapStyleDefinition) -> Unit,
    onDismissModal: () -> Unit,
    onDismissQuickSettings: () -> Unit,
    onOpenTool: (name: String, desc: String) -> Unit,
    onOpenMemoryPalace: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (quickSettingsOpen) {
        ModalBottomSheet(
            onDismissRequest = onDismissQuickSettings,
            containerColor = SlateDark800,
            sheetState = sheetState
        ) {
            QuickSettingsContent(onClose = onDismissQuickSettings)
        }
    }

    when (activeModal) {
        is ActiveModal.Settings -> {
            FullScreenModal(title = "Settings", onClose = onDismissModal) {
                SettingsScreenContent()
            }
        }
        is ActiveModal.MapsPopup -> {
            ModalBottomSheet(
                onDismissRequest = onDismissModal,
                containerColor = SlateDark800,
                sheetState = sheetState
            ) {
                MapsPopupContent(
                    currentStyle = currentStyle,
                    onSelectStyle = onSelectStyle,
                    onClose = onDismissModal
                )
            }
        }
        is ActiveModal.PlacesPopup -> {
            ModalBottomSheet(
                onDismissRequest = onDismissModal,
                containerColor = SlateDark800,
                sheetState = sheetState
            ) {
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
            ModalBottomSheet(
                onDismissRequest = onDismissModal,
                containerColor = SlateDark800,
                sheetState = sheetState
            ) {
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
            FullScreenModal(title = activeModal.name, onClose = onDismissModal) {
                ToolPlaceholderContent(name = activeModal.name, description = activeModal.description)
            }
        }
        null -> Unit
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
            .background(SlateDark900)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateDark800)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider(color = SurfaceBorderDark)
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun QuickSettingsContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Quick Settings",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Share Map Image action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { /* Handled below */ }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = AccentTeal)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Share Map Image (Visible Viewport)",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "[Pending MapLibre Viewport Surface Capture integration in Milestone 2]",
                    fontSize = 11.sp,
                    color = AccentAmber
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClose() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(12.dp))
            Text("Display & Gesture Calibration", color = MaterialTheme.colorScheme.onSurface)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SlateDark800,
            contentColor = AccentTeal
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> {
                Text("Base Styles (2D)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                MapSourceRepository.availableStyles.forEach { style ->
                    val isSelected = style.id == currentStyle.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) SlateDark700 else SlateDark800)
                            .clickable { onSelectStyle(style) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(style.displayName, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(style.attribution, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (style.isTemporaryDevelopmentStyle) {
                                Text("Dev Style: Non-persistent tile license", fontSize = 10.sp, color = AccentAmber)
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = AccentTeal)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("3D Mode: Not part of Milestone 1 (Planned for future release)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            1 -> {
                Text("Map Overlays & Layers", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Independent layers (weather radar, contour lines, hiking tracks) are currently unavailable and planned for future milestones.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            2 -> {
                Text("Custom Maps Workspace", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Custom MBTiles raster packs, GeoJSON imports, and offline packages will be configured here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PlacesPopupContent(
    onOpenMemoryPalace: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text("Places & Records", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(12.dp))

        PlaceItemRow(title = "Placemarks", icon = Icons.Default.PinDrop, subtitle = "0 saved landmarks")
        PlaceItemRow(title = "Routes", icon = Icons.Default.Route, subtitle = "0 recorded trails")
        PlaceItemRow(title = "Notes", icon = Icons.Default.Description, subtitle = "Spatial map notes")

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onOpenMemoryPalace,
            colors = ButtonDefaults.buttonColors(containerColor = SlateDark700),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Bookmark, contentDescription = null, tint = AccentTeal)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Memory Palace Workspace", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PlaceItemRow(title: String, icon: ImageVector, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AccentTeal)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text("Tools Menu", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tools) { (name, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectTool(name, desc) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreenContent() {
    Column {
        Text("Application Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("MapLibre Native Android Engine: Version 13.7.1", color = MaterialTheme.colorScheme.onSurface)
        Text("Rendering Mode: 2D Orthographic MapView", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Tile Cache Policy: Normal visible session cache (No bulk prefetching)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Data & Privacy Boundaries", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("• No cloud accounts, server sync, or external tracking", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("• Location is accessed ONLY when explicitly enabled by user", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("• User records isolated into managed storage boundary", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MemoryPalaceContent() {
    Column {
        Text("Memory Palace Workspace (Starting in Notes)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Milestone 1 Placeholder:", fontWeight = FontWeight.SemiBold, color = AccentTeal)
        Text("The full Spatial Notes and Memory Palace editor will be implemented in future milestones. Structured records will bind directly to the shared Room database.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ToolPlaceholderContent(name: String, description: String) {
    Column {
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Feature scheduled for upcoming release phase. Clean architecture boundaries and repository bindings are in place.", fontSize = 12.sp, color = AccentAmber)
    }
}
