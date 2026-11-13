package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.AreaCoordinateFormatter
import com.example.ui.screens.MapContextMenuUiState
import com.example.ui.screens.PlannedActionNoticeState
import com.example.ui.screens.SavedAreaEditorState
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
import kotlin.math.roundToInt

private val CtxIndigo = Color(0xFF4F46E5)
private val CtxEmerald = Color(0xFF059669)
private val CtxAmber = Color(0xFFD97706)

/**
 * Anchored Long-Press Map Context Menu.
 * Displays coordinate, context-sensitive existing nearby records (Saved Area / Placemark / Route),
 * functional map actions (Placemark, Save/Inspect Area, Route From/To, Wiki Search),
 * and honestly badged Planned actions (Memory Palace Room, Note, To-do, Measure).
 */
@Composable
fun MapContextMenu(
    state: MapContextMenuUiState,
    onDismiss: () -> Unit,
    onOpenExistingSavedArea: () -> Unit,
    onOpenExistingPlacemark: () -> Unit,
    onOpenExistingRoute: () -> Unit,
    onAddPlacemarkHere: () -> Unit,
    onOpenAreaDetails: () -> Unit,
    onWikiSearchArea: () -> Unit,
    onRouteFromHere: () -> Unit,
    onRouteToHere: () -> Unit,
    onPlannedAction: (title: String, group: String, summary: String, plannedBehavior: String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        val menuWidthDp = minOf(294.dp, maxWidth - 28.dp)
        val menuWidthPx = with(density) { menuWidthDp.toPx() }
        val estimatedHeightPx = with(density) { 360.dp.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }
        val topMarginPx = with(density) { 96.dp.toPx() }
        val bottomMarginPx = with(density) { 74.dp.toPx() }
        val pinGapPx = with(density) { 16.dp.toPx() }

        val targetX = state.screenX ?: (maxWidthPx / 2f)
        val targetY = state.screenY ?: (maxHeightPx * 0.52f)

        val clampedX = (targetX - menuWidthPx / 2f)
            .coerceIn(marginPx, (maxWidthPx - menuWidthPx - marginPx).coerceAtLeast(marginPx))

        val placeAbove = targetY - estimatedHeightPx - pinGapPx >= topMarginPx
        val clampedY = (if (placeAbove) targetY - estimatedHeightPx - pinGapPx else targetY + pinGapPx)
            .coerceIn(topMarginPx, (maxHeightPx - estimatedHeightPx - bottomMarginPx).coerceAtLeast(topMarginPx))

        val cardShape = RoundedCornerShape(16.dp)

        Column(
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .width(menuWidthDp)
                .heightIn(max = 395.dp)
                .shadow(6.dp, cardShape)
                .clip(cardShape)
                .background(SurfaceWhite)
                .border(1.dp, SurfaceBorderLight, cardShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("map_context_menu")
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = CtxIndigo,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.matches.savedArea?.name
                            ?: state.matches.placemark?.name
                            ?: state.matches.nearbyOsmPoi?.name
                            ?: "Map Actions",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = AreaCoordinateFormatter.formatDecimalDegrees(state.latitude, state.longitude),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextDarkSecondary
                    )
                }
                if (state.isResolvingMatches) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = SkyBluePrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("context_menu_dismiss")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close map menu",
                        tint = TextDarkSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Context-sensitive existing saved entities at/near this coordinate
                val matches = state.matches
                if (matches.hasAnySavedRecord) {
                    Text(
                        text = "Existing Nearby Records",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkyBlueDim,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    matches.savedArea?.let { area ->
                        ContextActionRow(
                            icon = Icons.Default.Bookmark,
                            iconTint = CtxIndigo,
                            title = "Open Saved Area: ${area.name}",
                            subtitle = "View stored offline area snapshot",
                            badge = "Saved",
                            onClick = onOpenExistingSavedArea,
                            testTag = "context_open_existing_area"
                        )
                    }
                    matches.placemark?.let { place ->
                        ContextActionRow(
                            icon = Icons.Default.PinDrop,
                            iconTint = CtxAmber,
                            title = "Open Placemark: ${place.name}",
                            subtitle = "Focus & edit existing bookmark",
                            badge = "Saved",
                            onClick = onOpenExistingPlacemark,
                            testTag = "context_open_existing_placemark"
                        )
                    }
                    matches.route?.let { routeModel ->
                        ContextActionRow(
                            icon = Icons.Default.Route,
                            iconTint = CtxEmerald,
                            title = "Open Route: ${routeModel.route.name}",
                            subtitle = "View & edit matching route",
                            badge = "Saved",
                            onClick = onOpenExistingRoute,
                            testTag = "context_open_existing_route"
                        )
                    }
                    HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 3.dp))
                }

                // Functional actions
                ContextActionRow(
                    icon = Icons.Default.AddLocationAlt,
                    iconTint = CtxAmber,
                    title = "Add Placemark here",
                    subtitle = if (matches.placemark != null) "Create a separate bookmark nearby" else "Pin permanent personal bookmark",
                    onClick = onAddPlacemarkHere,
                    testTag = "context_add_placemark"
                )
                ContextActionRow(
                    icon = Icons.Default.LocationOn,
                    iconTint = CtxIndigo,
                    title = if (matches.savedArea != null) "Inspect / Update Saved Area" else "Area Details & Save Area",
                    subtitle = "Inspect local OSM + online details & save offline",
                    onClick = onOpenAreaDetails,
                    testTag = "context_save_area"
                )
                ContextActionRow(
                    icon = Icons.Default.Public,
                    iconTint = SkyBluePrimary,
                    title = "Wiki Search for this area",
                    subtitle = "Look up nearest Wikipedia & Wikidata article",
                    onClick = onWikiSearchArea,
                    testTag = "context_wiki_search"
                )
                ContextActionRow(
                    icon = Icons.Default.Flag,
                    iconTint = CtxEmerald,
                    title = "Route from this point",
                    subtitle = "Start a new route draft here",
                    onClick = onRouteFromHere,
                    testTag = "context_route_from"
                )
                ContextActionRow(
                    icon = Icons.Default.AltRoute,
                    iconTint = CtxEmerald,
                    title = "Route to this point",
                    subtitle = "Set destination from current map center",
                    onClick = onRouteToHere,
                    testTag = "context_route_to"
                )

                HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 3.dp))

                // Planned subsystem actions (honestly badged, zero fake persistence)
                ContextActionRow(
                    icon = Icons.Default.Memory,
                    iconTint = TextDarkSecondary,
                    title = "Create Spatial Map / Memory Palace",
                    subtitle = "Bind a Memory Palace room to this location",
                    badge = "Planned",
                    onClick = {
                        onPlannedAction(
                            "Create Spatial Map / Memory Palace Room",
                            "Memory Palace",
                            "Spatial Memory Palace room creation at these coordinates will connect in the Memory Palace milestone.",
                            "When built, this coordinate will anchor a persistent Memory Palace room and linked spatial loci."
                        )
                    },
                    testTag = "context_planned_memory_palace"
                )
                ContextActionRow(
                    icon = Icons.Default.Description,
                    iconTint = TextDarkSecondary,
                    title = "Create Note here",
                    subtitle = "Attach a structured markdown note to coordinates",
                    badge = "Planned",
                    onClick = {
                        onPlannedAction(
                            "Create Note Here",
                            "Notes",
                            "Coordinate-bound Notes are planned for an upcoming milestone.",
                            "When built, notes created here will link directly to this geographic location without overwriting Placemarks."
                        )
                    },
                    testTag = "context_planned_note"
                )
                ContextActionRow(
                    icon = Icons.Default.Checklist,
                    iconTint = TextDarkSecondary,
                    title = "Create To-do here",
                    subtitle = "Add a location-tagged task item",
                    badge = "Planned",
                    onClick = {
                        onPlannedAction(
                            "Create To-do Here",
                            "Tasks",
                            "Location-linked To-do tasks are planned for an upcoming milestone.",
                            "When built, tasks created here will attach checklists and reminders to this map coordinate."
                        )
                    },
                    testTag = "context_planned_todo"
                )
                ContextActionRow(
                    icon = Icons.Default.Architecture,
                    iconTint = TextDarkSecondary,
                    title = "Measure from this point",
                    subtitle = "Geodesic distance & bearing ruler tool",
                    badge = "Planned",
                    onClick = {
                        onPlannedAction(
                            "Measure From This Point",
                            "Tools -> Measure",
                            "Interactive geodesic distance & bearing measurement is planned for an upcoming Tools milestone.",
                            "For distance along points right now, you can also use Route From Here to inspect polyline distance."
                        )
                    },
                    testTag = "context_planned_measure"
                )
            }
        }
    }
}

@Composable
private fun ContextActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceOffWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDarkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!badge.isNullOrBlank()) {
                    val isPlanned = badge.equals("Planned", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isPlanned) SkyBlueLight else Color(0xFFD1FAE5))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPlanned) SkyBlueDim else Color(0xFF047857)
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = TextDarkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PlannedActionNoticeDialog(
    state: PlannedActionNoticeState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = SkyBluePrimary
            )
        },
        title = {
            Text(
                text = state.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Target: ${AreaCoordinateFormatter.formatDecimalDegrees(state.latitude, state.longitude)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDarkSecondary
                )
                Text(
                    text = state.summary,
                    fontSize = 12.5.sp,
                    color = TextDarkPrimary
                )
                Text(
                    text = state.plannedBehavior,
                    fontSize = 11.5.sp,
                    color = TextDarkSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SkyBluePrimary)
            ) {
                Text("Got it", color = Color.White)
            }
        }
    )
}

@Composable
fun SavedAreaEditorDialog(
    editorState: SavedAreaEditorState,
    onDismiss: () -> Unit,
    onSave: (name: String, category: String, addressLabel: String, userNotes: String, tagsText: String) -> Unit
) {
    var name by remember(editorState.areaId) { mutableStateOf(editorState.name) }
    var category by remember(editorState.areaId) { mutableStateOf(editorState.category) }
    var addressLabel by remember(editorState.areaId) { mutableStateOf(editorState.addressLabel) }
    var userNotes by remember(editorState.areaId) { mutableStateOf(editorState.userNotes) }
    var tagsText by remember(editorState.areaId) { mutableStateOf(editorState.tagsText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Saved Area", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Area Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = addressLabel,
                    onValueChange = { addressLabel = it },
                    label = { Text("Address / Locality Summary") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = userNotes,
                    onValueChange = { userNotes = it },
                    label = { Text("Offline Field Notes") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!editorState.errorMessage.isNullOrBlank()) {
                    Text(
                        text = editorState.errorMessage,
                        fontSize = 11.sp,
                        color = IndicatorAlert,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, category, addressLabel, userNotes, tagsText) },
                colors = ButtonDefaults.buttonColors(containerColor = CtxIndigo)
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDarkSecondary)
            }
        }
    )
}
