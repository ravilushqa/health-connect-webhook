package com.ravil.healthcore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ravil.healthcore.ui.HealthCoreTheme
import com.ravil.healthcore.ui.MainScreen
import com.ravil.healthcore.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HealthCoreTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var inSettings by rememberSaveable { mutableStateOf(false) }
                    if (inSettings) {
                        BackHandler { inSettings = false }
                        SettingsScreen(onBack = { inSettings = false })
                    } else {
                        MainScreen(onOpenSettings = { inSettings = true })
                    }
                }
            }
        }
    }
}
