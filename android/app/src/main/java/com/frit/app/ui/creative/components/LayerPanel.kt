package com.frit.app.ui.creative.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

data class LayerItem(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f
)

@Composable
fun LayerPanel(
    layers: List<LayerItem>,
    selectedLayerId: String?,
    onLayerSelected: (String) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onReorderLayers: (Int, Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(200.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Layers",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(12.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(layers.reversed()) { index, layer ->
                    val actualIndex = layers.size - 1 - index
                    val selected = layer.id == selectedLayerId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLayerSelected(layer.id) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                layer.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onToggleVisibility(layer.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle visibility",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            // Reorder (visually up = higher index in the underlying list).
                            IconButton(
                                onClick = { if (actualIndex < layers.size - 1) onReorderLayers(actualIndex, actualIndex + 1) },
                                modifier = Modifier.size(24.dp),
                                enabled = actualIndex < layers.size - 1
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { if (actualIndex > 0) onReorderLayers(actualIndex, actualIndex - 1) },
                                modifier = Modifier.size(24.dp),
                                enabled = actualIndex > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
