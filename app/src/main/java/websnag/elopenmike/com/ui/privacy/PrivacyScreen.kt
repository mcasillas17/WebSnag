package websnag.elopenmike.com.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyScreen(
    internetPermissionDeclared: Boolean,
    onExportBackup: (String, Boolean) -> Unit,
    onImportBackup: (String) -> Unit,
    onExportActivity: () -> Unit,
    onDeleteHistory: () -> Unit,
    onDeleteAllData: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var includeHistory by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Privacy, exports & data", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (internetPermissionDeclared) "Internet permission is declared. This build is not local-only."
                else "No Internet permission is declared. WebSnag does not use cloud accounts or telemetry.",
                color = if (internetPermissionDeclared) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            PrivacyCard(
                "What stays on this device",
                "Profiles, schedules, NFC tag metadata, theme/retention preferences, and optional focus history. Raw NFC payloads are not exported. Accessibility cannot retrieve window content."
            )
        }
        item {
            Column {
                Text("Encrypted backup", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Passphrase (12+ characters)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { includeHistory = !includeHistory }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (includeHistory) "History: included (tap to exclude)" else "History: excluded (tap to include)")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onExportBackup(passphrase, includeHistory) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Create encrypted backup")
                }
                OutlinedButton(onClick = { onImportBackup(passphrase) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore encrypted backup")
                }
                Text(
                    "Restore is refused while a focus profile is active, and imported profiles are restored inactive.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            Column {
                Text("Activity attestation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Exports signed history with a public key for offline verification. It proves only this installation signed this data; reinstalling loses the signing key.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onExportActivity, modifier = Modifier.fillMaxWidth()) {
                    Text("Export signed activity")
                }
            }
        }
        item {
            PrivacyCard(
                "NFC assurance",
                "Ordinary UID and static NDEF tags are low-assurance identifiers and can be replayed or copied. Authenticated tag hardware is not enabled in this build."
            )
        }
        item {
            Column {
                Text("Delete local data", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onDeleteHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete focus history")
                }
                OutlinedButton(onClick = onDeleteAllData, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete all WebSnag data")
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
