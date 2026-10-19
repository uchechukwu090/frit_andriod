package com.frit.app.ui.creative.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ColorAdjustmentSliders(
    brightness: Float = 1f,
    contrast: Float = 1f,
    saturation: Float = 1f,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Adjust", style = MaterialTheme.typography.titleSmall)

        Text("Brightness", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = brightness,
            onValueChange = onBrightnessChange,
            valueRange = 0.5f..1.5f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Contrast", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = contrast,
            onValueChange = onContrastChange,
            valueRange = 0.5f..1.5f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Saturation", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = saturation,
            onValueChange = onSaturationChange,
            valueRange = 0f..2f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
