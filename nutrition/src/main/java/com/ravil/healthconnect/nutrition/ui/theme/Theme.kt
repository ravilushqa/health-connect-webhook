package com.ravil.healthconnect.nutrition.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green80 = Color(0xFF60ad5e)
private val GreenDark = Color(0xFF2E7D32)

private val LightColors = lightColorScheme(
    primary = GreenDark,
    onPrimary = Color.White,
    secondary = Green80,
    onSecondary = Color.Black,
)

@Composable
fun NutritionBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
