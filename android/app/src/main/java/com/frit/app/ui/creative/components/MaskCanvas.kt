package com.frit.app.ui.creative.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun MaskCanvas(
    modifier: Modifier = Modifier,
    onMaskChanged: (Path) -> Unit = {}
) {
    val path = remember { Path() }
    var isDrawing by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Draw mask area",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(8.dp)
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.1f))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDrawing = true
                                path.moveTo(offset.x, offset.y)
                            },
                            onDrag = { change, _ ->
                                if (isDrawing) {
                                    path.lineTo(change.position.x, change.position.y)
                                    onMaskChanged(path)
                                }
                            },
                            onDragEnd = {
                                isDrawing = false
                                path.close()
                                onMaskChanged(path)
                            }
                        )
                    }
            ) {
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(width = 4f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = { path.reset(); onMaskChanged(path) }) {
                    Text("Clear")
                }
                TextButton(onClick = { path.reset(); onMaskChanged(path) }) {
                    Text("Reset")
                }
            }
        }
    }
}
