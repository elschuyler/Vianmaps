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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.map.activation.MapActivationBoundary
import com.example.map.activation.MapActivationState
import com.example.map.activation.MapFeatureActivation

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
 * Lightweight host destination shell managing independent activation and deactivation
 * between Map and Notes without keeping Map UI composed beneath Notes.
 */
@Composable
fun AppHostShell(
    initialDestination: AppHostDestination = AppHostDestination.MAP,
    mapFeature: MapActivationBoundary = MapFeatureActivation,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentDestination by remember { mutableStateOf(initialDestination) }

    val openMap: () -> Unit = {
        if (mapFeature.state.value !is MapActivationState.Active) {
            mapFeature.activate(context)
        }
        currentDestination = AppHostDestination.MAP
    }

    val openNotes: () -> Unit = {
        currentDestination = AppHostDestination.NOTES
        mapFeature.deactivate()
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
            mapFeature.Content(
                modifier = modifier.fillMaxSize(),
                onOpenNotes = openNotes
            )
        }
        AppHostDestination.NOTES -> {
            NotesPlaceholderScreen(
                onReturnToMap = openMap,
                modifier = modifier.fillMaxSize()
            )
        }
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
