package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SkyBlueBorder
import com.example.ui.theme.SkyBlueDim
import com.example.ui.theme.SkyBlueLight
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceWhiteTranslucent
import com.example.ui.theme.TextDarkMuted
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Compact Map Scale Bar:
 * Calculates approximate ground distance in meters/kilometers for a standard 64dp bar
 * based on current camera zoom and latitude:
 * metersPerPixel = 156543.03392 * cos(lat * PI / 180) / (2 ^ zoom)
 */
@Composable
fun MapScaleBar(
    latitude: Double,
    zoom: Double,
    modifier: Modifier = Modifier
) {
    // 64dp baseline bar width in pixels at standard density
    val barWidthDp = 56.dp
    val metersPerPixel = 156543.03392 * cos(Math.toRadians(latitude)) / (2.0.pow(zoom))
    val totalMeters = metersPerPixel * 56.0 * 2.0 // rough approximation for standard display density

    val distanceText = when {
        totalMeters >= 1000 -> {
            val km = (totalMeters / 1000.0).roundToInt().coerceAtLeast(1)
            "$km km"
        }
        totalMeters >= 500 -> "500 m"
        totalMeters >= 200 -> "200 m"
        totalMeters >= 100 -> "100 m"
        totalMeters >= 50 -> "50 m"
        totalMeters >= 20 -> "20 m"
        else -> "10 m"
    }

    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceWhiteTranslucent)
            .border(1.dp, SurfaceBorderLight, shape)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag("map_scale_bar"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = distanceText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Canvas(modifier = Modifier.size(width = barWidthDp, height = 4.dp)) {
                val strokeWidth = 2.dp.toPx()
                val tickHeight = 4.dp.toPx()
                // Horizontal base line
                drawLine(
                    color = SkyBluePrimary,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth
                )
                // Left tick
                drawLine(
                    color = SkyBluePrimary,
                    start = Offset(0f, size.height - tickHeight),
                    end = Offset(0f, size.height),
                    strokeWidth = strokeWidth
                )
                // Right tick
                drawLine(
                    color = SkyBluePrimary,
                    start = Offset(size.width, size.height - tickHeight),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth
                )
            }
        }
    }
}

/**
 * Compact Compass Indicator:
 * Reflects live map bearing (rotation).
 * Clicking the compass smoothly resets map rotation to North-Up (bearing = 0°).
 * Turns into an active Sky Blue highlight when rotated away from north.
 */
@Composable
fun MapCompassIndicator(
    bearing: Double,
    isNorthLocked: Boolean,
    onResetNorth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOffNorth = (bearing % 360.0 > 1.0) || (bearing % 360.0 < -1.0)
    val buttonShape = CircleShape

    Box(
        modifier = modifier
            .size(38.dp)
            .clip(buttonShape)
            .background(if (isOffNorth) SkyBlueLight else SurfaceWhiteTranslucent)
            .border(
                width = 1.dp,
                color = if (isOffNorth) SkyBlueBorder else SurfaceBorderLight,
                shape = buttonShape
            )
            .clickable(onClick = onResetNorth)
            .testTag("map_compass_indicator"),
        contentAlignment = Alignment.Center
    ) {
        // 4-Axis Cross Reticle Canvas that rotates according to the camera bearing
        Canvas(
            modifier = Modifier
                .size(24.dp)
                .rotate(-bearing.toFloat()) // Invert camera bearing so cross points to geographic North
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val armLength = size.height / 2f - 2.dp.toPx()
            val neutralArmLength = armLength * 0.72f

            // North Arm (Sky Blue primary arm - boldest and longest)
            drawLine(
                color = SkyBluePrimary,
                start = Offset(centerX, centerY - 2.dp.toPx()),
                end = Offset(centerX, centerY - armLength),
                strokeWidth = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            // South Arm (Subtle slate)
            drawLine(
                color = Color(0xFF94A3B8),
                start = Offset(centerX, centerY + 2.dp.toPx()),
                end = Offset(centerX, centerY + neutralArmLength),
                strokeWidth = 1.2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            // East Arm (Subtle slate)
            drawLine(
                color = Color(0xFF94A3B8),
                start = Offset(centerX + 2.dp.toPx(), centerY),
                end = Offset(centerX + neutralArmLength, centerY),
                strokeWidth = 1.2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            // West Arm (Subtle slate)
            drawLine(
                color = Color(0xFF94A3B8),
                start = Offset(centerX - 2.dp.toPx(), centerY),
                end = Offset(centerX - neutralArmLength, centerY),
                strokeWidth = 1.2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            // Center pivot reticle dot
            drawCircle(color = TextDarkPrimary, radius = 1.5.dp.toPx(), center = Offset(centerX, centerY))
        }
    }
}

/**
 * Visible Attribution Bar:
 * Displays required provider credits (OpenFreeMap, OpenMapTiles, OpenStreetMap contributors)
 * in an unobtrusive, accessible overlay badge.
 */
@Composable
fun MapAttributionBadge(
    attributionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(topStart = 8.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceWhiteTranslucent)
            .border(1.dp, SurfaceBorderLight, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("map_attribution_badge")
    ) {
        Text(
            text = attributionText,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = TextDarkMuted,
            maxLines = 1
        )
    }
}
