package com.frit.app.ui.creative.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

data class FilterItem(val id: String, val name: String, val emoji: String = "")

val DEFAULT_FILTERS = listOf(
    FilterItem("none", "Original"),
    FilterItem("grayscale", "B&W"),
    FilterItem("sepia", "Sepia"),
    FilterItem("vintage", "Vintage"),
    FilterItem("blur", "Blur"),
    FilterItem("sharpen", "Sharp"),
    FilterItem("negate", "Invert"),
    FilterItem("vignette", "Vignette")
)

@Composable
fun FilterCarousel(
    filters: List<FilterItem> = DEFAULT_FILTERS,
    selectedFilter: String = "none",
    onFilterSelected: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(filters) { filter ->
            val selected = filter.id == selectedFilter
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onFilterSelected(filter.id) },
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (selected) 4.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (filter.emoji.isNotEmpty()) {
                        Text(filter.emoji, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        filter.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
