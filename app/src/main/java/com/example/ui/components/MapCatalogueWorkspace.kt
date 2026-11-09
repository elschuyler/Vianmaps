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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.map.composition.MapComposition
import com.example.map.composition.MapCompositionDefaults
import com.example.map.source.MapSourceRepository
import com.example.ui.screens.MapUiState
import com.example.ui.theme.IndicatorAlert
import com.example.ui.theme.SkyBlueBorder
import com.example.ui.theme.SkyBlueDim
import com.example.ui.theme.SkyBlueLight
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceOffWhite
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.TextDarkMuted
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary

/**
 * Full-screen Map Catalogue & Saved Compositions workspace.
 * Allows managing saved setups, building custom overlay combinations,
 * inspecting supported map sources/licensing, and auditing on-device storage.
 */
@Composable
fun MapCatalogueWorkspace(
    uiState: MapUiState,
    onSelectComposition: (MapComposition) -> Unit,
    onSaveComposition: (MapComposition) -> Unit,
    onDeleteComposition: (String) -> Unit,
    onDuplicateComposition: (MapComposition) -> Unit,
    onTogglePinComposition: (String) -> Unit,
    onRefreshStorage: () -> Unit,
    onClearProviderCache: (String) -> Unit,
    onBack: () -> Unit
) {
    var editingCompositionId by remember { mutableStateOf<String?>(null) }
    var builderName by remember { mutableStateOf("") }
    var builderDescription by remember { mutableStateOf("") }
    var builderBaseStyleId by remember { mutableStateOf(MapCompositionDefaults.defaultComposition.baseStyleId) }
    var builderShowRoads by remember { mutableStateOf(true) }
    var builderShowLabels by remember { mutableStateOf(true) }
    var builderShowPois by remember { mutableStateOf(true) }
    var builderShowBoundaries by remember { mutableStateOf(true) }
    var builderShowBuildings by remember { mutableStateOf(true) }
    var builderSatelliteOpacity by remember { mutableFloatStateOf(1.0f) }

    // Dialog state for deletion confirmation
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingClearProviderId by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    fun loadIntoBuilder(composition: MapComposition) {
        editingCompositionId = composition.id
        builderName = composition.name
        builderDescription = composition.description
        builderBaseStyleId = composition.baseStyleId
        builderShowRoads = composition.showRoads
        builderShowLabels = composition.showLabels
        builderShowPois = composition.showPois
        builderShowBoundaries = composition.showBoundaries
        builderShowBuildings = composition.showBuildings
        builderSatelliteOpacity = composition.satelliteOpacity
    }

    fun resetBuilder() {
        editingCompositionId = null
        builderName = ""
        builderDescription = ""
        builderBaseStyleId = "hybrid_satellite_osm"
        builderShowRoads = true
        builderShowLabels = true
        builderShowPois = true
        builderShowBoundaries = true
        builderShowBuildings = true
        builderSatelliteOpacity = 1.0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
    ) {
        // Back Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to More Settings",
                    tint = TextDarkPrimary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "Map Catalogue & Setups",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
                Text(
                    text = "Compositions, layer builder & offline storage",
                    fontSize = 11.sp,
                    color = TextDarkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // SECTION 1: SAVED COMPOSITIONS & QUICK-CHOICES
        // ==========================================
        SectionHeader(
            title = "Saved Map Compositions",
            subtitle = "Pinned setups appear in the bottom-bar Map popup quick choices"
        )

        Spacer(modifier = Modifier.height(8.dp))

        uiState.savedCompositions.forEach { comp ->
            val isActive = comp.id == uiState.activeComposition.id
            val isEditing = comp.id == editingCompositionId

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isActive) SkyBlueLight else SurfaceOffWhite)
                    .border(
                        width = if (isActive || isEditing) 1.5.dp else 1.dp,
                        color = if (isEditing) SkyBlueDim else if (isActive) SkyBlueBorder else SurfaceBorderLight,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = comp.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDarkPrimary
                            )
                            if (isActive) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SkyBluePrimary)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SurfaceWhite
                                    )
                                }
                            }
                        }
                        if (comp.description.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = comp.description,
                                fontSize = 11.sp,
                                color = TextDarkSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Base: ${comp.baseStyle.displayName} • Layers: " +
                                listOfNotNull(
                                    if (comp.showRoads) "Roads" else null,
                                    if (comp.showLabels) "Labels" else null,
                                    if (comp.showPois) "POIs" else null,
                                    if (comp.showBoundaries) "Boundaries" else null,
                                    if (comp.showBuildings) "Buildings" else null
                                ).joinToString(", "),
                            fontSize = 10.sp,
                            color = TextDarkMuted
                        )
                    }

                    // Quick-Choice Pin toggle
                    IconButton(
                        onClick = { onTogglePinComposition(comp.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = if (comp.isPinnedToQuickChoice) "Pinned to Quick Choices" else "Unpinned",
                            tint = if (comp.isPinnedToQuickChoice) SkyBluePrimary else TextDarkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isActive) {
                        OutlinedButton(
                            onClick = { onSelectComposition(comp) },
                            modifier = Modifier.height(30.dp),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Text("Activate", fontSize = 11.sp, color = SkyBluePrimary)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = { loadIntoBuilder(comp) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit in Builder",
                            tint = SkyBlueDim,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = { onDuplicateComposition(comp) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate Setup",
                            tint = TextDarkSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Delete only allowed if more than 1 composition exists
                    if (uiState.savedCompositions.size > 1) {
                        IconButton(
                            onClick = { pendingDeleteId = comp.id },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Composition",
                                tint = IndicatorAlert,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // SECTION 2: MAP COMPOSITION BUILDER
        // ==========================================
        SectionHeader(
            title = if (editingCompositionId != null) "Edit Composition" else "Create New Composition",
            subtitle = "Combine compatible base maps and overlay feature layers"
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsSectionCard {
            OutlinedTextField(
                value = builderName,
                onValueChange = { builderName = it },
                label = { Text("Composition Name") },
                placeholder = { Text("e.g. Hiking & Boundaries") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = builderDescription,
                onValueChange = { builderDescription = it },
                label = { Text("Description (Optional)") },
                placeholder = { Text("e.g. High-contrast trails with satellite imagery") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("Base Map Style", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            Spacer(modifier = Modifier.height(6.dp))

            MapSourceRepository.availableStyles.forEach { style ->
                val isSelected = style.id == builderBaseStyleId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) SkyBlueLight else SurfaceOffWhite)
                        .border(
                            width = if (isSelected) 1.dp else 0.5.dp,
                            color = if (isSelected) SkyBlueBorder else SurfaceBorderLight,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { builderBaseStyleId = style.id }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = style.displayName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) SkyBlueDim else TextDarkPrimary
                        )
                        Text(
                            text = "${style.providerName} • ${if (style.isCompositeHybrid) "Hybrid Satellite" else "Vector Map"}",
                            fontSize = 10.sp,
                            color = TextDarkSecondary
                        )
                    }
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Compatibility explanation
            val selectedBaseStyle = MapSourceRepository.getStyleById(builderBaseStyleId)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SkyBlueLight)
                    .border(1.dp, SkyBlueBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = SkyBlueDim, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedBaseStyle.isCompositeHybrid) {
                            "Satellite Base: Satellite imagery is composited beneath OpenStreetMap vector overlays."
                        } else {
                            "Vector Base: Lightweight vector style. Toggling layers adjusts the corresponding OSM features."
                        },
                        fontSize = 10.sp,
                        color = TextDarkPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Overlays & Feature Layers", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            Spacer(modifier = Modifier.height(4.dp))

            LayerCheckboxRow("OpenStreetMap Roads & Highways", builderShowRoads) { builderShowRoads = it }
            LayerCheckboxRow("Place & Road Labels", builderShowLabels) { builderShowLabels = it }
            LayerCheckboxRow("Points of Interest (POIs)", builderShowPois) { builderShowPois = it }
            LayerCheckboxRow("Administrative Boundaries", builderShowBoundaries) { builderShowBoundaries = it }
            LayerCheckboxRow("Building Footprints", builderShowBuildings) { builderShowBuildings = it }

            if (selectedBaseStyle.isCompositeHybrid) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Satellite Imagery Opacity: ${(builderSatelliteOpacity * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDarkPrimary
                )
                Slider(
                    value = builderSatelliteOpacity,
                    onValueChange = { builderSatelliteOpacity = it },
                    valueRange = 0.2f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = SkyBluePrimary,
                        activeTrackColor = SkyBluePrimary,
                        inactiveTrackColor = SurfaceBorderLight
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { resetBuilder() },
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(if (editingCompositionId != null) "Cancel" else "Reset", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        val finalName = if (builderName.isNotBlank()) builderName.trim() else "Custom ${System.currentTimeMillis() % 1000}"
                        val finalId = editingCompositionId ?: "comp_${System.currentTimeMillis()}"
                        val comp = MapComposition(
                            id = finalId,
                            name = finalName,
                            description = builderDescription.trim(),
                            baseStyleId = builderBaseStyleId,
                            isPinnedToQuickChoice = true,
                            isStarter = false,
                            showRoads = builderShowRoads,
                            showLabels = builderShowLabels,
                            showPois = builderShowPois,
                            showBoundaries = builderShowBoundaries,
                            showBuildings = builderShowBuildings,
                            satelliteOpacity = builderSatelliteOpacity
                        )
                        onSaveComposition(comp)
                        resetBuilder()
                    },
                    modifier = Modifier.height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBluePrimary)
                ) {
                    Icon(
                        imageVector = if (editingCompositionId != null) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (editingCompositionId != null) "Update Setup" else "Save Setup", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // SECTION 3: SUPPORTED SOURCES & STYLES DIRECTORY
        // ==========================================
        SectionHeader(
            title = "Supported Sources & Licensing Directory",
            subtitle = "Genuine providers, attribution, and data retention policies"
        )

        Spacer(modifier = Modifier.height(8.dp))

        MapSourceRepository.availableStyles.filter { !it.isTemporaryDevelopmentStyle }.forEach { style ->
            SettingsSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(style.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SkyBlueLight)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (style.isCompositeHybrid) "HYBRID SATELLITE" else "VECTOR MAP",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBlueDim
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                SettingsInfoRow(label = "Attribution", value = style.attribution)
                SettingsInfoRow(
                    label = "Licensing & Retention",
                    value = if (style.offlineRetentionPermitted) "Persistent local tile retention permitted" else "Transient browsing cache only"
                )
                SettingsInfoRow(label = "License Notes", value = style.licenseNotes)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Planned terrain contour notice
        SettingsSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Terrain & Contour Data", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceOffWhite)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Planned", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextDarkMuted)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Genuine topographic contours and hillshades require an offline DEM (Digital Elevation Model) raster tileset. Not yet operational in Milestone 1.",
                fontSize = 11.sp,
                color = TextDarkSecondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // SECTION 4: OFFLINE STORAGE REPORTING & MANAGEMENT
        // ==========================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(
                title = "Offline Storage Accounting",
                subtitle = "Exact on-device byte footprint and cache management"
            )
            IconButton(
                onClick = onRefreshStorage,
                modifier = Modifier.size(32.dp),
                enabled = !uiState.isCalculatingStorage
            ) {
                if (uiState.isCalculatingStorage) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SkyBluePrimary)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Recalculate Storage", tint = SkyBluePrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val report = uiState.storageReport
        if (report != null) {
            // Total footprint banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SkyBlueLight)
                    .border(1.dp, SkyBlueBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = SkyBlueDim, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Total Map Storage: ${report.formattedTotalSize}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkyBlueDim
                    )
                    Text(
                        text = "Tiles: ${report.perSource.sumOf { it.tileCount }} • SQLite DB: ${report.featureStorage.formattedDatabaseSize}",
                        fontSize = 11.sp,
                        color = TextDarkSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Per-Source Tile Cache Breakdown
            Text("Retained Tiles by Provider", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            Spacer(modifier = Modifier.height(6.dp))

            report.perSource.forEach { src ->
                SettingsSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(src.providerName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                            Text(
                                text = "${src.tileCount} tiles retained • ${src.formattedSize}",
                                fontSize = 11.sp,
                                color = if (src.tileCount > 0) SkyBlueDim else TextDarkMuted
                            )
                        }
                        if (src.tileCount > 0) {
                            OutlinedButton(
                                onClick = { pendingClearProviderId = src.providerId },
                                modifier = Modifier.height(28.dp),
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text("Clear", fontSize = 10.sp, color = IndicatorAlert)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Feature Store Accounting
            Text("OpenStreetMap Feature Database (Room SQLite)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            Spacer(modifier = Modifier.height(6.dp))

            SettingsSectionCard {
                SettingsInfoRow(label = "Database File (map_app_master.db)", value = report.featureStorage.formattedDatabaseSize)
                SettingsInfoRow(label = "Retained OSM POIs", value = "${report.featureStorage.poiCount} records")
                SettingsInfoRow(label = "Retained OSM Buildings", value = "${report.featureStorage.buildingCount} records")
                SettingsInfoRow(label = "Retained OSM Roads", value = "${report.featureStorage.roadCount} records")
                SettingsInfoRow(label = "Retained OSM Regions", value = "${report.featureStorage.regionCount} records")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Feature records in SQLite are shared across all map styles and preserved when tile caches are cleared.",
                    fontSize = 10.sp,
                    color = TextDarkMuted
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Routing Graph Index
            Text("Offline Routing Graph Coverage", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
            Spacer(modifier = Modifier.height(6.dp))

            SettingsSectionCard {
                SettingsInfoRow(label = "Routing Tiles", value = "${report.routingStorage.downloadedTiles} active tiles")
                SettingsInfoRow(label = "Routing Disk Size", value = report.routingStorage.formattedSize)
                SettingsInfoRow(label = "Coverage Status", value = report.routingStorage.status)
            }
        } else {
            SettingsSectionCard {
                Text("Storage report calculation in progress...", fontSize = 12.sp, color = TextDarkSecondary)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // Confirmation dialog: Delete Composition
    pendingDeleteId?.let { compId ->
        val comp = uiState.savedCompositions.find { it.id == compId }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete Composition?") },
            text = { Text("Are you sure you want to delete '${comp?.name ?: compId}'? This will remove it from your saved setups.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteComposition(compId)
                        pendingDeleteId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert)
                ) {
                    Text("Delete", color = SurfaceWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = TextDarkSecondary)
                }
            }
        )
    }

    // Confirmation dialog: Clear Provider Tiles
    pendingClearProviderId?.let { providerId ->
        val providerReport = uiState.storageReport?.perSource?.find { it.providerId == providerId }
        AlertDialog(
            onDismissRequest = { pendingClearProviderId = null },
            title = { Text("Clear Provider Cache?") },
            text = {
                Text(
                    "Purge all ${providerReport?.tileCount ?: 0} cached tiles (${providerReport?.formattedSize ?: "0 B"}) for ${providerReport?.providerName ?: providerId}? " +
                    "Shared OSM features, routes and other map styles will not be affected."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearProviderCache(providerId)
                        pendingClearProviderId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert)
                ) {
                    Text("Clear Cache", color = SurfaceWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearProviderId = null }) {
                    Text("Cancel", color = TextDarkSecondary)
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
        Text(text = subtitle, fontSize = 11.sp, color = TextDarkSecondary)
    }
}

@Composable
private fun LayerCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = SkyBluePrimary,
                checkmarkColor = SurfaceWhite
            ),
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, color = TextDarkPrimary)
    }
}
