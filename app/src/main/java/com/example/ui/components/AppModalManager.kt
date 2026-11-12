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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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

import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Layers
import com.example.map.composition.MapComposition
import androidx.compose.material.icons.filled.Terminal
import com.example.ui.screens.MapUiState

@Composable
fun AppModalManager(
    activeModal: ActiveModal?,
    settingsPopupOpen: Boolean,
    currentStyle: MapStyleDefinition,
    uiState: MapUiState,
    isDeviceOnline: Boolean,
    connectionType: String,
    onSelectStyle: (MapStyleDefinition) -> Unit,
    onSelectComposition: (MapComposition) -> Unit,
    onSaveComposition: (MapComposition) -> Unit,
    onDeleteComposition: (String) -> Unit,
    onDuplicateComposition: (MapComposition) -> Unit,
    onTogglePinComposition: (String) -> Unit,
    onRefreshStorageReport: () -> Unit,
    onClearProviderCache: (String) -> Unit,
    onDismissModal: () -> Unit,
    onDismissSettingsPopup: () -> Unit,
    onOpenMoreSettings: () -> Unit,
    onOpenMapCatalogue: () -> Unit,
    onBackFromMapCatalogue: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenTool: (name: String, desc: String) -> Unit,
    onOpenMemoryPalace: () -> Unit,
    onOpenLogKeeper: () -> Unit
) {
    if (settingsPopupOpen) {
        FloatingPopupDialog(onDismiss = onDismissSettingsPopup) {
            SettingsPopupContent(
                onOpenBackupRestore = onOpenBackupRestore,
                onOpenLogKeeper = onOpenLogKeeper,
                onOpenMoreSettings = onOpenMoreSettings,
                onClose = onDismissSettingsPopup
            )
        }
    }

    when (activeModal) {
        is ActiveModal.MoreSettings -> {
            FullScreenModal(title = "More Settings", onClose = onDismissModal) {
                MoreSettingsWorkspace(onOpenMapCatalogue = onOpenMapCatalogue)
            }
        }
        is ActiveModal.MapCatalogue -> {
            FullScreenModal(title = "Map Catalogue & Setups", onClose = onBackFromMapCatalogue) {
                MapCatalogueWorkspace(
                    uiState = uiState,
                    onSelectComposition = { comp ->
                        onSelectComposition(comp)
                        onDismissModal()
                    },
                    onSaveComposition = onSaveComposition,
                    onDeleteComposition = onDeleteComposition,
                    onDuplicateComposition = onDuplicateComposition,
                    onTogglePinComposition = onTogglePinComposition,
                    onRefreshStorage = onRefreshStorageReport,
                    onClearProviderCache = onClearProviderCache,
                    onBack = onBackFromMapCatalogue
                )
            }
        }
        is ActiveModal.BackupRestore -> {
            FullScreenModal(title = "Backup & Restore", onClose = onDismissModal) {
                BackupRestoreContent(onBack = onDismissModal)
            }
        }
        is ActiveModal.LogKeeper -> {
            FullScreenModal(title = "Log Keeper", onClose = onDismissModal) {
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
                    uiState = uiState,
                    onSelectComposition = { comp ->
                        onDismissModal()
                        onSelectComposition(comp)
                    },
                    onOpenMapCatalogue = {
                        onDismissModal()
                        onOpenMapCatalogue()
                    },
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
                .padding(10.dp)
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
fun SettingsPopupContent(
    onOpenBackupRestore: () -> Unit,
    onOpenLogKeeper: () -> Unit,
    onOpenMoreSettings: () -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Settings",
                    tint = TextDarkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 1. Backup & Restore (Planned / Not yet available badge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceOffWhite)
                .clickable {
                    onClose()
                    onOpenBackupRestore()
                }
                .padding(horizontal = 10.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = "Backup & Restore",
                tint = SkyBluePrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Backup & Restore",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDarkPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SkyBlueLight)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "Planned",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBlueDim
                        )
                    }
                }
                Text(
                    text = "Local archive export & restore (Planned milestone)",
                    fontSize = 10.sp,
                    color = TextDarkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // 2. Log Keeper
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceOffWhite)
                .clickable {
                    onClose()
                    onOpenLogKeeper()
                }
                .padding(horizontal = 10.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = "Log Keeper",
                tint = SkyBluePrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Log Keeper",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDarkPrimary
                )
                Text(
                    text = "Live diagnostic events, system telemetry & export",
                    fontSize = 10.sp,
                    color = TextDarkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // 3. More Settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceOffWhite)
                .clickable {
                    onClose()
                    onOpenMoreSettings()
                }
                .padding(horizontal = 10.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "More Settings",
                tint = SkyBluePrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "More Settings",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDarkPrimary
                )
                Text(
                    text = "Preferences, display, units & boundaries",
                    fontSize = 10.sp,
                    color = TextDarkSecondary
                )
            }
        }
    }
}

@Composable
fun MapsPopupContent(
    uiState: MapUiState,
    onSelectComposition: (MapComposition) -> Unit,
    onOpenMapCatalogue: () -> Unit,
    onClose: () -> Unit
) {
    val pinnedCompositions = remember(uiState.savedCompositions) {
        val pinned = uiState.savedCompositions.filter { it.isPinnedToQuickChoice }
        if (pinned.isEmpty()) uiState.savedCompositions else pinned
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Map Setups",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = "Quick Choices",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextDarkSecondary
        )

        Spacer(modifier = Modifier.height(3.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
        ) {
            items(pinnedCompositions, key = { it.id }) { comp ->
                val isSelected = comp.id == uiState.activeComposition.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) SkyBlueLight else SurfaceOffWhite)
                        .border(
                            width = if (isSelected) 1.dp else 0.dp,
                            color = if (isSelected) SkyBlueBorder else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectComposition(comp) }
                        .padding(horizontal = 10.dp, vertical = 2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = comp.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) SkyBlueDim else TextDarkPrimary
                        )
                        Text(
                            text = "${comp.baseStyle.displayName} • ${if (comp.showRoads) "Roads" else "No Roads"}",
                            fontSize = 10.sp,
                            color = TextDarkSecondary
                        )
                    }
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = SkyBluePrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(4.dp))

        // Direct action button to Map Catalogue
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceOffWhite)
                .clickable { onOpenMapCatalogue() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = SkyBluePrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Map Catalogue & Setups",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyBlueDim
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = SkyBlueDim,
                modifier = Modifier.size(14.dp)
            )
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
        Spacer(modifier = Modifier.height(3.dp))

        PlaceItemRow(title = "Placemarks", icon = Icons.Default.PinDrop, subtitle = "0 saved landmarks", onClick = onOpenMemoryPalace)
        Spacer(modifier = Modifier.height(2.dp))
        PlaceItemRow(title = "Routes", icon = Icons.Default.Route, subtitle = "0 recorded trails", onClick = onOpenMemoryPalace)
        Spacer(modifier = Modifier.height(2.dp))
        PlaceItemRow(title = "Notes", icon = Icons.Default.Description, subtitle = "Spatial map notes", onClick = onOpenMemoryPalace)

        Spacer(modifier = Modifier.height(4.dp))
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
private fun PlaceItemRow(
    title: String,
    icon: ImageVector,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceOffWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDarkPrimary)
            Text(subtitle, fontSize = 10.sp, color = TextDarkSecondary)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Open $title",
            tint = TextDarkSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp)
        )
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
        Spacer(modifier = Modifier.height(3.dp))

        LazyColumn(
            modifier = Modifier.heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp)
        ) {
            items(tools) { (name, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceOffWhite)
                        .clickable { onSelectTool(name, desc) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
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
fun MoreSettingsWorkspace(onOpenMapCatalogue: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Map Catalogue & Setups Navigation Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SkyBlueLight)
                .border(1.dp, SkyBlueBorder, RoundedCornerShape(12.dp))
                .clickable { onOpenMapCatalogue() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = null,
                tint = SkyBlueDim,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Map Catalogue & Setups",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyBlueDim
                )
                Text(
                    text = "Saved compositions, layer builder, source directory & offline storage",
                    fontSize = 11.sp,
                    color = TextDarkSecondary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open",
                tint = SkyBlueDim,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "System & Engine Configuration",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = SkyBluePrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 1. Map Display & Projection
        SettingsSectionCard {
            Text(
                text = "Map Display & Projection",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsInfoRow(label = "Native Engine", value = "MapLibre Native Android v13.7.1")
            SettingsInfoRow(label = "Projection", value = "2D Orthographic MapView")
            SettingsInfoRow(label = "Default Base Style", value = "Satellite Hybrid (ESRI + OSM Vector)")
            SettingsInfoRow(label = "Display Calibration", value = "Standard System Display")
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Units & Coordinates
        SettingsSectionCard {
            Text(
                text = "Units & Coordinate Formats",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsInfoRow(label = "Coordinate System", value = "DMS (Deg Min Sec) & DD")
            SettingsInfoRow(label = "Scale Indicator", value = "Stepped Bracket & Ratio Ruler")
            SettingsInfoRow(label = "Elevation Datum", value = "Meters Above Sea Level (MSL)")
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Offline Storage & Visible Tiles
        SettingsSectionCard {
            Text(
                text = "Offline Storage & Tile Retention",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsInfoRow(label = "Retention Mode", value = "Tile-by-tile visible retention")
            SettingsInfoRow(label = "Database Engine", value = "Room SQLite (Deduplicated)")
            SettingsInfoRow(label = "Routing Engine", value = "Graph index prepared (Engine planned)")
            SettingsInfoRow(label = "Ambient Disk Cache", value = "500 MB quota (No bulk packs)")
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. Network Policy & Device Privacy
        SettingsSectionCard {
            Text(
                text = "Network Policy & Device Privacy",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsInfoRow(label = "In-App Internet", value = "Hardware & Software Gate Controlled")
            SettingsInfoRow(label = "Telemetry / Analytics", value = "Strict Zero (No remote servers)")
            SettingsInfoRow(label = "Cloud Sync", value = "Disabled (Local persistence only)")
            SettingsInfoRow(label = "Location Access", value = "Runtime only (Standby when idle)")
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. About & Attribution
        SettingsSectionCard {
            Text(
                text = "About Vian Maps",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            SettingsInfoRow(label = "Application", value = "Vian Maps Android")
            SettingsInfoRow(label = "MapLibre Native", value = "v13.7.1 (ARM64-v8a / x86_64)")
            SettingsInfoRow(label = "Map Vector Data", value = "© OpenStreetMap contributors")
            SettingsInfoRow(label = "Tile Hosting", value = "OpenFreeMap")
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun BackupRestoreContent(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Status header card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SkyBlueLight)
                .border(1.dp, SkyBlueBorder, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = SkyBlueDim,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Feature Scheduled: Future Milestone",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyBlueDim
                )
                Text(
                    text = "Backup & Restore is intentionally not yet available.",
                    fontSize = 11.sp,
                    color = TextDarkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Planned Architectural Implementation",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSectionCard {
            Text(
                text = "Vian Maps strictly adheres to a local-first, zero-cloud architecture. When this milestone is implemented:",
                fontSize = 12.sp,
                color = TextDarkPrimary,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            BulletPointText(
                title = "Encrypted Local File Archive",
                description = "User-authored content (Memory Palace spatial notes, saved waypoints, and custom routes) will be exportable to a single encrypted file on device storage."
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletPointText(
                title = "Offline Package Restoration",
                description = "Restoring an archive will verify checksums and rebuild the local Room database indices without external network requests."
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletPointText(
                title = "Zero Remote Server Dependency",
                description = "No mandatory user accounts, cloud syncing services, or third-party storage providers will ever be required."
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Current State Verification",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = SkyBluePrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "No simulated or mock restore operations are active.",
                    fontSize = 11.sp,
                    color = TextDarkSecondary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = SkyBluePrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "All live data remains safely contained in local Room storage.",
                    fontSize = 11.sp,
                    color = TextDarkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = SkyBluePrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Return to Map", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun SettingsSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        content()
    }
}

@Composable
internal fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = TextDarkSecondary)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextDarkPrimary)
    }
}

@Composable
private fun BulletPointText(title: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("• ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SkyBluePrimary)
        Column {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextDarkPrimary)
            Text(text = description, fontSize = 11.sp, color = TextDarkSecondary, lineHeight = 15.sp)
        }
    }
}

@Composable
fun MemoryPalaceContent() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Overview", "Placemarks", "Routes", "Notes")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Memory Palace & Spatial Records",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Local-first spatial inventory and geographic notes bound to Room SQLite database.",
            fontSize = 12.sp,
            color = TextDarkSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SurfaceOffWhite,
            contentColor = SkyBluePrimary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) SkyBluePrimary else TextDarkSecondary
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (selectedTab) {
            0 -> {
                SettingsSectionCard {
                    Text("Spatial Inventory Summary", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsInfoRow(label = "Saved Placemarks", value = "0 records")
                    SettingsInfoRow(label = "Recorded Routes", value = "0 trails")
                    SettingsInfoRow(label = "Spatial Notes", value = "0 annotations")
                    SettingsInfoRow(label = "Local Storage Engine", value = "Room SQLite (Local-first)")
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionCard {
                    Text("Architectural Guarantees", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    BulletPointText(
                        title = "Zero Cloud Leakage",
                        description = "All coordinates, pins, and personal map notes reside exclusively on your physical device storage."
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    BulletPointText(
                        title = "Tile Cache Independence",
                        description = "User spatial records are decoupled from tile caches. Purging map tile caches never modifies or deletes your places."
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    BulletPointText(
                        title = "Route Tracks vs. Routing Engine",
                        description = "Saved routes record your actual GPS travel geometry. Turn-by-turn routing calculation requires an active routing engine (planned in future milestones)."
                    )
                }
            }
            1 -> {
                SettingsSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Saved Placemarks", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                        Surface(color = SkyBlueDim, shape = RoundedCornerShape(4.dp)) {
                            Text("0 Items", fontSize = 10.sp, color = SkyBluePrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No saved placemarks yet. Tap on any coordinate or POI on the main map to bookmark locations as named waypoints.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
            2 -> {
                SettingsSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recorded Route Tracks", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                        Surface(color = SkyBlueDim, shape = RoundedCornerShape(4.dp)) {
                            Text("0 Tracks", fontSize = 10.sp, color = SkyBluePrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No GPS route tracks recorded yet. Track recording logs breadcrumb coordinate geometry directly to local storage.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Note: Saved routes represent recorded geometry, not an offline turn-by-turn routing graph calculation.",
                        fontSize = 11.sp,
                        color = TextDarkMuted,
                        lineHeight = 15.sp
                    )
                }
            }
            3 -> {
                SettingsSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Spatial Field Notes", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                        Surface(color = SkyBlueDim, shape = RoundedCornerShape(4.dp)) {
                            Text("0 Notes", fontSize = 10.sp, color = SkyBluePrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No spatial notes recorded. The Memory Palace editor lets you attach field observations, terrain photos, and descriptions to exact geographic coordinates.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = SkyBlueDim,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "Future Milestone",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyBluePrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(description, fontSize = 12.sp, color = TextDarkSecondary)
        Spacer(modifier = Modifier.height(10.dp))

        val detailedNote = when (name.lowercase()) {
            "search" -> "Offline search will query cached OpenStreetMap POIs, addresses, and custom Placemarks directly from the local Room database."
            "navigate" -> "Navigation displays sensor compass azimuth and bearing waypoints. Turn-by-turn routing calculation requires a routing engine graph."
            "measure" -> "Calculates geodesic distances and polygon surface areas using WGS84 ellipsoidal geometry."
            "highlight" -> "Allows defining polygonal bounding areas and geographic zones on top of active vector tiles."
            "weather" -> "Atmospheric pressure and local barometric sensor readings will display alongside weather tile layers."
            "field tools" -> "Grid offsets, MGRS/UTM coordinate conversions, and compass resection markers for field work."
            else -> "Feature scheduled for upcoming release phase. Clean architecture boundaries and repository bindings are in place."
        }

        Text(
            text = detailedNote,
            fontSize = 11.sp,
            color = TextDarkSecondary,
            lineHeight = 15.sp
        )
    }
}
