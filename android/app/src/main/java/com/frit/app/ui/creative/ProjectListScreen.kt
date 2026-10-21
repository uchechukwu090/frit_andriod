package com.frit.app.ui.creative

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.features.creative.CreativeProject
import com.frit.app.features.creative.CreativeSuiteEngine
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ProjectListScreen(
    onClose: () -> Unit = {},
    onProjectSelected: (String) -> Unit = {}
) {
    var projects by remember { mutableStateOf<List<CreativeProject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectType by remember { mutableStateOf("image") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val response = com.frit.app.data.NetworkClient.getProjects()
            val json = org.json.JSONObject(response)
            val arr = json.optJSONArray("projects")
            if (arr != null) {
                projects = (0 until arr.length()).map { i ->
                    CreativeProject.fromJson(arr.getJSONObject(i))
                }
            }
        } catch (e: Exception) { Diag.warn("Creative", "projects_load", e) }
        isLoading = false
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Projects",
                onBack = onClose,
                compact = true
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewProjectDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New Project")
            }
        }
    ) { padding ->
        if (error.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        } else if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No projects yet", style = MaterialTheme.typography.bodyMedium)
                    Text("Tap + to create your first project", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(projects) { project ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onProjectSelected(project.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // F-13 follow-up: the project API already returns a
                            // thumbnail (and the assets carry their own image),
                            // but this row drew a generic icon instead — the
                            // library showed NO pictures at all. Render the real
                            // thumbnail, falling back to the first image asset.
                            val projectImage = project.thumbnailUrl
                                ?: project.assets.firstOrNull { it.kind == "image" }?.currentUrl
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                if (projectImage != null) {
                                    com.frit.app.ui.creative.components.CreativeImage(
                                        source = projectImage,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        label = "",
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (project.type == "video") Icons.Default.Videocam else Icons.Default.Image,
                                            contentDescription = null
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${if (project.assetCount > 0) project.assetCount else project.assets.size} assets • ${project.type} • ${project.status}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    // F-13 follow-up: NetworkClient returns an
                                    // error JSON instead of throwing, so a
                                    // failed delete used to remove the row from
                                    // the UI while the server still had it.
                                    val resp = runCatching {
                                        com.frit.app.data.NetworkClient.deleteProject(project.id)
                                    }.getOrDefault("")
                                    val ok = runCatching {
                                        org.json.JSONObject(resp).optBoolean("ok", false)
                                    }.getOrDefault(false)
                                    if (ok) {
                                        projects = projects.filter { it.id != project.id }
                                    } else {
                                        error = "Could not delete the project — it is still on the server."
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }

        if (showNewProjectDialog) {
            AlertDialog(
                onDismissRequest = { showNewProjectDialog = false },
                title = { Text("New Project") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newProjectName,
                            onValueChange = { newProjectName = it },
                            label = { Text("Project name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Type", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("image", "video", "storyboard", "batch").forEach { type ->
                                FilterChip(
                                    selected = newProjectType == type,
                                    onClick = { newProjectType = type },
                                    label = { Text(type.replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val result = CreativeSuiteEngine.createProject(newProjectName, newProjectType)
                                if (result.isSuccess) {
                                    showNewProjectDialog = false
                                    newProjectName = ""
                                    val response = com.frit.app.data.NetworkClient.getProjects()
                                    val json = org.json.JSONObject(response)
                                    val arr = json.optJSONArray("projects")
                                    if (arr != null) {
                                        projects = (0 until arr.length()).map { i ->
                                            CreativeProject.fromJson(arr.getJSONObject(i))
                                        }
                                    }
                                }
                            }
                        },
                        enabled = newProjectName.isNotBlank()
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewProjectDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
