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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.OsmPoiRecord
import com.example.ui.screens.PoiSearchUiState
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

@Composable
fun PoiSearchContent(
    searchState: PoiSearchUiState,
    isDeviceOnline: Boolean,
    isInternetEnabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectPoi: (OsmPoiRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val isOfflineContext = !isDeviceOnline || !isInternetEnabled

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("poi_search_workspace")
    ) {
        // Local-Only Coverage Notice Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SkyBlueLight)
                .border(1.dp, SkyBlueBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = "Local Storage Only",
                tint = SkyBlueDim,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${searchState.totalLocalPoiCount} Local POIs Stored",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkyBlueDim
                    )
                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isOfflineContext) "Offline Mode • Zero Network" else "Local Data Only",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SkyBlueDim,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Searches OSM places already acquired from map areas you have viewed. Does not make web requests or provide global address search.",
                    fontSize = 10.sp,
                    color = TextDarkSecondary,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Query TextField
        OutlinedTextField(
            value = searchState.query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = "Search local POI name or category...",
                    fontSize = 13.sp,
                    color = TextDarkMuted
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = SkyBluePrimary
                )
            },
            trailingIcon = {
                if (searchState.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("poi_search_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search query",
                            tint = TextDarkSecondary
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SkyBluePrimary,
                unfocusedBorderColor = SurfaceBorderLight,
                focusedContainerColor = SurfaceOffWhite,
                unfocusedContainerColor = SurfaceOffWhite,
                focusedTextColor = TextDarkPrimary,
                unfocusedTextColor = TextDarkPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("poi_search_input")
        )

        // Category Filter Chips (built dynamically from actual Room POI categories)
        if (searchState.availableCategories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                modifier = Modifier.testTag("poi_category_filter_row")
            ) {
                item(key = "__all__") {
                    val isAllSelected = searchState.selectedCategory == null
                    FilterChip(
                        selected = isAllSelected,
                        onClick = { onSelectCategory(null) },
                        label = {
                            Text(
                                text = "All Categories",
                                fontSize = 11.sp,
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkyBlueLight,
                            selectedLabelColor = SkyBlueDim
                        )
                    )
                }
                items(searchState.availableCategories, key = { it.category }) { option ->
                    val isSelected = searchState.selectedCategory.equals(option.category, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectCategory(option.category) },
                        label = {
                            Text(
                                text = "${option.category} (${option.count})",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkyBlueLight,
                            selectedLabelColor = SkyBlueDim
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Loading indicator
        if (searchState.isSearching) {
            LinearProgressIndicator(
                color = SkyBluePrimary,
                trackColor = SkyBlueLight,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("poi_search_loading")
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Searching local Room database...",
                fontSize = 11.sp,
                color = TextDarkSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Error state
        searchState.errorMessage?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IndicatorAlert.copy(alpha = 0.08f))
                    .border(1.dp, IndicatorAlert.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = IndicatorAlert,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = err,
                    fontSize = 11.sp,
                    color = IndicatorAlert
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Main Content State Area
        when {
            searchState.totalLocalPoiCount == 0 -> {
                SettingsSectionCard {
                    Text(
                        text = "No Local POIs Acquired Yet",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "There are currently 0 POI records in local Room storage. Pan or zoom around an area on the map with Internet ON so visible vector tiles and their POIs are retained locally. Once stored, they remain searchable even in airplane mode.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            !searchState.hasSearched && searchState.query.isBlank() && searchState.selectedCategory == null -> {
                SettingsSectionCard {
                    Text(
                        text = "Ready for Offline Search",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Type part of a place name above or pick one of the ${searchState.availableCategories.size} acquired categories to browse up to 30 matching places stored on this device.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            searchState.hasSearched && searchState.results.isEmpty() && !searchState.isSearching -> {
                SettingsSectionCard {
                    Text(
                        text = "No Matching Local POIs",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val targetDesc = buildString {
                        if (searchState.query.isNotBlank()) append("\"${searchState.query.trim()}\"")
                        if (searchState.selectedCategory != null) {
                            if (isNotEmpty()) append(" in ")
                            append("category '${searchState.selectedCategory}'")
                        }
                    }
                    Text(
                        text = "No local records matched $targetDesc. Search only inspects geographic areas whose tiles have already been viewed and persisted locally.",
                        fontSize = 12.sp,
                        color = TextDarkSecondary,
                        lineHeight = 17.sp
                    )
                    if (searchState.query.isNotBlank() || searchState.selectedCategory != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                onQueryChange("")
                                if (searchState.selectedCategory != null) {
                                    onSelectCategory(null)
                                }
                            }
                        ) {
                            Text("Reset Search & Filters", fontSize = 12.sp, color = SkyBluePrimary)
                        }
                    }
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${searchState.results.size} local match${if (searchState.results.size == 1) "" else "es"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDarkSecondary
                    )
                    if (searchState.results.size >= 30) {
                        Text(
                            text = "Showing top 30 — refine query to narrow",
                            fontSize = 10.sp,
                            color = TextDarkMuted
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("poi_search_results_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(searchState.results, key = { it.osmId }) { poi ->
                        PoiSearchResultRow(
                            poi = poi,
                            onClick = {
                                focusManager.clearFocus()
                                onSelectPoi(poi)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PoiSearchResultRow(
    poi: OsmPoiRecord,
    onClick: () -> Unit
) {
    val categoryLabel = if (!poi.subCategory.isNullOrBlank() && !poi.subCategory.equals(poi.category, ignoreCase = true)) {
        "${poi.category} • ${poi.subCategory}"
    } else {
        poi.category
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("poi_result_${poi.osmId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
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
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = poi.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextDarkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = SkyBlueLight,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = categoryLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = SkyBlueDim,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Text(
                    text = "Local OSM POI",
                    fontSize = 9.sp,
                    color = TextDarkMuted
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${formatDecimal(poi.latitude, poi.longitude)}  (z${poi.sourceTileZ})",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextDarkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Show ${poi.name} on map",
            tint = SkyBluePrimary,
            modifier = Modifier.size(16.dp)
        )
    }
}
