package websnag.elopenmike.com.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Exact confirmation wording, shared with the instrumented test that guards the two-step gate. */
const val LEGACY_UNLOCK_CONFIRMATION: String =
    "I understand this permanently replaces every unsupported lock with a stricter one"

/** Typed friction for releasing a lockdown no retry can repair. */
const val PAUSE_BLOCKING_PHRASE: String = "I choose to pause blocking"

/**
 * Shown instead of the normal app whenever persisted state cannot be read -- most importantly when
 * an initialization migration aborted and deliberately kept the original bytes on disk.
 *
 * Blocking stays fully active behind this screen, so it must never be the only thing standing
 * between the user and their device: emergency calls, the dialer, the home launcher and WebSnag
 * itself stay exempt in every state. Nothing here deletes data, and the one irreversible option is
 * gated behind an explicit confirmation.
 */
@Composable
fun StorageRecoveryScreen(
    onRetry: () -> Unit,
    onApproveLegacyUnlockConversion: () -> Unit,
    onPauseBlocking: () -> Unit,
    blockingPaused: Boolean
) {
    var confirmed by remember { mutableStateOf(false) }
    var intention by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Saved data could not be loaded", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (blockingPaused) {
                "WebSnag kept your saved profiles, tags and schedules exactly as they were and did " +
                    "not start with empty settings. You paused blocking, so nothing is being " +
                    "blocked right now. It starts again by itself as soon as your data loads."
            } else {
                "WebSnag kept your saved profiles, tags and schedules exactly as they were and did " +
                    "not start with empty settings. Until they load, blocking stays fully active " +
                    "for every app. Emergency calls, your phone dialer, your home screen and " +
                    "WebSnag itself are still reachable."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "If the cause was temporary, trying again is usually enough.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }

        Text("Still not loading?", style = MaterialTheme.typography.titleMedium)
        Text(
            "One kind of older lock cannot be carried forward: a timed lock that never named an " +
                "NFC tag. There is no timer to expire it and no matching lock today, so WebSnag " +
                "will not choose one for you. You can replace every lock of that kind with the " +
                "strictest lock available: the session keeps blocking, no tap and no tag can end " +
                "it, and the usual emergency unlock with its waiting period stays available. The " +
                "original timer is discarded. Everything else -- profiles, blocked apps, tags and " +
                "schedules -- is kept.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = confirmed, role = Role.Checkbox, onValueChange = { confirmed = it }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = confirmed, onCheckedChange = null)
            Text(
                LEGACY_UNLOCK_CONFIRMATION,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedButton(
            onClick = onApproveLegacyUnlockConversion,
            enabled = confirmed,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Replace the unsupported lock") }

        Text("If nothing above helps", style = MaterialTheme.typography.titleMedium)
        Text(
            "Some failures cannot be repaired from here, such as a lost device key. Rather than " +
                "leave you with a phone that blocks everything, you can pause blocking until your " +
                "data loads. Your data stays untouched, this screen stays here, and blocking " +
                "starts again by itself the moment your data loads.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = intention,
            onValueChange = { intention = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !blockingPaused,
            label = { Text("Intention phrase") },
            supportingText = { Text("Type: $PAUSE_BLOCKING_PHRASE") },
            singleLine = true
        )
        OutlinedButton(
            onClick = onPauseBlocking,
            enabled = !blockingPaused && intention.trim() == PAUSE_BLOCKING_PHRASE,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (blockingPaused) "Blocking is paused" else "Pause blocking until data loads") }
    }
}
