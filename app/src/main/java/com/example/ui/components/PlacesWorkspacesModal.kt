package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.db.UserPlaceEntity
import com.example.data.places.RouteGeometryCodec
import com.example.data.places.RouteWaypointDraft
import com.example.data.places.SavedRouteModel
import com.example.ui.screens.PlacemarkEditorState
import com.example.ui.screens.RouteDraftUiState
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

private val AmberAccent = Color(0xFFD97706)
private val AmberText = Color(0xFF92400E)
private val AmberBg = Color(0xFFFEF3C7)
private val AmberBorder = Color(0xFFFDE68A)

private val EmeraldAccent = Color(0xFF059669)
private val EmeraldText = Color(0xFF065F46)
private val EmeraldBg = Color(0xFFD1FAE5)
private val EmeraldBorder = Color(0xFFA7F3D0)

@Composable
fun PlacemarksWorkspaceContent(
    placemarks: List<UserPlaceEntity>,
    onCreateAtCenter: () -> Unit,
    onPickOnMap: () -> Unit,
    onSelectPlacemark: (UserPlaceEntity) -> Unit,
    onEditPlacemark: (UserPlaceEntity) -> Unit,
    onDeletePlacemark: (String) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<UserPlaceEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("placemarks_workspace")
    ) {
        // Primary creation actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCreateAtCenter,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SkyBluePrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("create_placemark_center_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AddLocationAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Map Center", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }

            OutlinedButton(
                onClick = onPickOnMap,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("pick_placemark_on_map_button")
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = SkyBluePrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pick on Map", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SkyBlueDim, maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Honest local storage notice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceOffWhite)
                .border(1.dp, SurfaceBorderLight, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = SkyBluePrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Placemarks are stored locally in Room (map_app_master.db) and remain untouched when map tile caches are cleared. You can also long-press any point on the map to drop a placemark.",
                fontSize = 11.sp,
                color = TextDarkSecondary,
                lineHeight = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Saved Placemarks (${placemarks.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (placemarks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceOffWhite)
                    .border(1.dp, SurfaceBorderLight, RoundedCornerShape(12.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PinDrop,
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No Saved Placemarks Yet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Use 'Save Map Center', 'Pick on Map', or long-press the map canvas to create custom landmarks without GPS or network access.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(placemarks, key = { it.id }) { place ->
                    PlacemarkCard(
                        place = place,
                        onOpen = { onSelectPlacemark(place) },
                        onEdit = { onEditPlacemark(place) },
                        onDelete = { pendingDelete = place }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Placemark?") },
            text = {
                Text("Are you sure you want to permanently delete '${target.name}'? Any route waypoints linked to it will keep their coordinates and drop the place link.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePlacemark(target.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_delete_placemark_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PlacemarkCard(
    place: UserPlaceEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, shape)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag("placemark_item_${place.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AmberBg)
                .border(1.dp, AmberBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PinDrop,
                contentDescription = null,
                tint = AmberAccent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(color = AmberBg, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        text = place.category,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AmberText,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                if (place.tagsCsv.isNotBlank()) {
                    Text(
                        text = place.tagsCsv,
                        fontSize = 10.sp,
                        color = TextDarkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!place.addressLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = place.addressLabel,
                    fontSize = 10.sp,
                    color = TextDarkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (place.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = place.description,
                    fontSize = 11.sp,
                    color = TextDarkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatDecimal(place.latitude, place.longitude),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextDarkMuted
            )
        }
        IconButton(
            onClick = onEdit,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("edit_placemark_${place.id}")
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit ${place.name}",
                tint = SkyBluePrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("delete_placemark_${place.id}")
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete ${place.name}",
                tint = IndicatorAlert,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun RoutesWorkspaceContent(
    routes: List<SavedRouteModel>,
    onStartCreateRoute: () -> Unit,
    onSelectRoute: (SavedRouteModel) -> Unit,
    onEditRouteMetadata: (SavedRouteModel) -> Unit,
    onEditRouteGeometry: (SavedRouteModel) -> Unit,
    onDeleteRoute: (String) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SavedRouteModel?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("routes_workspace")
    ) {
        Button(
            onClick = onStartCreateRoute,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldAccent,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("start_create_route_button")
        ) {
            Icon(
                imageVector = Icons.Default.AltRoute,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Route on Map", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceOffWhite)
                .border(1.dp, SurfaceBorderLight, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = EmeraldAccent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Saved routes store ordered polyline geometry and waypoints placed on the map. Distances are spherical polyline lengths; automatic turn-by-turn routing calculation is planned for a future milestone.",
                fontSize = 11.sp,
                color = TextDarkSecondary,
                lineHeight = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Saved Routes (${routes.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (routes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceOffWhite)
                    .border(1.dp, SurfaceBorderLight, RoundedCornerShape(12.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No Saved Routes Yet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Create Route on Map' to place ordered points and waypoints directly on the live map and save the trail to Room storage.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(routes, key = { it.route.id }) { model ->
                    SavedRouteCard(
                        model = model,
                        onOpen = { onSelectRoute(model) },
                        onEditMetadata = { onEditRouteMetadata(model) },
                        onEditGeometry = { onEditRouteGeometry(model) },
                        onDelete = { pendingDelete = model }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Saved Route?") },
            text = {
                Text("Are you sure you want to delete '${target.route.name}' and its ${target.waypoints.size} waypoints? Any saved placemarks remain untouched.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteRoute(target.route.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_delete_route_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SavedRouteCard(
    model: SavedRouteModel,
    onOpen: () -> Unit,
    onEditMetadata: () -> Unit,
    onEditGeometry: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val distanceLabel = remember(model.route.totalDistanceMeters) {
        RouteGeometryCodec.formatDistanceMeters(model.route.totalDistanceMeters)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, shape)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp)
            .testTag("route_item_${model.route.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EmeraldBg)
                    .border(1.dp, EmeraldBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                    tint = EmeraldAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.route.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Surface(color = EmeraldBg, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = distanceLabel,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldText,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = "${model.points.size} pts • ${model.waypoints.size} waypoints",
                        fontSize = 10.sp,
                        color = TextDarkSecondary
                    )
                }
            }
        }

        if (model.hasMalformedGeometry) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFEE2E2))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = IndicatorAlert,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Partial geometry: malformed coordinate entries skipped.",
                    fontSize = 10.sp,
                    color = IndicatorAlert
                )
            }
        }

        if (model.route.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = model.route.description,
                fontSize = 11.sp,
                color = TextDarkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (model.waypoints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Waypoints: " + model.waypoints.joinToString(" -> ") { it.name },
                fontSize = 10.sp,
                color = TextDarkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onEditGeometry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.EditLocationAlt, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit Points", fontSize = 11.sp, color = EmeraldText)
            }
            TextButton(
                onClick = onEditMetadata,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Rename / Info", fontSize = 11.sp, color = SkyBlueDim)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_route_${model.route.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete ${model.route.name}",
                    tint = IndicatorAlert,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun PlacemarkEditorDialog(
    editorState: PlacemarkEditorState,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        description: String,
        addressLabel: String,
        category: String,
        tagsText: String,
        latitude: Double,
        longitude: Double,
        allowDuplicateLocation: Boolean
    ) -> Unit,
    onOpenExistingDuplicate: (UserPlaceEntity) -> Unit
) {
    var name by remember(editorState.editingPlaceId, editorState.latitude, editorState.longitude) {
        mutableStateOf(editorState.name)
    }
    var description by remember(editorState.editingPlaceId, editorState.latitude, editorState.longitude) {
        mutableStateOf(editorState.description)
    }
    var addressLabel by remember(editorState.editingPlaceId, editorState.latitude, editorState.longitude) {
        mutableStateOf(editorState.addressLabel)
    }
    var category by remember(editorState.editingPlaceId, editorState.latitude, editorState.longitude) {
        mutableStateOf(editorState.category)
    }
    var tagsText by remember(editorState.editingPlaceId, editorState.latitude, editorState.longitude) {
        mutableStateOf(editorState.tagsText)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceBorderLight, RoundedCornerShape(16.dp))
                .testTag("placemark_editor_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (editorState.editingPlaceId != null) "Edit Placemark" else "New Placemark",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.Close, contentDescription = "Close editor", tint = TextDarkSecondary)
                    }
                }

                Text(
                    text = formatDecimal(editorState.latitude, editorState.longitude),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SkyBlueDim
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (Required)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("placemark_name_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (e.g., landmark, camp, water)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Notes / Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = addressLabel,
                    onValueChange = { addressLabel = it },
                    label = { Text("Location / Address Label (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tags (Comma-separated, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!editorState.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = editorState.errorMessage,
                        fontSize = 12.sp,
                        color = IndicatorAlert,
                        fontWeight = FontWeight.Medium
                    )
                }

                val duplicate = editorState.nearbyDuplicatePlace
                if (duplicate != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AmberBg)
                            .border(1.dp, AmberBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "Nearby Placemark Found (~10m)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AmberText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "'${duplicate.name}' already exists near these coordinates. Open it or keep a separate copy.",
                            fontSize = 11.sp,
                            color = TextDarkPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onOpenExistingDuplicate(duplicate) },
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Text("Open Existing", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    onSave(
                                        name,
                                        description,
                                        addressLabel,
                                        category,
                                        tagsText,
                                        editorState.latitude,
                                        editorState.longitude,
                                        true
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberAccent, contentColor = Color.White),
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Text("Save Separate", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                name,
                                description,
                                addressLabel,
                                category,
                                tagsText,
                                editorState.latitude,
                                editorState.longitude,
                                false
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SkyBluePrimary, contentColor = Color.White),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("save_placemark_button")
                    ) {
                        Text(if (editorState.editingPlaceId != null) "Save Changes" else "Save Placemark")
                    }
                }
            }
        }
    }
}

@Composable
fun SaveRouteDialog(
    routeDraft: RouteDraftUiState,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, waypointNamesByPointIndex: Map<Int, String>) -> Unit
) {
    var name by remember(routeDraft.editingRouteId, routeDraft.showSaveDialog) {
        mutableStateOf(routeDraft.initialName)
    }
    var description by remember(routeDraft.editingRouteId, routeDraft.showSaveDialog) {
        mutableStateOf(routeDraft.initialDescription)
    }
    val waypointNames = remember(routeDraft.editingRouteId, routeDraft.showSaveDialog) {
        mutableStateMapOf<Int, String>().apply {
            putAll(routeDraft.waypointNamesByPointIndex)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceBorderLight, RoundedCornerShape(16.dp))
                .testTag("save_route_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (routeDraft.editingRouteId != null) "Update Saved Route" else "Save New Route",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary)
                    }
                }

                Text(
                    text = "${routeDraft.points.size} points • ${RouteGeometryCodec.formatDistanceMeters(routeDraft.totalDistanceMeters)} (polyline geometry)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EmeraldText
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Route Name (Required)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_route_name_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / Trail Notes (Optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Waypoints (${waypointNames.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    if (routeDraft.points.isNotEmpty() && !waypointNames.containsKey(0)) {
                        TextButton(onClick = { waypointNames[0] = "Start" }) {
                            Text("+ Mark Start", fontSize = 11.sp, color = EmeraldText)
                        }
                    }
                    if (routeDraft.points.size >= 2 && !waypointNames.containsKey(routeDraft.points.lastIndex)) {
                        TextButton(onClick = { waypointNames[routeDraft.points.lastIndex] = "End" }) {
                            Text("+ Mark End", fontSize = 11.sp, color = EmeraldText)
                        }
                    }
                }

                if (waypointNames.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        waypointNames.entries.sortedBy { it.key }.forEach { (idx, wpName) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Flag, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = wpName,
                                    onValueChange = { waypointNames[idx] = it },
                                    label = { Text("Point #${idx + 1}") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { waypointNames.remove(idx) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove waypoint", tint = TextDarkSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                if (!routeDraft.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = routeDraft.errorMessage,
                        fontSize = 12.sp,
                        color = IndicatorAlert,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Text("Back to Map")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name, description, waypointNames.toMap()) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent, contentColor = Color.White),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("confirm_save_route_button")
                    ) {
                        Text("Save Route")
                    }
                }
            }
        }
    }
}

@Composable
fun RouteMetadataEditorDialog(
    routeModel: SavedRouteModel,
    onDismiss: () -> Unit,
    onEditGeometryOnMap: () -> Unit,
    onSaveChanges: (name: String, description: String, updatedWaypoints: List<RouteWaypointDraft>) -> Unit
) {
    var name by remember(routeModel.route.id) { mutableStateOf(routeModel.route.name) }
    var description by remember(routeModel.route.id) { mutableStateOf(routeModel.route.description) }
    val waypointDrafts = remember(routeModel.route.id) {
        mutableStateListOf<RouteWaypointDraft>().apply {
            addAll(
                routeModel.waypoints.map { wp ->
                    RouteWaypointDraft(
                        id = wp.id,
                        placeId = wp.placeId,
                        name = wp.name,
                        latitude = wp.latitude,
                        longitude = wp.longitude,
                        notes = wp.notes
                    )
                }
            )
        }
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceBorderLight, RoundedCornerShape(16.dp))
                .testTag("route_metadata_editor_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Edit Route Details", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDarkSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; validationError = null },
                    label = { Text("Route Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onEditGeometryOnMap,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.EditLocationAlt, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit Route Points on Map (${routeModel.points.size} pts)", fontSize = 12.sp, color = EmeraldText)
                }

                if (waypointDrafts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Waypoints (${waypointDrafts.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    waypointDrafts.forEachIndexed { idx, wp ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = wp.name,
                                onValueChange = { newText ->
                                    waypointDrafts[idx] = wp.copy(name = newText)
                                },
                                label = { Text("Waypoint #${idx + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { waypointDrafts.removeAt(idx) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete waypoint", tint = IndicatorAlert, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (validationError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(validationError!!, fontSize = 12.sp, color = IndicatorAlert)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.trim().isEmpty()) {
                                validationError = "Route name cannot be empty."
                            } else {
                                onSaveChanges(name, description, waypointDrafts.toList())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent, contentColor = Color.White),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
