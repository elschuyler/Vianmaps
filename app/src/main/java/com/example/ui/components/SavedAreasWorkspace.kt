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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.SavedAreaEntity
import com.example.ui.screens.AreaCoordinateFormatter
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

private val AreaIndigo = Color(0xFF4F46E5)
private val AreaIndigoBg = Color(0xFFEEF2FF)

@Composable
fun SavedAreasWorkspaceContent(
    savedAreas: List<SavedAreaEntity>,
    isOnlineAllowed: Boolean,
    onOpenSavedAreaOnMap: (SavedAreaEntity) -> Unit,
    onEditSavedArea: (SavedAreaEntity) -> Unit,
    onRefreshSavedAreaOnline: (String) -> Unit,
    onDeleteSavedArea: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SavedAreaEntity?>(null) }

    val filteredAreas = remember(savedAreas, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            savedAreas
        } else {
            savedAreas.filter { area ->
                area.name.lowercase().contains(q) ||
                    area.locality.lowercase().contains(q) ||
                    area.addressLabel.lowercase().contains(q) ||
                    area.userNotes.lowercase().contains(q) ||
                    area.tagsCsv.lowercase().contains(q) ||
                    (area.wikiTitle?.lowercase()?.contains(q) == true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("saved_areas_workspace")
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter saved areas, notes, tags...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("saved_areas_search_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Honest snapshot notice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceOffWhite)
                .border(1.dp, SurfaceBorderLight, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = SkyBluePrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Saved Areas persist geographic details, Wikipedia summaries, and chosen nearby items locally in Room. They survive tile-cache clearing and do NOT download surrounding map tiles.",
                fontSize = 11.sp,
                color = TextDarkSecondary,
                lineHeight = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Saved Areas (${filteredAreas.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (filteredAreas.isEmpty()) {
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
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = AreaIndigo,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (savedAreas.isEmpty()) "No Saved Areas Yet" else "No Matching Saved Areas",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (savedAreas.isEmpty()) {
                            "Single-tap or long-press any location on the map to inspect area details and tap 'Save Area' to keep an offline snapshot."
                        } else {
                            "Try clearing the filter query."
                        },
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
                items(filteredAreas, key = { it.id }) { area ->
                    SavedAreaCard(
                        area = area,
                        isOnlineAllowed = isOnlineAllowed,
                        onOpenOnMap = { onOpenSavedAreaOnMap(area) },
                        onEdit = { onEditSavedArea(area) },
                        onRefreshOnline = { onRefreshSavedAreaOnline(area.id) },
                        onDelete = { pendingDelete = area }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Saved Area?") },
            text = { Text("Remove '${target.name}' permanently from your Saved Areas snapshots?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSavedArea(target.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert)
                ) {
                    Text("Delete", color = SurfaceWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = TextDarkSecondary)
                }
            }
        )
    }
}

@Composable
private fun SavedAreaCard(
    area: SavedAreaEntity,
    isOnlineAllowed: Boolean,
    onOpenOnMap: () -> Unit,
    onEdit: () -> Unit,
    onRefreshOnline: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenOnMap)
            .padding(11.dp)
            .testTag("saved_area_card_${area.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AreaIndigoBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = AreaIndigo,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = area.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = AreaCoordinateFormatter.formatDecimalDegrees(area.latitude, area.longitude),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDarkSecondary
                )
            }
            Surface(color = SkyBlueLight, shape = RoundedCornerShape(6.dp)) {
                Text(
                    text = "${area.retainedNearbyCount} nearby",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyBlueDim,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        val locSummary = listOf(area.addressLabel, area.locality, area.region, area.country)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" • ")
        if (locSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = locSummary,
                fontSize = 11.5.sp,
                color = TextDarkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!area.wikiTitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, contentDescription = null, tint = SkyBluePrimary, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Wiki: ${area.wikiTitle}",
                    fontSize = 11.sp,
                    color = SkyBlueDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (area.userNotes.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "Note: ${area.userNotes}",
                fontSize = 11.sp,
                color = TextDarkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "Data retrieved: ${AreaCoordinateFormatter.formatTimestampRelativeOrShort(area.retrievedAt)} • Record edited: ${AreaCoordinateFormatter.formatTimestampRelativeOrShort(area.updatedAt)}",
            fontSize = 9.5.sp,
            color = TextDarkMuted
        )
        Text(
            text = "Sources: ${area.sourceAttribution.ifBlank { "Local OSM Database" }}",
            fontSize = 9.5.sp,
            color = TextDarkMuted,
            modifier = Modifier.testTag("saved_area_attribution_${area.id}")
        )

        HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onOpenOnMap,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Open on Map", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = AreaIndigo)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onRefreshOnline,
                enabled = isOnlineAllowed,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh saved area online",
                    tint = if (isOnlineAllowed) SkyBluePrimary else TextDarkMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit saved area",
                    tint = TextDarkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete saved area",
                    tint = IndicatorAlert,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
