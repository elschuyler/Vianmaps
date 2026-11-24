package com.example.host

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.map.activation.MapActivationBoundary
import com.example.map.activation.MapActivationLoadingScreen
import com.example.map.activation.MapActivationRecoveryScreen
import com.example.map.activation.MapActivationState
import com.example.map.activation.MapDeactivationResult
import com.example.map.activation.MapFeatureActivation
import kotlinx.coroutines.launch

/**
 * Top-level host destinations for Vian Maps.
 * Map and Notes are independently activated halves of the same application:
 * entering Notes never activates Map, and entering Map never activates Notes.
 */
enum class AppHostDestination {
    MAP,
    NOTES
}

const val EXTRA_INITIAL_DESTINATION = "com.example.host.INITIAL_DESTINATION"
const val EXTRA_DESTINATION_MAP = "map"
const val EXTRA_DESTINATION_NOTES = "notes"

fun resolveInitialHostDestination(intent: Intent?): AppHostDestination {
    val raw = intent?.getStringExtra(EXTRA_INITIAL_DESTINATION)?.trim()?.lowercase()
    return when (raw) {
        EXTRA_DESTINATION_NOTES -> AppHostDestination.NOTES
        else -> AppHostDestination.MAP
    }
}

/**
 * Lightweight host destination shell managing independent activation and ordered asynchronous
 * deactivation between Map and Notes without keeping Map UI composed beneath Notes.
 */
@Composable
fun AppHostShell(
    initialDestination: AppHostDestination = AppHostDestination.MAP,
    mapFeature: MapActivationBoundary = MapFeatureActivation,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activationState by mapFeature.state.collectAsStateWithLifecycle()
    var currentDestination by remember { mutableStateOf(initialDestination) }
    var isTransitioningToNotes by remember { mutableStateOf(false) }
    var deactivationErrorMessage by remember { mutableStateOf<String?>(null) }

    val openMap: () -> Unit = {
        deactivationErrorMessage = null
        val currentState = mapFeature.state.value
        if (currentState !is MapActivationState.Active && currentState !is MapActivationState.Activating) {
            mapFeature.activate(context)
        }
        currentDestination = AppHostDestination.MAP
    }

    val openNotes: () -> Unit = {
        if (!isTransitioningToNotes) {
            deactivationErrorMessage = null
            isTransitioningToNotes = true
            coroutineScope.launch {
                try {
                    when (val result = mapFeature.deactivate()) {
                        is MapDeactivationResult.Deactivated,
                        is MapDeactivationResult.AlreadyInactive -> {
                            currentDestination = AppHostDestination.NOTES
                        }
                        is MapDeactivationResult.Failed -> {
                            deactivationErrorMessage = result.message
                        }
                    }
                } finally {
                    isTransitioningToNotes = false
                }
            }
        }
    }

    LaunchedEffect(currentDestination) {
        if (currentDestination == AppHostDestination.MAP &&
            mapFeature.state.value is MapActivationState.Inactive
        ) {
            mapFeature.activate(context)
        }
    }

    when (currentDestination) {
        AppHostDestination.MAP -> {
            when (val actState = activationState) {
                is MapActivationState.Active -> {
                    mapFeature.Content(
                        modifier = modifier.fillMaxSize(),
                        onOpenNotes = openNotes
                    )
                }
                is MapActivationState.Failed -> {
                    MapActivationRecoveryScreen(
                        failureMessage = actState.message,
                        onRetry = openMap,
                        onOpenNotes = openNotes,
                        modifier = modifier.fillMaxSize()
                    )
                }
                is MapActivationState.Activating,
                is MapActivationState.Inactive -> {
                    MapActivationLoadingScreen(
                        modifier = modifier.fillMaxSize(),
                        onOpenNotes = openNotes
                    )
                }
            }
        }
        AppHostDestination.NOTES -> {
            NotesPlaceholderScreen(
                onReturnToMap = openMap,
                modifier = modifier.fillMaxSize()
            )
        }
    }

    val pendingDeactivationError = deactivationErrorMessage
    if (pendingDeactivationError != null) {
        AlertDialog(
            onDismissRequest = { deactivationErrorMessage = null },
            modifier = Modifier.testTag("map_deactivation_error_dialog"),
            title = {
                Text(text = stringResource(id = R.string.map_deactivation_error_title))
            },
            text = {
                Text(
                    text = stringResource(
                        id = R.string.map_deactivation_error_body,
                        pendingDeactivationError
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = openNotes,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("map_deactivation_retry_button")
                ) {
                    Text(text = stringResource(id = R.string.map_deactivation_retry))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deactivationErrorMessage = null },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("map_deactivation_dismiss_button")
                ) {
                    Text(text = stringResource(id = R.string.map_deactivation_stay))
                }
            }
        )
    }
}

@Composable
fun NotesPlaceholderScreen(
    onReturnToMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onReturnToMap()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("notes_placeholder_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.notes_placeholder_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.notes_placeholder_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onReturnToMap,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("notes_return_to_map_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(id = R.string.notes_return_to_map))
            }
        }
    }
}
