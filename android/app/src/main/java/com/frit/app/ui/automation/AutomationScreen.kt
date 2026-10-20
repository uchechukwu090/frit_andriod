package com.frit.app.ui.automation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.frit.app.ui.settings.SettingToggle
import com.frit.app.ui.theme.Montserrat

/**
 * Automation surface composables, split out of MainActivity.kt (audit F-14).
 *
 * AutomationScreen is the automation page; AutoModeAppsSection is the
 * "apps the AI watches" list; AppScheduleDialog is its per-app schedule.
 */
@Composable
fun AppScheduleDialog(
    appLabel: String, pkg: String,
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onDismiss: () -> Unit,
    onSave: (intervalText: String, taskText: String) -> Unit
) {
    var intervalText by remember { mutableStateOf("") }
    var taskText by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(16.dp)).padding(20.dp)
        ) {
            Text("Watch schedule", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.height(4.dp))
            Text(appLabel, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
            Text(pkg, color = fg.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = Montserrat)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it },
                label = { Text("Every… (e.g. 30m, 2h, 1 day)", color = fg.copy(alpha = 0.6f)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = taskText,
                onValueChange = { taskText = it },
                label = { Text("Task (e.g. check unread chats)", color = fg.copy(alpha = 0.6f)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = fg)
                }
                Button(
                    onClick = { onSave(intervalText, taskText) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Save", color = bg, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// Embedded inside SettingsScreen (which already scrolls), so the list is a
// fixed-height LazyColumn rather than fillMaxSize — a LazyColumn with
// unbounded height inside verticalScroll would crash at measure time.
@Composable
fun AutoModeAppsSection(
    fg: Color, surf: Color, stroke: Color, bg: Color
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) } // dropdown: collapsed by default
    var filter by remember { mutableStateOf("") }
    var scheduleFor by remember { mutableStateOf<com.frit.app.features.automation.AutoModeApps.AppEntry?>(null) }
    val apps = remember { mutableStateListOf<com.frit.app.features.automation.AutoModeApps.AppEntry>() }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.frit.app.features.automation.AutoModeApps.listInstalled(context)
        }
        val sel = com.frit.app.features.automation.AutoModeApps.selected(context)
        apps.clear()
        apps.addAll(list)
        checked.clear()
        list.forEach { checked[it.packageName] = sel.contains(it.packageName) }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Apps the AI watches", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text(
                    if (loading) "Loading apps…"
                    else "${checked.values.count { it }} of ${apps.size} watched — tap to open",
                    color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat
                )
            }
            Text(if (expanded) "▲" else "▼", color = fg, fontSize = 14.sp)
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(8.dp))
        Text(
            "Checked apps are watched: their notifications wake the agent, which replies or acts as you would.",
            color = fg, fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val all = apps.map { it.packageName }.toSet()
                    com.frit.app.features.automation.AutoModeApps.replaceAll(context, all)
                    checked.clear()
                    apps.forEach { checked[it.packageName] = true }
                    Toast.makeText(context, "Watching all ${apps.size} apps — expect frequent agent turns", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Check all", color = fg) }
            OutlinedButton(
                onClick = {
                    com.frit.app.features.automation.AutoModeApps.replaceAll(context, emptySet())
                    checked.clear()
                    apps.forEach { checked[it.packageName] = false }
                    Toast.makeText(context, "Unwatched all apps", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Uncheck all", color = fg) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Search apps", color = fg.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(12.dp))
        scheduleFor?.let { target ->
            AppScheduleDialog(
                appLabel = target.label, pkg = target.packageName,
                fg = fg, surf = surf, stroke = stroke, bg = bg,
                onDismiss = { scheduleFor = null },
                onSave = { intervalText, taskText ->
                    val sched = com.frit.app.features.automation.AutomationScheduler
                    val secs = sched.parseIntervalInput(intervalText, 30L)
                    sched.scheduleAppTask(
                        target.packageName, target.label,
                        taskText.ifBlank { "Check for anything needing attention" }, secs
                    )
                    checked[target.packageName] = true
                    Toast.makeText(context, "Watching ${target.label} every ${sched.formatInterval(secs)}", Toast.LENGTH_LONG).show()
                    scheduleFor = null
                }
            )
        }
        if (loading) {
            Text("Loading apps…", color = fg, fontSize = 14.sp, fontFamily = Montserrat)
        } else {
            val shown = apps.filter {
                filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true)
            }
            Column(Modifier.fillMaxWidth().height(380.dp).verticalScroll(rememberScrollState())) {
                shown.forEach { app ->
                    val isChecked = checked[app.packageName] == true
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val next = !isChecked
                            checked[app.packageName] = next
                            com.frit.app.features.automation.AutoModeApps.setSelected(context, app.packageName, next)
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hue = ((app.packageName.hashCode() and 0x7fffffff) % 360).toFloat()
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(Color.hsv(hue, 0.5f, 0.62f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                app.label.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                (if (app.isSystem) "system · " else "") + app.packageName,
                                color = fg.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1
                            )
                        }
                        IconButton(onClick = { scheduleFor = app }) {
                            Icon(Icons.Default.Alarm, "Schedule watch", tint = fg)
                        }
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                checked[app.packageName] = it
                                com.frit.app.features.automation.AutoModeApps.setSelected(context, app.packageName, it)
                            }
                        )
                    }
                    HorizontalDivider(color = stroke.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun AutomationScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    automodeEnabled: Boolean,
    onAutomodeEnabled: (Boolean) -> Unit,
    watchedAppCount: Int,
    notifAccessGranted: Boolean,
    onEnableNotifAccess: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activeTasks = remember { mutableStateListOf<String>() }
    var newTaskText by remember { mutableStateOf("") }
    var newIntervalMins by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        activeTasks.clear()
        activeTasks.addAll(com.frit.app.features.automation.AutomationScheduler.listActiveTasks())
    }
    Column(Modifier.fillMaxSize().background(bg).padding(24.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automation", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(20.dp))

        // ---- Auto Mode (moved from Settings — same functions, new home) ----
        SettingToggle("Auto Mode", automodeEnabled, onAutomodeEnabled, fg, stroke)
        Text(
            "AI harnesses the phone: auto-answers calls, supervises watchers, runs scheduled tasks, live status in notification.",
            color = fg, fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Watching $watchedAppCount app(s)" + if (notifAccessGranted) " · notification access ON" else " · notification access OFF",
            color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat
        )
        Spacer(Modifier.height(8.dp))
        if (!notifAccessGranted) {
            OutlinedButton(onClick = onEnableNotifAccess, modifier = Modifier.fillMaxWidth()) {
                Text("Enable notification access", color = fg)
            }
            Spacer(Modifier.height(8.dp))
        }
        AutoModeAppsSection(fg = fg, surf = surf, stroke = stroke, bg = bg)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val ok = com.frit.app.core.A11yKeeper.requestBatteryExemption(context)
                Toast.makeText(
                    context,
                    if (ok) "Choose “Allow” so the system never sleeps Frit" else "Already exempt — Frit is protected",
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Keep Frit alive (battery exemption)", color = fg) }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(16.dp))

        Text("Scheduled tasks", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = newTaskText,
            onValueChange = { newTaskText = it },
            label = { Text("Task Description / Prompt", color = fg.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newIntervalMins,
            onValueChange = { newIntervalMins = it },
            label = { Text("Interval (e.g. 180, 180 mins, 3 hours, 90s)", color = fg.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val secs = com.frit.app.features.automation.AutomationScheduler.parseIntervalInput(newIntervalMins, 5L)
                if (newTaskText.isNotBlank()) {
                    com.frit.app.features.automation.AutomationScheduler.scheduleTask(newTaskText, newTaskText, secs)
                    activeTasks.clear()
                    activeTasks.addAll(com.frit.app.features.automation.AutomationScheduler.listActiveTasks())
                    newTaskText = ""
                    newIntervalMins = ""
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = fg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Task", color = bg)
        }

        Spacer(Modifier.height(20.dp))
        Text("Active Tasks:", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))

    Column(Modifier.fillMaxWidth()) {
            activeTasks.forEach { taskId ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(taskId, color = fg, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        com.frit.app.features.automation.AutomationScheduler.cancelTask(taskId)
                        activeTasks.remove(taskId)
                    }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                }
            }
        }
    }
}
