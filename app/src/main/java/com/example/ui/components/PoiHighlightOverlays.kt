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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

@Composable
fun PoiHighlightPopupContent(
    highlightedPoi: OsmPoiRecord?,
    isHighlightVisible: Boolean,
    onToggleVisibility: () -> Unit,
    onRecenter: () -> Unit,
    onClearHighlight: () -> Unit,
    onOpenSearch: () -> Unit,
    onClose: () -> Unit
) {
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
                    text = "Highlight POI",
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
                        text = "Local Map",
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

        Spacer(modifier = Modifier.height(4.dp))

        if (highlightedPoi != null) {
            val categoryLabel = if (
                !highlightedPoi.subCategory.isNullOrBlank() &&
                !highlightedPoi.subCategory.equals(highlightedPoi.category, ignoreCase = true)
            ) {
                "${highlightedPoi.category} • ${highlightedPoi.subCategory}"
            } else {
                highlightedPoi.category
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SkyBlueLight)
                    .border(1.dp, SkyBlueBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = highlightedPoi.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$categoryLabel  •  Tile z${highlightedPoi.sourceTileZ}/${highlightedPoi.sourceTileX}/${highlightedPoi.sourceTileY}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = SkyBlueDim
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatDecimal(highlightedPoi.latitude, highlightedPoi.longitude),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDarkSecondary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceOffWhite)
                    .clickable { onToggleVisibility() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Show Map Marker",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDarkPrimary
                )
                Switch(
                    checked = isHighlightVisible,
                    onCheckedChange = { onToggleVisibility() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SkyBluePrimary
                    ),
                    modifier = Modifier.testTag("highlight_visibility_switch")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onRecenter()
                        onClose()
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("highlight_recenter_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = SkyBluePrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Center", fontSize = 11.sp, color = SkyBlueDim)
                }

                OutlinedButton(
                    onClick = onClearHighlight,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("highlight_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = TextDarkSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", fontSize = 11.sp, color = TextDarkPrimary)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = onOpenSearch,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SkyBluePrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("highlight_open_search_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Search Different Local POI", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceOffWhite)
                    .border(1.dp, SurfaceBorderLight, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "No POI Currently Highlighted",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Use Offline POI Search to find a locally acquired place and highlight its exact location on the live map.",
                    fontSize = 11.sp,
                    color = TextDarkSecondary,
                    lineHeight = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onOpenSearch,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SkyBluePrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("highlight_open_search_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Offline POI Search", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Highlights locally cached OSM POIs. Full polygon and boundary drawing remains scheduled for a future milestone.",
            fontSize = 10.sp,
            color = TextDarkMuted,
            lineHeight = 13.sp
        )
    }
}
