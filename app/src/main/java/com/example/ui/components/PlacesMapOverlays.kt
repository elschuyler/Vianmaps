package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.UserPlaceEntity
import com.example.data.places.RouteGeometryCodec
import com.example.data.places.SavedRouteModel
import com.example.ui.screens.RouteDraftUiState
import com.example.ui.theme.IndicatorAlert
import com.example.ui.theme.SkyBlueDim
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.TextDarkMuted
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary

private val BannerAmber = Color(0xFFD97706)
private val BannerAmberText = Color(0xFF92400E)
private val BannerAmberBg = Color(0xFFFEF3C7)
private val BannerAmberBorder = Color(0xFFFDE68A)

private val BannerEmerald = Color(0xFF059669)
private val BannerEmeraldText = Color(0xFF065F46)
private val BannerEmeraldBg = Color(0xFFD1FAE5)
private val BannerEmeraldBorder = Color(0xFFA7F3D0)

@Composable
fun PickLocationHudBanner(
    onUseMapCenter: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .widthIn(min = 240.dp, max = 310.dp)
            .shadow(4.dp, cardShape)
            .clip(cardShape)
            .background(SurfaceWhite)
            .border(1.dp, BannerAmberBorder, cardShape)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
            .testTag("pick_location_hud")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BannerAmberBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = BannerAmber,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pick Placemark Location",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
                Text(
                    text = "Tap any point on the live map to drop a placemark.",
                    fontSize = 11.sp,
                    color = TextDarkSecondary
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel pick location", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp, end = 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onUseMapCenter,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = BannerAmber, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Use Map Center", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BannerAmberText)
            }
            TextButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Cancel", fontSize = 11.sp, color = TextDarkSecondary)
            }
        }
    }
}

@Composable
fun RouteCreationHudBar(
    routeDraft: RouteDraftUiState,
    onAddMapCenterPoint: () -> Unit,
    onUndoPoint: () -> Unit,
    onMarkLastPointWaypoint: (String) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(14.dp)
    var showWaypointDialog by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .widthIn(min = 250.dp, max = 330.dp)
            .shadow(4.dp, cardShape)
            .clip(cardShape)
            .background(SurfaceWhite)
            .border(1.dp, BannerEmeraldBorder, cardShape)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
            .testTag("route_creation_hud")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BannerEmeraldBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Route, contentDescription = null, tint = BannerEmerald, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (routeDraft.editingRouteId != null) "Editing Route Points" else "Creating Route",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
                Text(
                    text = "${routeDraft.points.size} pts • ${RouteGeometryCodec.formatDistanceMeters(routeDraft.totalDistanceMeters)} • ${routeDraft.waypointNamesByPointIndex.size} wp",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = BannerEmeraldText
                )
            }
            IconButton(
                onClick = {
                    if (routeDraft.points.isNotEmpty()) {
                        showDiscardConfirm = true
                    } else {
                        onCancel()
                    }
                },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel route draft", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Tap the map to add ordered route points (polyline only).",
            fontSize = 10.sp,
            color = TextDarkMuted
        )

        if (!routeDraft.errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = routeDraft.errorMessage,
                fontSize = 11.sp,
                color = IndicatorAlert,
                fontWeight = FontWeight.SemiBold
            )
        }

        HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp, end = 6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onAddMapCenterPoint,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("route_hud_add_center")
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = null, tint = BannerEmerald, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("+ Center", fontSize = 11.sp, color = BannerEmeraldText)
            }

            TextButton(
                onClick = { showWaypointDialog = true },
                enabled = routeDraft.points.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("route_hud_waypoint")
            ) {
                Icon(Icons.Default.Flag, contentDescription = null, tint = if (routeDraft.points.isNotEmpty()) BannerAmber else TextDarkMuted, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Waypoint", fontSize = 11.sp, color = if (routeDraft.points.isNotEmpty()) BannerAmberText else TextDarkMuted)
            }

            TextButton(
                onClick = onUndoPoint,
                enabled = routeDraft.points.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("route_hud_undo")
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = if (routeDraft.points.isNotEmpty()) TextDarkPrimary else TextDarkMuted, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Undo", fontSize = 11.sp, color = if (routeDraft.points.isNotEmpty()) TextDarkPrimary else TextDarkMuted)
            }

            TextButton(
                onClick = onFinish,
                enabled = routeDraft.points.size >= 2,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("route_hud_finish")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = if (routeDraft.points.size >= 2) BannerEmerald else TextDarkMuted, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Finish",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (routeDraft.points.size >= 2) BannerEmeraldText else TextDarkMuted
                )
            }
        }
    }

    if (showWaypointDialog) {
        var wpName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showWaypointDialog = false },
            title = { Text("Name Last Point as Waypoint") },
            text = {
                OutlinedTextField(
                    value = wpName,
                    onValueChange = { wpName = it },
                    label = { Text("Waypoint Name (e.g., Ridge Saddle)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onMarkLastPointWaypoint(wpName)
                        showWaypointDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BannerEmerald, contentColor = Color.White)
                ) {
                    Text("Mark Waypoint")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWaypointDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard Route Draft?") },
            text = { Text("You have placed ${routeDraft.points.size} point(s) on the map. Discard this draft without saving?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirm = false
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert, contentColor = Color.White)
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }
}

@Composable
fun SelectedPlacemarkBanner(
    place: UserPlaceEntity,
    onCenter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(14.dp)
    var showDeleteConfirm by remember(place.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .widthIn(min = 240.dp, max = 310.dp)
            .shadow(4.dp, cardShape)
            .clip(cardShape)
            .background(SurfaceWhite)
            .border(1.dp, BannerAmberBorder, cardShape)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
            .testTag("selected_placemark_banner")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BannerAmberBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PinDrop, contentDescription = null, tint = BannerAmber, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
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
                    Surface(color = BannerAmberBg, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = place.category,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BannerAmberText,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(text = "Saved Placemark", fontSize = 9.sp, color = TextDarkMuted)
                }
                if (place.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = place.description,
                        fontSize = 10.sp,
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
                    color = TextDarkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Default.Close, contentDescription = "Close placemark banner", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp, end = 6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCenter,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = BannerAmber, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Center", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BannerAmberText)
            }
            TextButton(
                onClick = onEdit,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SkyBlueDim)
            }
            TextButton(
                onClick = { showDeleteConfirm = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = IndicatorAlert, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = IndicatorAlert)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Placemark?") },
            text = { Text("Delete '${place.name}' from local Room storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert, contentColor = Color.White)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SelectedRouteBanner(
    routeModel: SavedRouteModel,
    onCenter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(14.dp)
    val distanceLabel = remember(routeModel.route.totalDistanceMeters) {
        RouteGeometryCodec.formatDistanceMeters(routeModel.route.totalDistanceMeters)
    }
    var showDeleteConfirm by remember(routeModel.route.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .widthIn(min = 240.dp, max = 310.dp)
            .shadow(4.dp, cardShape)
            .clip(cardShape)
            .background(SurfaceWhite)
            .border(1.dp, BannerEmeraldBorder, cardShape)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
            .testTag("selected_route_banner")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BannerEmeraldBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Route, contentDescription = null, tint = BannerEmerald, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = routeModel.route.name,
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
                    Surface(color = BannerEmeraldBg, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = distanceLabel,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BannerEmeraldText,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = "${routeModel.points.size} pts • ${routeModel.waypoints.size} wp",
                        fontSize = 9.sp,
                        color = TextDarkMuted
                    )
                }
                if (routeModel.waypoints.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = routeModel.waypoints.joinToString(" -> ") { it.name },
                        fontSize = 10.sp,
                        color = TextDarkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Default.Close, contentDescription = "Close route banner", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp, end = 6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCenter,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = BannerEmerald, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Center", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BannerEmeraldText)
            }
            TextButton(
                onClick = onEdit,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SkyBlueDim)
            }
            TextButton(
                onClick = { showDeleteConfirm = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = IndicatorAlert, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = IndicatorAlert)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Saved Route?") },
            text = { Text("Delete '${routeModel.route.name}' and its waypoints from local Room storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert, contentColor = Color.White)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
