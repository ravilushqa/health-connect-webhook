package com.ravil.healthcore.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ravil.healthcore.HealthCoreApp
import com.ravil.healthcore.core.model.HealthDataType
import com.ravil.healthcore.core.net.ConnectionCheck
import com.ravil.healthcore.core.status.RunStatus
import com.ravil.healthcore.core.status.StatusText
import com.ravil.healthcore.data.AppPreferences
import com.ravil.healthcore.data.Settings
import com.ravil.healthcore.hc.HcPermissions
import com.ravil.healthcore.nutrition.NutritionPreferences
import com.ravil.healthcore.sync.MealRunner
import com.ravil.healthcore.sync.SyncJobs
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant

@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HealthCoreApp
    val prefs = remember { AppPreferences(app) }
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()

    var refreshTick by remember { mutableIntStateOf(0) }
    var sdkStatus by remember { mutableIntStateOf(HealthConnectClient.getSdkStatus(app)) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lastIngest by remember { mutableStateOf(prefs.lastRun(AppPreferences.Job.INGEST)) }
    var lastMeals by remember { mutableStateOf(prefs.lastRun(AppPreferences.Job.MEALS)) }
    var lastMealWrittenAt by remember { mutableStateOf(prefs.lastMealWrittenAt()) }
    var mealsWrittenTotal by remember { mutableIntStateOf(NutritionPreferences(app).getAppliedTotal()) }
    var ingestOn by remember { mutableStateOf(prefs.ingestEnabled) }
    var mealsOn by remember { mutableStateOf(MealRunner(app).isEnabled()) }
    var configured by remember { mutableStateOf(settings.hasIngestToken() || settings.hasNutritionToken()) }
    var syncing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<Pair<ConnectionCheck?, ConnectionCheck?>?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshTick) {
        now = Instant.now()
        sdkStatus = HealthConnectClient.getSdkStatus(app)
        granted = if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            try {
                HealthConnectClient.getOrCreate(app).permissionController.getGrantedPermissions()
            } catch (_: Exception) {
                emptySet()
            }
        } else emptySet()
        lastIngest = prefs.lastRun(AppPreferences.Job.INGEST)
        lastMeals = prefs.lastRun(AppPreferences.Job.MEALS)
        lastMealWrittenAt = prefs.lastMealWrittenAt()
        mealsWrittenTotal = NutritionPreferences(app).getAppliedTotal()
        ingestOn = prefs.ingestEnabled
        mealsOn = MealRunner(app).isEnabled()
        configured = settings.hasIngestToken() || settings.hasNutritionToken()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshTick++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Health Core", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    hostOf(prefs.baseUrl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }

        if (!configured) {
            InfoCard(
                title = "Not set up yet",
                body = "Open Settings, check the server URL and save the ingest and nutrition tokens.",
                action = "Open settings",
                onAction = onOpenSettings,
            )
        }

        HealthConnectCard(sdkStatus, granted) { permissionLauncher.launch(HcPermissions.ALL) }

        RunCard(
            title = "Health data → health-core",
            enabled = ingestOn,
            status = lastIngest,
            now = now,
            detail = { s -> if (s.records > 0) "${s.records} records" else null },
        )
        RunCard(
            title = "Meals → Health Connect",
            enabled = mealsOn,
            status = lastMeals,
            now = now,
            rows = listOf(
                "Last meal written" to (lastMealWrittenAt?.let { StatusText.ago(it, now) } ?: "None yet"),
                "Written in total" to mealsWrittenTotal.toString(),
            ),
            detail = { s ->
                listOfNotNull(
                    if (s.records > 0) "This run wrote ${s.records}" else "Queue was empty on this run",
                    if (s.errors > 0) "${s.errors} errors" else null,
                ).joinToString(" · ")
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    syncing = true
                    scope.launch {
                        try {
                            app.appScope.async { SyncJobs.runAll(app) }.await()
                        } finally {
                            syncing = false
                            refreshTick++
                        }
                    }
                },
                enabled = !syncing && (ingestOn || mealsOn),
                modifier = Modifier.weight(1f),
            ) {
                if (syncing) Spinner() else Text("Sync now")
            }
            OutlinedButton(
                onClick = {
                    checking = true
                    scope.launch {
                        try {
                            check = app.appScope.async { settings.check() }.await()
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking && configured,
                modifier = Modifier.weight(1f),
            ) {
                if (checking) Spinner() else Text("Check connection")
            }
        }

        check?.let { (ingest, meals) ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    CheckLine("Ingest token", ingest)
                    CheckLine("Nutrition token", meals)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Health values are never shown here; the cards report times, outcomes and counts only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HealthConnectCard(sdkStatus: Int, granted: Set<String>, onGrant: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            when (sdkStatus) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    val readTypes = HcPermissions.grantedTypes(granted).size
                    val total = HealthDataType.entries.size
                    StatusLine("Read access", "$readTypes of $total types", if (readTypes == total) OkColor else if (readTypes > 0) WarnColor else BadColor)
                    StatusLine(
                        "Background reads",
                        if (HcPermissions.READ_IN_BACKGROUND in granted) "Allowed" else "Not allowed",
                        if (HcPermissions.READ_IN_BACKGROUND in granted) OkColor else WarnColor,
                    )
                    StatusLine(
                        "Write meals",
                        if (HcPermissions.WRITE_NUTRITION in granted) "Allowed" else "Not allowed",
                        if (HcPermissions.WRITE_NUTRITION in granted) OkColor else WarnColor,
                    )
                    if (!granted.containsAll(HcPermissions.ALL)) {
                        OutlinedButton(onClick = onGrant) { Text("Grant permissions") }
                    }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    StatusLine("Status", "Needs an update", WarnColor)
                else -> StatusLine("Status", "Not available on this device", BadColor)
            }
        }
    }
}

@Composable
private fun RunCard(
    title: String,
    enabled: Boolean,
    status: RunStatus?,
    now: Instant,
    rows: List<Pair<String, String>> = emptyList(),
    detail: (RunStatus) -> String?,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when {
                !enabled -> Text("Off", color = MaterialTheme.colorScheme.onSurfaceVariant)
                status == null -> Text("No run yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    StatusLine("Last run", StatusText.ago(status.at, now), MaterialTheme.colorScheme.onSurface)
                    StatusLine("Result", StatusText.label(status.code), if (status.ok) OkColor else BadColor)
                    rows.forEach { (label, value) -> StatusLine(label, value, MaterialTheme.colorScheme.onSurface) }
                    detail(status)?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: String, onAction: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun CheckLine(label: String, result: ConnectionCheck?) {
    val (text, color) = when (result) {
        null -> "No token saved" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionCheck.OK -> "OK" to OkColor
        ConnectionCheck.UNAUTHORIZED -> "Token rejected" to BadColor
        ConnectionCheck.NOT_ENABLED -> "Not enabled on the server" to WarnColor
        ConnectionCheck.RATE_LIMITED -> "Rate limited, try later" to WarnColor
        ConnectionCheck.UNEXPECTED_RESPONSE -> "Unexpected answer (wrong URL?)" to BadColor
        ConnectionCheck.NETWORK_ERROR -> "Network error" to BadColor
    }
    StatusLine(label, text, color)
}

@Composable
internal fun StatusLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
}

private fun hostOf(url: String): String = try {
    URI(url).host ?: url
} catch (_: Exception) {
    url
}
