package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import com.example.data.db.OsmPoiRecord
import com.example.data.osm.PoiHighlightCategory
import com.example.ui.screens.PoiCategoryHighlightUiState
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
import java.util.Locale

@Composable
fun SelectedPoiHighlightBanner(
    poi: OsmPoiRecord,
    isHighlightVisible: Boolean,
    onRecenter: () -> Unit,
    onToggleVisibility: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryLabel = if (!poi.subCategory.isNullOrBlank() && !poi.subCategory.equals(poi.category, ignoreCase = true)) {
        "${poi.category} • ${poi.subCategory}"
    } else {
        poi.category
    }
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .widthIn(min = 240.dp, max = 310.dp)
            .shadow(4.dp, cardShape)
            .clip(cardShape)
            .background(SurfaceWhite)
            .border(1.dp, SkyBlueBorder, cardShape)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
            .testTag("selected_poi_banner")
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
                    .background(SkyBlueLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PinDrop,
                    contentDescription = null,
                    tint = SkyBluePrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = poi.name,
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
                    Surface(
                        color = SkyBlueLight,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = categoryLabel,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SkyBlueDim,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Local OSM POI",
                        fontSize = 9.sp,
                        color = TextDarkMuted
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatDecimal(poi.latitude, poi.longitude),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDarkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("poi_banner_dismiss")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear selected POI",
                    tint = TextDarkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        HorizontalDivider(
            color = SurfaceBorderLight,
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 4.dp, end = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onRecenter,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("poi_banner_recenter")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = SkyBluePrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Center",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyBlueDim
                )
            }

            TextButton(
                onClick = onToggleVisibility,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("poi_banner_toggle_visibility")
            ) {
                Icon(
                    imageVector = if (isHighlightVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    tint = SkyBluePrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isHighlightVisible) "Hide Marker" else "Show Marker",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyBlueDim
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoiHighlightPopupContent(
    highlightState: PoiCategoryHighlightUiState,
    onToggleCategory: (String) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit
) {
    val selectedCount = highlightState.selectedCategoryIds.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("poi_highlight_popup")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Highlight POIs",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = SkyBlueLight,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (selectedCount > 0) "$selectedCount Active" else "Viewport",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyBlueDim,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("poi_highlight_close")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Highlight controls",
                    tint = TextDarkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (highlightState.isRefreshing) {
            LinearProgressIndicator(
                color = SkyBluePrimary,
                trackColor = SkyBlueLight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .testTag("highlight_loading_bar")
            )
        }

        // Honest live status card (zoom pause / empty viewport / active count / prompt)
        val isZoomPaused = selectedCount > 0 && highlightState.isPausedByZoom
        val isEmptyViewport = selectedCount > 0 &&
            !highlightState.isPausedByZoom &&
            !highlightState.isRefreshing &&
            highlightState.hasQueriedViewport &&
            highlightState.matchedPois.isEmpty()

        val statusBackground = when {
            highlightState.errorMessage != null -> IndicatorAlert.copy(alpha = 0.08f)
            isZoomPaused -> Color(0xFFFEF3C7)
            selectedCount > 0 && highlightState.matchedPois.isNotEmpty() -> SkyBlueLight
            else -> SurfaceOffWhite
        }
        val statusBorder = when {
            highlightState.errorMessage != null -> IndicatorAlert.copy(alpha = 0.35f)
            isZoomPaused -> Color(0xFFF59E0B).copy(alpha = 0.45f)
            selectedCount > 0 && highlightState.matchedPois.isNotEmpty() -> SkyBlueBorder
            else -> SurfaceBorderLight
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(statusBackground)
                .border(1.dp, statusBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("highlight_status_card")
        ) {
            when {
                highlightState.errorMessage != null -> {
                    Text(
                        text = highlightState.errorMessage,
                        fontSize = 11.sp,
                        color = IndicatorAlert,
                        lineHeight = 14.sp
                    )
                }
                isZoomPaused -> {
                    Text(
                        text = "Paused at zoom ${formatZoomLevel(highlightState.currentZoom)} (resumes at z${formatZoomLevel(highlightState.minHighlightZoom)}+)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF92400E),
                        modifier = Modifier.testTag("highlight_zoom_paused_notice")
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$selectedCount selected categor${if (selectedCount == 1) "y remains" else "ies remain"} active and will highlight automatically when you zoom in.",
                        fontSize = 10.sp,
                        color = Color(0xFF78350F),
                        lineHeight = 13.sp
                    )
                }
                selectedCount == 0 -> {
                    Text(
                        text = "Select categories below to highlight matching local POIs inside the current map view.",
                        fontSize = 11.sp,
                        color = TextDarkSecondary,
                        lineHeight = 14.sp
                    )
                }
                highlightState.lastViewport == null -> {
                    Text(
                        text = "$selectedCount categor${if (selectedCount == 1) "y" else "ies"} active. Waiting for map viewport bounds.",
                        fontSize = 11.sp,
                        color = TextDarkSecondary,
                        lineHeight = 14.sp
                    )
                }
                isEmptyViewport -> {
                    Text(
                        text = "0 matching local POIs in this viewport",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary,
                        modifier = Modifier.testTag("highlight_empty_viewport_notice")
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Selections stay active while panning/zooming. Only POIs from locally acquired OpenFreeMap vector tiles appear.",
                        fontSize = 10.sp,
                        color = TextDarkSecondary,
                        lineHeight = 13.sp
                    )
                }
                else -> {
                    val count = highlightState.matchedPois.size
                    Text(
                        text = "Highlighting $count visible local POI${if (count == 1) "" else "s"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkyBlueDim,
                        modifier = Modifier.testTag("highlight_match_count")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable multi-select category pill container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 185.dp)
                .verticalScroll(rememberScrollState())
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (category in PoiHighlightCategory.entries) {
                    val isSelected = category.id in highlightState.selectedCategoryIds
                    val count = highlightState.categoryMatchCounts[category.id]
                    val showCount = isSelected && !isZoomPaused && highlightState.hasQueriedViewport && count != null
                    CategoryHighlightPill(
                        category = category,
                        isSelected = isSelected,
                        badgeCount = if (showCount) count else null,
                        onClick = { onToggleCategory(category.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onClearAll,
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("highlight_clear_all_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = if (selectedCount > 0) TextDarkPrimary else TextDarkMuted,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Clear All",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedCount > 0) TextDarkPrimary else TextDarkMuted
                )
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SkyBluePrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("highlight_done_button")
            ) {
                Text(
                    text = "Done",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = "Uses local OSM vector tile POI classes/subclasses (z<=14 cache). Standalone ATMs only; bank-nested ATMs are not separated in tiles.",
            fontSize = 9.5.sp,
            color = TextDarkMuted,
            lineHeight = 12.sp
        )
    }
}

@Composable
private fun CategoryHighlightPill(
    category: PoiHighlightCategory,
    isSelected: Boolean,
    badgeCount: Int?,
    onClick: () -> Unit
) {
    val accent = categoryAccentColor(category)
    val pillShape = RoundedCornerShape(20.dp)
    val labelText = if (badgeCount != null) "${category.displayName} ($badgeCount)" else category.displayName

    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(pillShape)
            .background(if (isSelected) accent.copy(alpha = 0.14f) else SurfaceOffWhite)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) accent else SurfaceBorderLight,
                shape = pillShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("highlight_category_pill_${category.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = labelText,
            fontSize = 11.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) TextDarkPrimary else TextDarkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isSelected) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "${category.displayName} selected",
                tint = accent,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun categoryAccentColor(category: PoiHighlightCategory): Color {
    return when (category) {
        PoiHighlightCategory.SHOP -> Color(0xFF6366F1)
        PoiHighlightCategory.GROCERY -> Color(0xFF10B981)
        PoiHighlightCategory.RESTAURANT -> Color(0xFFF59E0B)
        PoiHighlightCategory.CAFE -> Color(0xFF14B8A6)
        PoiHighlightCategory.PHARMACY -> Color(0xFFF43F5E)
        PoiHighlightCategory.HOSPITAL -> Color(0xFFDC2626)
        PoiHighlightCategory.BUS_STOP -> Color(0xFF8B5CF6)
        PoiHighlightCategory.ATM -> Color(0xFF0EA5E9)
        PoiHighlightCategory.FUEL -> Color(0xFFEA580C)
        PoiHighlightCategory.SCHOOL -> Color(0xFFEC4899)
        PoiHighlightCategory.PARKING -> Color(0xFF475569)
    }
}

private fun formatZoomLevel(zoom: Double): String {
    return String.format(Locale.US, "%.1f", zoom)
}
