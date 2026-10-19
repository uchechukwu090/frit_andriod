package com.frit.app.ui.creative.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TextOverlayEditor(
    text: String = "",
    fontSize: Int = 32,
    color: Color = Color.White,
    onTextChange: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onColorChange: (Color) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Text Overlay", style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Text") },
            modifier = Modifier.fillMaxWidth()
        )

        Text("Size: ${fontSize}px", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = fontSize.toFloat(),
            onValueChange = { onFontSizeChange(it.toInt()) },
            valueRange = 12f..72f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Color", style = MaterialTheme.typography.labelSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(Color.White, Color.Black, Color.Red, Color.Blue, Color.Green, Color.Yellow).forEach { c ->
                Surface(
                    modifier = Modifier
                        .size(32.dp)
                        .padding(2.dp),
                    color = c,
                    shape = MaterialTheme.shapes.small,
                    onClick = { onColorChange(c) }
                ) {}
            }
        }
    }
}
