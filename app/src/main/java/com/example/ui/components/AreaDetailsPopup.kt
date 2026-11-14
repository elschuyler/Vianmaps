package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.area.AreaDataProvenance
import com.example.data.area.SavedAreaNearbyCodec
import com.example.data.db.SavedAreaEntity
import com.example.ui.screens.AreaCoordinateFormatter
import com.example.ui.screens.AreaDetailsUiState
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

private val IndigoAccent = Color(0xFF4F46E5)
private val IndigoBg = Color(0xFFEEF2FF)
private val IndigoBorder = Color(0xFFC7D2FE)
private val AmberWarnText = Color(0xFF92400E)
private val AmberWarnBg = Color(0xFFFEF3C7)
private val AmberWarnBorder = Color(0xFFFDE68A)
private val EmeraldOk = Color(0xFF047857)
private val EmeraldBg = Color(0xFFD1FAE5)

/**
 * Anchored, non-blocking Area Details popup.
 * Tracks `(screenX, screenY)` on the map canvas so the popup stays anchored to the
 * selected coordinate while leaving all surrounding map area free for pan, pinch zoom,
 * and double-tap zoom gestures.
 */
@Composable
fun AreaDetailsPopup(
    state: AreaDetailsUiState,
    savedAreaEntity: SavedAreaEntity?,
    onDismiss: () -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleNearbySelection: (String) -> Unit,
    onUpdateCustomName: (String) -> Unit,
    onUpdateNotesDraft: (String) -> Unit,
    onSaveArea: () -> Unit,
    onEditSavedArea: (SavedAreaEntity) -> Unit,
    onRefreshOnline: () -> Unit,
    onDeleteSavedArea: (String) -> Unit,
    onCopyFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var showSaveCustomizer by remember(state.latitude, state.longitude) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        val cardWidthDp = minOf(328.dp, maxWidth - 24.dp)
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        val estimatedHeightPx = with(density) { if (state.isExpanded) 380.dp.toPx() else 132.dp.toPx() }
        val horizontalMarginPx = with(density) { 12.dp.toPx() }
        val topMarginPx = with(density) { 96.dp.toPx() }
        val bottomMarginPx = with(density) { 78.dp.toPx() }
        val pinGapPx = with(density) { 18.dp.toPx() }

        val targetX = state.screenX ?: (maxWidthPx / 2f)
        val targetY = state.screenY ?: (maxHeightPx * 0.56f)

        val clampedX = (targetX - cardWidthPx / 2f)
            .coerceIn(horizontalMarginPx, (maxWidthPx - cardWidthPx - horizontalMarginPx).coerceAtLeast(horizontalMarginPx))

        val placeAbove = targetY - estimatedHeightPx - pinGapPx >= topMarginPx
        val rawY = if (placeAbove) {
            targetY - estimatedHeightPx - pinGapPx
        } else {
            targetY + pinGapPx
        }
        val clampedY = rawY.coerceIn(
            topMarginPx,
            (maxHeightPx - estimatedHeightPx - bottomMarginPx).coerceAtLeast(topMarginPx)
        )

        val cardShape = RoundedCornerShape(16.dp)
        val details = state.details
        val displayTitle = details.locationIdentity?.primaryName?.takeIf { it.isNotBlank() }
            ?: savedAreaEntity?.name?.takeIf { it.isNotBlank() }
            ?: "Selected Location"

        Column(
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .width(cardWidthDp)
                .heightIn(max = if (state.isExpanded) 410.dp else 160.dp)
                .shadow(6.dp, cardShape)
                .clip(cardShape)
                .background(SurfaceWhite)
                .border(1.dp, IndigoBorder, cardShape)
                // Consume pointer clicks inside popup only — never outside so MapView gestures pass through cleanly
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("area_details_popup")
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(IndigoBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = IndigoAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = AreaCoordinateFormatter.formatDecimalDegrees(state.latitude, state.longitude),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextDarkSecondary
                    )
                }
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("area_details_toggle_expand")
                ) {
                    Icon(
                        imageVector = if (state.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (state.isExpanded) "Collapse area details" else "Expand area details",
                        tint = TextDarkSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("area_details_dismiss")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close area details",
                        tint = TextDarkSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Status / Provenance strip
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (badgeBg, badgeFg) = when (details.provenance) {
                    AreaDataProvenance.SAVED_SNAPSHOT -> EmeraldBg to EmeraldOk
                    AreaDataProvenance.ONLINE_LIVE,
                    AreaDataProvenance.HYBRID_ONLINE_AND_LOCAL -> SkyBlueLight to SkyBlueDim
                    AreaDataProvenance.LOCAL_ONLY -> IndigoBg to IndigoAccent
                }
                Surface(color = badgeBg, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        text = details.provenance.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (details.isLoadingOnline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.8.dp,
                            color = SkyBluePrimary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Loading online...", fontSize = 10.sp, color = SkyBlueDim)
                    }
                } else if (details.isOffline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = AmberWarnText,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Offline — local data only", fontSize = 10.sp, color = AmberWarnText)
                    }
                }
            }

            if (state.isExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Snapshot outdated & non-tile notice with separate retrieved vs edited timestamps
                    if (details.isSavedSnapshot) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AmberWarnBg)
                                .border(1.dp, AmberWarnBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = AmberWarnText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Saved offline snapshot. Details may have changed since retrieval; surrounding map tiles are NOT saved automatically.",
                                    fontSize = 10.sp,
                                    color = AmberWarnText,
                                    lineHeight = 13.sp
                                )
                            }
                            val editedTs = savedAreaEntity?.updatedAt ?: details.savedAt
                            val timeLine = buildString {
                                append("Data retrieved: ")
                                append(AreaCoordinateFormatter.formatTimestampRelativeOrShort(details.retrievedAt))
                                if (editedTs != null && editedTs > 0L) {
                                    append("  •  Record edited: ")
                                    append(AreaCoordinateFormatter.formatTimestampRelativeOrShort(editedTs))
                                }
                            }
                            Text(
                                text = timeLine,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = AmberWarnText
                            )
                        }
                    }

                    // Partial, error, or disabled reverse-geocoder policy notice
                    val notice = details.errorMessage ?: details.partialWarning ?: details.reverseGeocoderNote
                    if (!notice.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceOffWhite)
                                .border(1.dp, SurfaceBorderLight, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = TextDarkSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(notice, fontSize = 10.5.sp, color = TextDarkSecondary, lineHeight = 14.sp)
                        }
                    }

                    // Section A: Location Identity
                    val identity = details.locationIdentity
                    if (identity != null) {
                        AreaSectionBlock(title = "Location Identity") {
                            if (identity.addressLabel.isNotBlank()) {
                                Text(identity.addressLabel, fontSize = 11.5.sp, color = TextDarkPrimary)
                            }
                            val hierarchy = listOf(identity.locality, identity.administrativeRegion, identity.country)
                                .filter { it.isNotBlank() }
                                .joinToString(" • ")
                            if (hierarchy.isNotBlank()) {
                                Text(hierarchy, fontSize = 11.sp, color = TextDarkSecondary)
                            }
                            if (identity.featureName.isNotBlank() && !identity.featureName.equals(identity.primaryName, ignoreCase = true)) {
                                Text("Feature: ${identity.featureName} (${identity.category})", fontSize = 10.5.sp, color = TextDarkMuted)
                            }
                        }
                    }

                    // Section B: Nearby Places & Points
                    AreaSectionBlock(title = "Nearby (within ${details.radiusUsedMeters} m)") {
                        if (details.nearbyPlaces.isEmpty()) {
                            Text(
                                text = "No nearby named places found within ${details.radiusUsedMeters} m.",
                                fontSize = 11.sp,
                                color = TextDarkMuted
                            )
                        } else {
                            details.nearbyPlaces.take(8).forEach { item ->
                                val isChecked = state.selectedNearbyIdsForSave.contains(item.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleNearbySelection(item.id) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (showSaveCustomizer || !details.isSavedSnapshot) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { onToggleNearbySelection(item.id) },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = IndigoAccent,
                                                checkmarkColor = SurfaceWhite
                                            ),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextDarkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.category} • ${SavedAreaNearbyCodec.formatMeters(item.distanceMeters)} (${item.sourceLabel})",
                                            fontSize = 10.sp,
                                            color = TextDarkSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section C: Geographic Details
                    if (details.geographicDetails.isNotEmpty()) {
                        AreaSectionBlock(title = "Geographic Details") {
                            details.geographicDetails.take(6).forEach { line ->
                                Text("• $line", fontSize = 11.sp, color = TextDarkPrimary, lineHeight = 14.sp)
                            }
                        }
                    }

                    // Section D: Wikipedia / Reference
                    val wiki = details.wikiInfo
                    if (wiki != null && wiki.isReliableMatch) {
                        AreaSectionBlock(title = "Wikipedia & Reference") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = SkyBluePrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = wiki.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextDarkPrimary
                                )
                            }
                            Text(
                                text = wiki.summary,
                                fontSize = 11.sp,
                                color = TextDarkSecondary,
                                lineHeight = 15.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = listOfNotNull(wiki.wikidataId, wiki.attribution).joinToString(" • "),
                                    fontSize = 9.5.sp,
                                    color = TextDarkMuted,
                                    modifier = Modifier.weight(1f)
                                )
                                if (wiki.url.startsWith("https://")) {
                                    TextButton(
                                        onClick = {
                                            try {
                                                uriHandler.openUri(wiki.url)
                                            } catch (_: Throwable) {
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Open Wiki", fontSize = 11.sp, color = SkyBluePrimary)
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Open Wikipedia article",
                                            tint = SkyBluePrimary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!details.wikiStatusNote.isNullOrBlank()) {
                        AreaSectionBlock(title = "Wikipedia & Reference") {
                            Text(details.wikiStatusNote, fontSize = 11.sp, color = TextDarkMuted)
                        }
                    }

                    // Section E: Saved user notes (if any) & Customize save options
                    if (details.userNotes.isNotBlank() && !showSaveCustomizer) {
                        AreaSectionBlock(title = "Saved Area Notes") {
                            Text(details.userNotes, fontSize = 11.sp, color = TextDarkPrimary)
                        }
                    }

                    if (showSaveCustomizer) {
                        AreaSectionBlock(title = "Snapshot Options Before Saving") {
                            OutlinedTextField(
                                value = state.customNameDraft,
                                onValueChange = onUpdateCustomName,
                                label = { Text("Area Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = state.userNotesDraft,
                                onValueChange = onUpdateNotesDraft,
                                label = { Text("Offline Notes (Optional)") },
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Section F: Coordinates, DMS & Source Attribution
                    AreaSectionBlock(title = "Coordinates & Attribution") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = AreaCoordinateFormatter.formatDecimalDegrees(state.latitude, state.longitude),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextDarkPrimary
                                )
                                Text(
                                    text = AreaCoordinateFormatter.formatDms(state.latitude, state.longitude),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextDarkSecondary
                                )
                            }
                            TextButton(
                                onClick = {
                                    val coordStr = AreaCoordinateFormatter.formatDecimalDegrees(state.latitude, state.longitude)
                                    clipboard.setText(AnnotatedString(coordStr))
                                    onCopyFeedback("Copied $coordStr")
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("area_details_copy_coords")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy coordinates", tint = SkyBluePrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 11.sp, color = SkyBluePrimary)
                            }
                        }
                        Text(
                            text = "Sources: ${details.sourceAttribution}",
                            fontSize = 9.5.sp,
                            color = TextDarkMuted,
                            modifier = Modifier.testTag("area_details_attribution")
                        )
                    }
                }

                HorizontalDivider(color = SurfaceBorderLight, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Action Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (savedAreaEntity != null && !showSaveCustomizer) {
                                onSaveArea()
                            } else {
                                onSaveArea()
                                showSaveCustomizer = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (savedAreaEntity != null) EmeraldOk else IndigoAccent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("area_details_save_button")
                    ) {
                        Icon(
                            imageVector = if (savedAreaEntity != null) Icons.Default.BookmarkAdded else Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (savedAreaEntity != null) "Update" else "Save Area",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (savedAreaEntity != null) {
                        OutlinedButton(
                            onClick = { onEditSavedArea(savedAreaEntity) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit saved area", tint = TextDarkPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit", fontSize = 11.sp, color = TextDarkPrimary)
                        }
                    } else {
                        TextButton(
                            onClick = { showSaveCustomizer = !showSaveCustomizer },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(if (showSaveCustomizer) "Hide Note" else "+ Note", fontSize = 11.sp, color = IndigoAccent)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = onRefreshOnline,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("area_details_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh online details",
                            tint = SkyBluePrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (savedAreaEntity != null) {
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("area_details_delete_button")
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
        }
    }

    if (showDeleteConfirm && savedAreaEntity != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Saved Area?") },
            text = { Text("Remove '${savedAreaEntity.name}' from offline Saved Areas? This does not affect your tile caches.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSavedArea(savedAreaEntity.id)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndicatorAlert)
                ) {
                    Text("Delete", color = SurfaceWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextDarkSecondary)
                }
            }
        )
    }
}

@Composable
private fun AreaSectionBlock(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkSecondary
        )
        content()
    }
}
