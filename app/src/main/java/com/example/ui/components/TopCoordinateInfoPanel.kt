package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.MapCameraState
import com.example.ui.theme.SkyBlueLight
import com.example.ui.theme.SkyBluePrimary
import com.example.ui.theme.SurfaceBorderLight
import com.example.ui.theme.SurfaceWhiteTranslucent
import com.example.ui.theme.TextDarkMuted
import com.example.ui.theme.TextDarkPrimary
import com.example.ui.theme.TextDarkSecondary
import java.util.Locale

@Composable
fun TopCoordinateInfoPanel(
    cameraState: MapCameraState,
    isCollapsed: Boolean,
    isInternetEnabled: Boolean,
    locationStatusText: String,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val panelShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 210.dp, max = 320.dp)
            .clip(panelShape)
            .background(SurfaceWhiteTranslucent)
            .border(1.dp, SurfaceBorderLight, panelShape)
            .animateContentSize()
            .clickable { onToggleCollapse() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("top_coordinate_panel")
    ) {
        // Folded Header (White background, Sky Blue accent, dark readable text)
        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = String.format(Locale.US, "%.4f° N, %.4f° W", cameraState.latitude, -cameraState.longitude),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = TextDarkPrimary
                )
                Text(
                    text = String.format(Locale.US, "Z: %.1f • %03.0f°", cameraState.zoom, cameraState.bearing),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextDarkSecondary
                )
            }

            // Compact Sky-Blue Sensor Indicator
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SkyBlueLight)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = "Sensor Board",
                    modifier = Modifier.size(11.dp),
                    tint = SkyBluePrimary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Standby",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyBluePrimary
                )
            }

            Icon(
                imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (isCollapsed) "Expand Information" else "Collapse Information",
                modifier = Modifier.size(16.dp),
                tint = TextDarkSecondary
            )
        }

        // Expanded details
        AnimatedVisibility(visible = !isCollapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = "LOCATION STATUS",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyBluePrimary
                )
                Text(
                    text = locationStatusText,
                    fontSize = 10.sp,
                    color = TextDarkSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "NETWORK & CACHE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyBluePrimary
                )
                Text(
                    text = if (isInternetEnabled) "In-App Internet: ON" else "In-App Internet: OFF (App Blocked)",
                    fontSize = 10.sp,
                    color = TextDarkSecondary
                )
                Text(
                    text = "Sensors: Inactive in panel",
                    fontSize = 9.sp,
                    color = TextDarkMuted
                )
            }
        }
    }
}
