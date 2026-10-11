package com.ravil.healthconnect.nutrition

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ravil.healthconnect.nutrition.ui.MainScreen
import com.ravil.healthconnect.nutrition.ui.theme.NutritionBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NutritionBridgeTheme {
                MainScreen()
            }
        }
    }
}
