package com.frit.app.ui.creative.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared creative-suite top bar.
 *
 * Icon contract (every icon always does its job):
 * - Undo/Redo: step back/forward through edits (editors only).
 * - Save: persists the current result into the "My Creations" project library.
 * - Download: writes the current result to Downloads as a real file.
 * - Export: opens the Export screen pre-loaded with the current result.
 *
 * List/hub screens pass compact = true and get back + title only.
 */
@Composable
fun CreativeTopBar(
    title: String,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    saveEnabled: Boolean = false,
    onSave: () -> Unit = {},
    downloadEnabled: Boolean = false,
    onDownload: () -> Unit = {},
    exportEnabled: Boolean = false,
    onExport: () -> Unit = {},
    compact: Boolean = false,
    onBack: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (!compact) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.Default.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.Default.Redo, contentDescription = "Redo")
                }
                IconButton(onClick = onSave, enabled = saveEnabled) {
                    Icon(Icons.Default.Save, contentDescription = "Save to library")
                }
                IconButton(onClick = onDownload, enabled = downloadEnabled) {
                    Icon(Icons.Default.Download, contentDescription = "Download file")
                }
                IconButton(onClick = onExport, enabled = exportEnabled) {
                    Icon(Icons.Default.Share, contentDescription = "Export")
                }
            }
        }
    }
}
