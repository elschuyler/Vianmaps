package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.diagnostics.DiagnosticEvent
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.ui.screens.MapUiState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Full-Screen Log Keeper Workspace.
 * Conforms strictly to the project's Sky Blue & White theme and Log Keeper Standard:
 * - Real-time map loading stage, last error, and network status
 * - Bounded structured diagnostic events
 * - Severity filters (ALL, DEBUG, INFO, WARNING, ERROR)
 * - Safe copy to clipboard & system document export (zero broad permissions)
 * - Clear log action
 */
@Composable
fun LogKeeperScreenContent(
    uiState: MapUiState,
    isDeviceOnline: Boolean,
    connectionType: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val events by DiagnosticRepository.eventsFlow.collectAsStateWithLifecycle()
    var selectedSeverity by remember { mutableStateOf<LogSeverity?>(null) }

    val filteredEvents = remember(events, selectedSeverity) {
        if (selectedSeverity == null) events.reversed()
        else events.filter { it.severity == selectedSeverity }.reversed()
    }

    // System document picker for exporting diagnostic report
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val report = DiagnosticRepository.getExportReport(selectedSeverity)
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(report.toByteArray())
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Log Keeper report exported successfully", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to export report: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("log_keeper_screen")
    ) {
        // Telemetry & Status Summary Card
        StatusSummaryCard(
            uiState = uiState,
            isDeviceOnline = isDeviceOnline,
            connectionType = connectionType,
            eventCount = events.size
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Action & Filter Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Severity Filter Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    label = "ALL",
                    isSelected = selectedSeverity == null,
                    onClick = { selectedSeverity = null }
                )
                FilterChip(
                    label = "ERR",
                    isSelected = selectedSeverity == LogSeverity.ERROR,
                    onClick = { selectedSeverity = LogSeverity.ERROR },
                    activeColor = IndicatorAlert
                )
                FilterChip(
                    label = "WARN",
                    isSelected = selectedSeverity == LogSeverity.WARNING,
                    onClick = { selectedSeverity = LogSeverity.WARNING },
                    activeColor = SkyBlueDim
                )
                FilterChip(
                    label = "INFO",
                    isSelected = selectedSeverity == LogSeverity.INFO,
                    onClick = { selectedSeverity = LogSeverity.INFO }
                )
            }

            // Copy & Export Actions
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("VianMaps_Logs", DiagnosticRepository.getExportReport(selectedSeverity))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = SkyBluePrimary, modifier = Modifier.size(18.dp))
                }

                IconButton(
                    onClick = {
                        val fileName = "vian_maps_log_${System.currentTimeMillis()}.txt"
                        exportDocumentLauncher.launch(fileName)
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Export Report", tint = SkyBluePrimary, modifier = Modifier.size(18.dp))
                }

                IconButton(
                    onClick = {
                        DiagnosticRepository.clear()
                        Toast.makeText(context, "Log history cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = "Clear Logs", tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = SurfaceBorderLight)
        Spacer(modifier = Modifier.height(8.dp))

        // Diagnostic Event History
        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No diagnostic events recorded for selected filter.",
                    fontSize = 12.sp,
                    color = TextDarkMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredEvents, key = { it.id }) { event ->
                    DiagnosticEventRow(event = event)
                }
            }
        }
    }
}

@Composable
private fun StatusSummaryCard(
    uiState: MapUiState,
    isDeviceOnline: Boolean,
    connectionType: String,
    eventCount: Int
) {
    val cardShape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, cardShape)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MAP ENGINE & TELEMETRY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SkyBluePrimary
            )
            Text(
                text = "$eventCount / 250 events",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = TextDarkMuted
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Stage & Map Ready
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Stage: ${uiState.mapLoadingStage}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextDarkPrimary
            )
            Text(
                text = if (uiState.isMapReady) "Map: READY" else "Map: LOADING/IDLE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (uiState.isMapReady) SkyBluePrimary else TextDarkMuted
            )
        }

        // Style
        Text(
            text = "Active Style: ${uiState.currentStyle.displayName} (${uiState.currentStyle.providerName})",
            fontSize = 11.sp,
            color = TextDarkSecondary
        )

        // Network Status
        Text(
            text = "Device Network: ${if (isDeviceOnline) "ONLINE ($connectionType)" else "OFFLINE"} • In-App: ${if (uiState.isInternetEnabled) "ON" else "OFF (Blocked)"}",
            fontSize = 11.sp,
            color = TextDarkSecondary
        )

        // Last error if any
        if (!uiState.lastMapError.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Last Error: ${uiState.lastMapError}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = IndicatorAlert
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    activeColor: Color = SkyBluePrimary
) {
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (isSelected) SkyBlueLight else SurfaceOffWhite)
            .border(
                width = 1.dp,
                color = if (isSelected) activeColor else SurfaceBorderLight,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) activeColor else TextDarkSecondary
        )
    }
}

@Composable
private fun DiagnosticEventRow(event: DiagnosticEvent) {
    val rowShape = RoundedCornerShape(8.dp)
    val severityColor = when (event.severity) {
        LogSeverity.ERROR -> IndicatorAlert
        LogSeverity.WARNING -> SkyBlueDim
        LogSeverity.INFO -> SkyBluePrimary
        LogSeverity.DEBUG -> TextDarkMuted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(SurfaceOffWhite)
            .border(1.dp, SurfaceBorderLight, rowShape)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(severityColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[${event.formattedTime}] ${event.source}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
            }
            Text(
                text = event.severity.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = severityColor
            )
        }

        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = event.description,
            fontSize = 11.sp,
            color = TextDarkPrimary
        )

        event.stage?.let {
            Text(
                text = "Stage: $it",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = SkyBlueDim
            )
        }

        event.errorDetails?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = it,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = IndicatorAlert
            )
        }
    }
}
