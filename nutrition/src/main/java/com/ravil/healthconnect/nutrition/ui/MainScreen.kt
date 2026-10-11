package com.ravil.healthconnect.nutrition.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.ravil.healthconnect.nutrition.NutritionApplication
import com.ravil.healthconnect.nutrition.R
import com.ravil.healthconnect.nutrition.data.SyncResult
import com.ravil.healthconnect.nutrition.health.HealthConnectWriter
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences
import com.ravil.healthconnect.nutrition.sync.NutritionSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { NutritionPreferences(context) }
    val writer = remember { HealthConnectWriter(context) }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(prefs.getBaseUrl() ?: "") }
    var tokenInput by remember { mutableStateOf("") }
    var syncEnabled by remember { mutableStateOf(prefs.isSyncEnabled()) }
    // syncEnabledSaved reflects what is actually persisted — used to gate Pull Now so that
    // an unsaved toggle change does not enable the button before Settings are saved.
    var syncEnabledSaved by remember { mutableStateOf(prefs.isSyncEnabled()) }
    var intervalMinutes by remember { mutableStateOf(prefs.getSyncIntervalMinutes().toString()) }

    var pendingCount by remember { mutableStateOf(prefs.getPendingCount()) }
    var appliedTotal by remember { mutableStateOf(prefs.getAppliedTotal()) }
    var lastErrors by remember { mutableStateOf(prefs.getLastSyncErrorCount()) }
    var lastMessage by remember { mutableStateOf(prefs.getLastSyncMessage()) }
    // null = never attempted, true = last save succeeded, false = last save failed
    var saveResult by remember { mutableStateOf<Boolean?>(null) }

    var hasWritePermission by remember { mutableStateOf(false) }
    var isHcAvailable by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    fun refreshCounters() {
        pendingCount = prefs.getPendingCount()
        appliedTotal = prefs.getAppliedTotal()
        lastErrors = prefs.getLastSyncErrorCount()
        lastMessage = prefs.getLastSyncMessage()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hasWritePermission = writer.writePermissionSet().all { it in granted }
    }

    LaunchedEffect(Unit) {
        isHcAvailable = writer.isAvailable()
        if (isHcAvailable) {
            hasWritePermission = writer.hasWritePermission()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        // Server URL
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.label_server_url)) },
            placeholder = { Text("https://health-connect.ravil.space") },
            singleLine = true,
            isError = baseUrl.isNotBlank() && !baseUrl.startsWith("https://"),
            supportingText = {
                if (baseUrl.isNotBlank() && !baseUrl.startsWith("https://"))
                    Text(stringResource(R.string.url_must_be_https))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Token input (masked)
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text(stringResource(R.string.label_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            placeholder = { Text("paste token here to save") },
            modifier = Modifier.fillMaxWidth(),
        )

        // Save settings button — creates an immutable settings ticket synchronously on the UI
        // thread (which also invalidates in-flight syncs and marks a pending update), then
        // applies the ticket in an IO coroutine under the sync mutex.
        Button(
            onClick = {
                // Snapshot all compose state fields before launching the coroutine so the
                // values reflect what the user saw when they tapped Save, not a later
                // recomposition that might occur while the coroutine is waiting for the mutex.
                val trimmedUrl = baseUrl.trim()
                val tok = if (tokenInput.isNotBlank()) tokenInput else null
                val interval = intervalMinutes.toIntOrNull() ?: 60
                val enabled = syncEnabled  // snapshot before launch

                // Synchronous: increments consentGeneration (kills in-flight syncs), marks
                // pendingUpdate=true (blocks new syncs), captures the request atomically.
                // Must happen on the UI thread BEFORE the coroutine is dispatched.
                val request = NutritionSyncManager.beginSettingsUpdate(
                    baseUrl = trimmedUrl,
                    tokenInput = tok,
                    syncEnabled = enabled,
                    intervalMinutes = interval,
                )

                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        NutritionSyncManager(context).applySettings(request)
                    }
                    saveResult = ok
                    if (ok) {
                        if (tok != null) tokenInput = ""
                        syncEnabledSaved = prefs.isSyncEnabled()
                        (context.applicationContext as? NutritionApplication)?.rescheduleWorkerIfEnabled()
                    }
                    // On failure: token and reschedule are intentionally left unchanged so the
                    // user can retry — we must not silently report success for a failed write.
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_saved))
        }

        when (saveResult) {
            true -> Text(
                text = stringResource(R.string.settings_saved_ok),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
            false -> Text(
                text = stringResource(R.string.settings_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            null -> {}
        }

        HorizontalDivider()

        // Sync interval
        OutlinedTextField(
            value = intervalMinutes,
            onValueChange = { intervalMinutes = it },
            label = { Text(stringResource(R.string.label_sync_interval)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        // Enable sync toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.label_sync_enabled))
            Switch(
                checked = syncEnabled,
                onCheckedChange = { newValue ->
                    syncEnabled = newValue
                    // Toggling off synchronously invalidates any in-flight sync so it cannot
                    // complete with the user's intent to disable. An unsaved re-enable does
                    // not restore consent — only a successful Save lifts the pending block.
                    if (!newValue) NutritionSyncManager.invalidateConsent()
                },
            )
        }

        HorizontalDivider()

        // Health Connect status & permission
        if (!isHcAvailable) {
            Text(
                stringResource(R.string.hc_not_available),
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!hasWritePermission) {
            Button(
                onClick = { permissionLauncher.launch(writer.writePermissionSet()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_grant_permission))
            }
        }

        // Counters card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sync status", style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CounterItem(stringResource(R.string.status_pending), pendingCount)
                    CounterItem(stringResource(R.string.status_applied), appliedTotal)
                    CounterItem(stringResource(R.string.status_errors), lastErrors)
                }
                if (lastMessage.isNotBlank()) {
                    Text(
                        text = "Last: $lastMessage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Manual pull button
        Button(
            onClick = {
                if (!isHcAvailable) return@Button
                scope.launch {
                    isSyncing = true
                    val permitted = withContext(Dispatchers.IO) { writer.hasWritePermission() }
                    if (!permitted) {
                        hasWritePermission = false
                        isSyncing = false
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        NutritionSyncManager(context).performSync()
                    }
                    refreshCounters()
                    isSyncing = false
                }
            },
            enabled = !isSyncing && isHcAvailable && hasWritePermission && syncEnabledSaved && syncEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text(stringResource(R.string.btn_pull_now))
            }
        }

        Text(
            text = stringResource(R.string.permission_rationale),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CounterItem(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
