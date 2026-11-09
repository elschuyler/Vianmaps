package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.MapCameraState
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceOffWhite
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.HorizontalDivider
import com.example.ui.theme.SkyBlueBorder
import com.example.ui.theme.SkyBlueLight

/**
 * Converts decimal degrees to standard GPS DMS format:
 * DD°MM'SS.SS" N/S  DD°MM'SS.SS" E/W
 * Matching the exact visual format in the reference image.
 */
fun formatDms(latitude: Double, longitude: Double): String {
    val latHemisphere = if (latitude >= 0.0) "N" else "S"
    val absLat = abs(latitude)
    val latDeg = absLat.toInt()
    val latMin = ((absLat - latDeg) * 60.0).toInt()
    val latSec = ((absLat - latDeg) * 60.0 - latMin) * 60.0

    val lngHemisphere = if (longitude >= 0.0) "E" else "W"
    val absLng = abs(longitude)
    val lngDeg = absLng.toInt()
    val lngMin = ((absLng - lngDeg) * 60.0).toInt()
    val lngSec = ((absLng - lngDeg) * 60.0 - lngMin) * 60.0

    return String.format(
        Locale.US,
        "%d°%02d'%04.1f\"%s  %d°%02d'%04.1f\"%s",
        latDeg, latMin, latSec, latHemisphere,
        lngDeg, lngMin, lngSec, lngHemisphere
    )
}

/**
 * Converts decimal degrees to high-precision readable decimal notation:
 * DD.DDDDD° N/S, DD.DDDDD° E/W
 */
fun formatDecimal(latitude: Double, longitude: Double): String {
    val latHemisphere = if (latitude >= 0.0) "N" else "S"
    val lngHemisphere = if (longitude >= 0.0) "E" else "W"
    return String.format(
        Locale.US,
        "%.5f° %s, %.5f° %s",
        abs(latitude), latHemisphere,
        abs(longitude), lngHemisphere
    )
}

/**
 * Top Coordinate Panel:
 * - Foldable on tap: shows clean DMS coordinates by default with a subtle chevron indicator.
 * - Unfolded state reveals high-precision decimal coordinates, live camera telemetry (Zoom/Bearing/Network),
 *   and a dedicated "Sensor Panel" action button.
 */
@Composable
fun TopCoordinateInfoPanel(
    cameraState: MapCameraState,
    isCollapsed: Boolean,
    isInternetEnabled: Boolean,
    locationStatusText: String,
    onToggleCollapse: () -> Unit,
    onOpenSensorPanel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .wrapContentSize()
            .clip(cardShape)
            .background(SurfaceWhite)
            .border(1.dp, SurfaceBorderLight, cardShape)
            .clickable { onToggleCollapse() }
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag("top_coordinate_panel")
    ) {
        Column {
            // Primary Header Row: DMS Coordinates + chevron indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formatDms(cameraState.latitude, cameraState.longitude),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    color = TextDarkPrimary,
                    maxLines = 1
                )
                Icon(
                    imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (isCollapsed) "Expand Details" else "Collapse Details",
                    tint = TextDarkSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }

            // Unfolded telemetry breakdown & Sensor Panel action button
            AnimatedVisibility(visible = !isCollapsed) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(
                        color = SurfaceBorderLight,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )

                    // Decimal degrees
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "DEC:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBluePrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = formatDecimal(cameraState.latitude, cameraState.longitude),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextDarkPrimary
                        )
                    }

                    // Zoom, Bearing, and Network telemetry
                    Text(
                        text = "Zoom: ${String.format(Locale.US, "%.1f", cameraState.zoom)} • Bearing: ${cameraState.bearing.toInt()}° • Net: ${if (isInternetEnabled) "ON" else "OFF"}",
                        fontSize = 10.sp,
                        color = TextDarkSecondary,
                        fontFamily = FontFamily.Monospace
                    )

                    // Sensor Panel placeholder button
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SkyBlueLight)
                            .border(1.dp, SkyBlueBorder, RoundedCornerShape(6.dp))
                            .clickable { onOpenSensorPanel() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = "Sensor Panel",
                            tint = SkyBluePrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Sensor Panel",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBluePrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stepped Scale Ruler & Ratio Tile:
 * Matches the reference image directly beneath the coordinates panel:
 * - Map flag/ratio indicator (e.g. "1:3,239K  8/20")
 * - Stepped black/white ruler bracket (e.g. "|________| 50 km")
 */
@Composable
fun MapSteppedScaleTile(
    latitude: Double,
    zoom: Double,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(6.dp)
    val barWidthDp = 80.dp

    // Calculate real-world ground meters represented by an 80dp ruler on a standard mdpi/xhdpi screen
    val metersPerPixel = 156543.03392 * cos(Math.toRadians(latitude)) / (2.0.pow(zoom))
    val totalMeters = metersPerPixel * 80.0 * 2.0

    val (distanceText, bracketMeters) = when {
        totalMeters >= 500_000 -> Pair("500 km", 500_000)
        totalMeters >= 200_000 -> Pair("200 km", 200_000)
        totalMeters >= 100_000 -> Pair("100 km", 100_000)
        totalMeters >= 50_000 -> Pair("50 km", 50_000)
        totalMeters >= 20_000 -> Pair("20 km", 20_000)
        totalMeters >= 10_000 -> Pair("10 km", 10_000)
        totalMeters >= 5_000 -> Pair("5 km", 5_000)
        totalMeters >= 2_000 -> Pair("2 km", 2_000)
        totalMeters >= 1_000 -> Pair("1 km", 1_000)
        totalMeters >= 500 -> Pair("500 m", 500)
        totalMeters >= 200 -> Pair("200 m", 200)
        totalMeters >= 100 -> Pair("100 m", 100)
        totalMeters >= 50 -> Pair("50 m", 50)
        totalMeters >= 20 -> Pair("20 m", 20)
        else -> Pair("10 m", 10)
    }

    // Ratio formatting (e.g. 1:3,239K)
    val scaleDenominator = (metersPerPixel * 39.3701 * 160.0).roundToInt()
    val ratioText = when {
        scaleDenominator >= 1_000_000 -> String.format(Locale.US, "1:%,dK", scaleDenominator / 1000)
        scaleDenominator >= 1_000 -> String.format(Locale.US, "1:%,d", scaleDenominator)
        else -> "1:$scaleDenominator"
    }

    val zoomInt = zoom.toInt().coerceIn(0, 22)
    val zoomFraction = "$zoomInt/20"

    Box(
        modifier = modifier
            .wrapContentSize()
            .clip(cardShape)
            .background(SurfaceWhite)
            .border(1.dp, SurfaceBorderLight, cardShape)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("map_stepped_scale_tile")
    ) {
        Column {
            // Top row: Map flag icon, ratio, and zoom fraction
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = TextDarkPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "$ratioText  $zoomFraction",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Stepped bracket scale ruler
            Column {
                Canvas(modifier = Modifier.size(width = barWidthDp, height = 5.dp)) {
                    val stroke = 1.5.dp.toPx()
                    val tickHeight = 5.dp.toPx()

                    // Left vertical end tick
                    drawLine(
                        color = Color.Black,
                        start = Offset(0f, 0f),
                        end = Offset(0f, tickHeight),
                        strokeWidth = stroke
                    )
                    // Horizontal base line
                    drawLine(
                        color = Color.Black,
                        start = Offset(0f, tickHeight),
                        end = Offset(size.width, tickHeight),
                        strokeWidth = stroke
                    )
                    // Right vertical end tick
                    drawLine(
                        color = Color.Black,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, tickHeight),
                        strokeWidth = stroke
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = distanceText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
            }
        }
    }
}
