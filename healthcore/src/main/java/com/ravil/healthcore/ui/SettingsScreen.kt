@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ravil.healthcore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ravil.healthcore.HealthCoreApp
import com.ravil.healthcore.core.model.Resolutions
import com.ravil.healthcore.core.model.label
import com.ravil.healthcore.core.net.HealthCoreHttp
import com.ravil.healthcore.core.status.StatusText
import com.ravil.healthcore.data.AppPreferences
import com.ravil.healthcore.data.Settings
import com.ravil.healthcore.data.SettingsDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as HealthCoreApp
    val settings = remember { Settings(app) }
    val initial = remember { settings.current() }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var ingestToken by remember { mutableStateOf<String?>(null) }
    var nutritionToken by remember { mutableStateOf<String?>(null) }
    var ingestEnabled by remember { mutableStateOf(initial.ingestEnabled) }
    var mealsEnabled by remember { mutableStateOf(initial.mealsEnabled) }
    var interval by remember { mutableStateOf(initial.intervalMinutes.toString()) }
    val resolutions = remember { mutableStateMapOf<com.ravil.healthcore.core.model.HealthDataType, Int>().apply { putAll(initial.resolutions) } }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var tokenTick by remember { mutableStateOf(0) }

    val urlOk = HealthCoreHttp.isCanonicalHttpsUrl(baseUrl.trim())
    val intervalValue = interval.toIntOrNull()
    val intervalOk = intervalValue != null && intervalValue >= AppPreferences.MIN_INTERVAL_MINUTES

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }

        Section("Server") {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("health-core URL") },
                singleLine = true,
                isError = !urlOk,
                supportingText = { if (!urlOk) Text("https:// only, no query or credentials") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            // tokenTick forces a re-read of the stored state after a save.
            key(tokenTick) {
                TokenRow(
                    title = "Ingest token (X-Health-Token)",
                    saved = settings.hasIngestToken(),
                    updatedAt = settings.tokenUpdatedAt(AppPreferences.Token.INGEST),
                    input = ingestToken,
                    onInput = { ingestToken = it },
                )
                TokenRow(
                    title = "Nutrition token (X-Nutrition-Token)",
                    saved = settings.hasNutritionToken(),
                    updatedAt = settings.tokenUpdatedAt(AppPreferences.Token.NUTRITION),
                    input = nutritionToken,
                    onInput = { nutritionToken = it },
                )
            }
        }

        Section("Sync") {
            SwitchRow("Send health data to health-core", ingestEnabled) { ingestEnabled = it }
            SwitchRow("Write meals to Health Connect", mealsEnabled) { mealsEnabled = it }
            OutlinedTextField(
                value = interval,
                onValueChange = { v -> interval = v.filter { it.isDigit() }.take(4) },
                label = { Text("Interval, minutes") },
                singleLine = true,
                isError = !intervalOk,
                supportingText = { Text("At least ${AppPreferences.MIN_INTERVAL_MINUTES} (Android's minimum for periodic work)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Data resolution") {
            Text(
                "Steps, distance, calories, hydration and nutrition are always sent as raw Health Connect records, so health-core can deduplicate them by id.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Resolutions.configurable.forEachIndexed { i, type ->
                if (i > 0) HorizontalDivider()
                Text(type.label, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Resolutions.options(type).forEach { opt ->
                        FilterChip(
                            selected = resolutions[type] == opt.minutes,
                            onClick = { resolutions[type] = opt.minutes },
                            label = { Text(opt.label) },
                        )
                    }
                }
            }
        }

        message?.let { (ok, text) ->
            Text(text, color = if (ok) OkColor else BadColor, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = {
                saving = true
                message = null
                val draft = SettingsDraft(
                    baseUrl = baseUrl,
                    ingestTokenInput = ingestToken,
                    nutritionTokenInput = nutritionToken,
                    ingestEnabled = ingestEnabled,
                    mealsEnabled = mealsEnabled,
                    intervalMinutes = intervalValue ?: 0,
                    resolutions = resolutions.toMap(),
                )
                scope.launch {
                    val error = try {
                        app.appScope.async { settings.save(draft) }.await()
                    } finally {
                        saving = false
                    }
                    if (error == null) {
                        ingestToken = null
                        nutritionToken = null
                        tokenTick++
                        message = true to "Saved"
                    } else {
                        message = false to error
                    }
                }
            },
            enabled = !saving && urlOk && intervalOk,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "Saving…" else "Save") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A token is write-only: the stored value is never shown or prefilled, only whether one is
 * saved and when it was last replaced. [input] is null until the user starts replacing it.
 */
@Composable
private fun TokenRow(title: String, saved: Boolean, updatedAt: Instant?, input: String?, onInput: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    StatusText.tokenState(saved, updatedAt, Instant.now()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saved) OkColor else WarnColor,
                )
            }
            if (input == null) {
                TextButton(onClick = { onInput("") }) { Text(if (saved) "Replace" else "Add") }
            } else {
                TextButton(onClick = { onInput(null) }) { Text("Cancel") }
            }
        }
        if (input != null) {
            OutlinedTextField(
                value = input,
                onValueChange = { onInput(it) },
                label = { Text("New token") },
                placeholder = { Text("Paste the token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                supportingText = { Text("Stored encrypted when you tap Save") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
