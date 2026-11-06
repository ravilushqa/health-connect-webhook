package com.ravil.healthcore

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ravil.healthcore.ui.HealthCoreTheme

/**
 * Shown by Health Connect when the user asks why Health Core wants access
 * (ACTION_SHOW_PERMISSIONS_RATIONALE, and VIEW_PERMISSION_USAGE on Android 14+).
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ draws apps edge to edge; the app is dark only, so the bars get light icons
        // and the content is padded clear of the status bar, cutout, navigation bar and keyboard.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            HealthCoreTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Health Core and your data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        POLICY.forEach { (title, body) ->
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }

    private companion object {
        val POLICY = listOf(
            "What it reads" to
                "The Health Connect record types you allow (activity, sleep, vitals, body measurements, " +
                "nutrition, cycle tracking and the others listed in the permission screen), from the last 48 hours.",
            "Where it goes" to
                "Only to the health-core server whose URL you set in Settings, over HTTPS, authenticated with " +
                "your own token. There is no analytics, no advertising, and no other server.",
            "What it writes" to
                "Meals that your health-core server queues for you are written to Health Connect as nutrition " +
                "records. Nothing else is written.",
            "What stays on the phone" to
                "Tokens are stored encrypted with the Android Keystore and are excluded from backups. The app keeps " +
                "only sync times, outcomes and record counts, never health values, and writes no health data to logs.",
            "Stopping" to
                "Turn sync off in Settings or revoke access in Health Connect at any time. Data already sent is " +
                "controlled by your health-core server.",
        )
    }
}
