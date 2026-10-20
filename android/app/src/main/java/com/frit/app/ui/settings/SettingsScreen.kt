package com.frit.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.frit.app.ui.model.OnDeviceModelSection
import com.frit.app.ui.theme.Montserrat

/**
 * Settings surface composables, split out of MainActivity.kt (audit F-14).
 *
 * SettingsScreen is the settings page; the smaller building blocks
 * (UserProfileSection, OnboardingDialog, ProfileField, SettingItem,
 * SettingToggle, ExpandableSection) are its leaves.
 */
@Composable
fun SettingsScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    voiceEnabled: Boolean, onVoiceEnabled: (Boolean) -> Unit,
    wakeEnabled: Boolean, onWakeEnabled: (Boolean) -> Unit,
    screenCapEnabled: Boolean, onScreenCapEnabled: (Boolean) -> Unit,
    marketEnabled: Boolean, onMarketEnabled: (Boolean) -> Unit,
    automationEnabled: Boolean, onAutomationEnabled: (Boolean) -> Unit,
    isDark: Boolean, onThemeToggle: (Boolean) -> Unit,
    brainMode: com.frit.app.core.RouteController.Brain,
    onBrainModeChange: (com.frit.app.core.RouteController.Brain) -> Unit,
    privacyFilter: Boolean,
    onPrivacyFilterChange: (Boolean) -> Unit,
    isWakeTrained: Boolean,
    onTrainWakeWord: () -> Unit,
    appPin: String,
    onAppPinChange: (String) -> Unit,
    onSaveAppPin: () -> Unit,
    onClearAppPin: () -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    onSaveApiToken: () -> Unit,
    onWalletTap: () -> Unit,
    veilEnabled: Boolean,
    onVeilEnabled: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val brainOptions = com.frit.app.core.RouteController.Brain.values().toList()

    Column(Modifier.fillMaxSize().background(bg).padding(24.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(30.dp))

        Button(
            onClick = onWalletTap,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = fg)
        ) { Text("Wallet & Subscription", color = bg, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tiers, top-ups, passcodes and redeem codes live here.",
            color = fg, fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(16.dp))

        UserProfileSection(fg = fg, surf = surf, stroke = stroke, bg = bg)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        SettingToggle("Dark Mode", isDark, onThemeToggle, fg, stroke)

        Spacer(Modifier.height(8.dp))
        ExpandableSection(
            title = "Accessibilities",
            subtitle = "Voice, wake word, vision, engines & PIN vault",
            fg = fg, stroke = stroke
        ) {
            SettingToggle("Voice Output", voiceEnabled, onVoiceEnabled, fg, stroke)
            SettingToggle("Wake Word", wakeEnabled, onWakeEnabled, fg, stroke)
            SettingToggle("Screen Vision", screenCapEnabled, onScreenCapEnabled, fg, stroke)
            SettingToggle("Market Engine", marketEnabled, onMarketEnabled, fg, stroke)
            SettingToggle("Automation", automationEnabled, onAutomationEnabled, fg, stroke)
            Spacer(Modifier.height(12.dp))
            Text(
                if (isWakeTrained) "Wake status: trained — say \"Hey Frit\"" else "Wake status: not trained",
                color = fg, fontSize = 13.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onTrainWakeWord,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) {
                Text(if (isWakeTrained) "Retrain wake word" else "Train wake word", color = bg, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = stroke)
            Spacer(Modifier.height(12.dp))
            Text("App PIN vault", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
            Text(
                "Stored on this phone only (app-private). The AI receives it ONLY when it calls get_app_pin for an app screen demanding a PIN — e.g. OPay transaction PIN — and types it straight into the field. Phone lockscreen PINs cannot be automated by any third-party app (Android seals secure windows); keep the phone unlocked via Smart Lock / Extend Unlock for scheduled tasks instead.",
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = appPin,
                onValueChange = onAppPinChange,
                label = { Text(if (appPin.isBlank()) "No PIN stored" else "PIN stored ••••", color = fg.copy(alpha = 0.6f)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
            )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSaveAppPin,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Save PIN", color = bg, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onClearAppPin, modifier = Modifier.weight(1f)) {
                    Text("Clear", color = fg)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        ExpandableSection(
            title = "Screen veil (task lock)",
            subtitle = "FRIT's own lock while tasks run",
            fg = fg, stroke = stroke
        ) {
            val veil = com.frit.app.features.veil.VeilManager
            var showVeilPinDialog by remember { mutableStateOf(false) }
            var veilPin1 by remember { mutableStateOf("") }
            var veilPin2 by remember { mutableStateOf("") }
            var veilPinOld by remember { mutableStateOf("") }
            var veilPinErr by remember { mutableStateOf<String?>(null) }
            val veilPinSet = remember(veilEnabled, showVeilPinDialog) { veil.isPinSet(context) }
            val overlayGranted = remember { android.provider.Settings.canDrawOverlays(context) }
            val sysSecure = remember { veil.isSystemLockSecure(context) }

            SettingToggle("Screen veil", veilEnabled, { on ->
                if (on && !veil.isPinSet(context)) {
                    veilPinErr = "Set a veil PIN first — the veil must never stand guard without one."
                    veilPin1 = ""; veilPin2 = ""; veilPinOld = ""
                    showVeilPinDialog = true
                } else onVeilEnabled(on)
            }, fg, stroke)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !veilEnabled -> "Off. Tasks pause on the system lock as before."
                    !veilPinSet -> "On, but no PIN yet — set one below to arm it."
                    sysSecure -> "Ready, with one limit: your system lock is a secure PIN, which Android never lets any app dismiss. For hands-free tasks, relax it to Swipe (button below) — the veil then guards the phone instead."
                    else -> "Armed. The veil guards the phone while a task runs, is scheduled, or auto-mode is on; otherwise your normal lock shows. Face re-entry arrives in Phase 2."
                },
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    veilPin1 = ""; veilPin2 = ""; veilPinOld = ""; veilPinErr = null
                    showVeilPinDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text(if (veilPinSet) "Change veil PIN" else "Set veil PIN", color = bg, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            var faceTick by remember { mutableStateOf(0) }
            val faceEnrolled = remember(veilEnabled, faceTick) {
                com.frit.app.features.veil.FaceVerifier.isEnrolled(context)
            }
            val enrollLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { faceTick++ }
            OutlinedButton(
                onClick = {
                    try {
                        enrollLauncher.launch(android.content.Intent(
                            context,
                            com.frit.app.features.veil.FaceEnrollActivity::class.java
                        ))
                    } catch (e: Exception) { com.frit.app.core.Diag.warn("MainActivity", "launch FaceEnrollActivity", e) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (faceEnrolled) "Re-enroll face (pickup check)" else "Enroll face (pickup check)", color = fg) }
            Spacer(Modifier.height(4.dp))
            Text(
                if (faceEnrolled) "Face enrolled on this phone only — pickups during tasks verify silently, strangers get the ringtone."
                else "No face enrolled: pickups lock the veil without face check (PIN still works). Enrollment is 3 quick captures, kept on-device.",
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            if (!overlayGranted) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ))
                        } catch (e: Exception) { com.frit.app.core.Diag.warn("MainActivity", "overlay permission intent", e) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow display over other apps", color = fg) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "What it's for: waking the screen already works without this (tasks pulse it awake). This permission is what lets the guard itself appear over the lock screen — without it, tasks still run with the screen on, but awake and unguarded.",
                    color = fg, fontSize = 12.sp, fontFamily = Montserrat
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e: Exception) { com.frit.app.core.Diag.warn("MainActivity", "security settings intent", e) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open system lock settings", color = fg) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Safety: keep your phone around you while tasks run. Forgot the veil PIN? Restart the phone — the veil stays down for 2 minutes after boot so you can reset it here.",
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(4.dp))

            if (showVeilPinDialog) {
                AlertDialog(
                    onDismissRequest = { showVeilPinDialog = false },
                    title = { Text(if (veilPinSet) "Change veil PIN" else "Set veil PIN") },
                    text = {
                        Column {
                            if (veilPinSet) {
                                OutlinedTextField(
                                    value = veilPinOld,
                                    onValueChange = { veilPinOld = it.filter { c -> c.isDigit() }.take(8) },
                                    label = { Text("Current PIN") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = veilPin1,
                                onValueChange = { veilPin1 = it.filter { c -> c.isDigit() }.take(8) },
                                label = { Text("New PIN (4–8 digits)") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = veilPin2,
                                onValueChange = { veilPin2 = it.filter { c -> c.isDigit() }.take(8) },
                                label = { Text("Confirm new PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            veilPinErr?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = Color(0xFFC62828), fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (veilPin1 != veilPin2) { veilPinErr = "PINs don't match"; return@Button }
                            val err = veil.setPin(context, veilPin1, veilPinOld.ifBlank { null })
                            if (err == null) {
                                showVeilPinDialog = false
                                if (!veilEnabled) onVeilEnabled(true)
                                Toast.makeText(context, "Veil PIN saved", Toast.LENGTH_SHORT).show()
                            } else veilPinErr = err
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showVeilPinDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        // NOTE: server URL + API token fields were removed from Settings — users
        // don't know what a "brain server" is. Values come baked from the build
        // (BuildConfig) with TokenStore override; support can still rotate via
        // TokenStore.set() without an app update.
        OnDeviceModelSection(fg = fg, stroke = stroke, bg = bg)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { com.frit.app.core.DataWipe.wipeAll(context) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C3A2F)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Delete my data", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun UserProfileSection(fg: Color, surf: Color, stroke: Color, bg: Color) {    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(com.frit.app.core.UserProfile.load()) }
    // Refresh every time the section opens (e.g. after auto-learn updated it mid-chat).
    LaunchedEffect(expanded) { if (expanded) profile = com.frit.app.core.UserProfile.load() }
    val callAs = profile.preferredName.ifBlank { profile.displayName }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Personal Details", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text(
                    if (callAs.isNotBlank()) "Saved for $callAs — tap to edit"
                    else "Tell FRIT about you — tap to fill",
                    color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat
                )
            }
            Text(if (expanded) "▲" else "▼", color = fg, fontSize = 14.sp)
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(8.dp))
        Text(
            "Used to personalise replies and AutoMode. Updates automatically when you say 'remember...' or 'my name is...'. Stored on this phone only.",
            color = fg.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(12.dp))
        ProfileField(fg = fg, stroke = stroke, label = "Full name", value = profile.displayName) { profile = profile.copy(displayName = it) }
        ProfileField(fg = fg, stroke = stroke, label = "What should FRIT call you?", value = profile.preferredName) { profile = profile.copy(preferredName = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Language (e.g. English)", value = profile.language) { profile = profile.copy(language = it) }
        ProfileField(fg = fg, stroke = stroke, label = "City", value = profile.city) { profile = profile.copy(city = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Country", value = profile.country) { profile = profile.copy(country = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Birthday", value = profile.birthday) { profile = profile.copy(birthday = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Occupation", value = profile.occupation) { profile = profile.copy(occupation = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Work schedule (e.g. 9am-5pm Mon-Fri)", value = profile.workSchedule) { profile = profile.copy(workSchedule = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Interests (comma separated)", value = profile.interests) { profile = profile.copy(interests = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Preferred tone (Friendly / Formal / Casual)", value = profile.tone) { profile = profile.copy(tone = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Reply length (Short / Detailed)", value = profile.verbosity) { profile = profile.copy(verbosity = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Trading risk (Conservative / Moderate / Aggressive)", value = profile.tradingRisk) { profile = profile.copy(tradingRisk = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Emergency contact (name + number)", value = profile.emergencyContact) { profile = profile.copy(emergencyContact = it) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    com.frit.app.core.UserProfile.save(profile)
                    com.frit.app.core.UserProfile.setOnboarded(true)
                    Toast.makeText(context, "Personal details saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text("Save", color = bg, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = {
                    profile = com.frit.app.core.UserProfile.load()
                    Toast.makeText(context, "Changes discarded", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Reset", color = fg) }
        }
    }
}

@Composable
fun OnboardingDialog(fg: Color, surf: Color, stroke: Color, bg: Color, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    Dialog(onDismissRequest = {}) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(16.dp)).padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Welcome to FRIT 👋", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tell FRIT a little about you so it can personalise replies and act like you in AutoMode. You can change everything later in Settings → Personal Details.",
                color = fg.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(12.dp))
            ProfileField(fg = fg, stroke = stroke, label = "Your name", value = name) { name = it }
            ProfileField(fg = fg, stroke = stroke, label = "Language (e.g. English)", value = language) { language = it }
            ProfileField(fg = fg, stroke = stroke, label = "City", value = city) { city = it }
            ProfileField(fg = fg, stroke = stroke, label = "Occupation", value = occupation) { occupation = it }
            ProfileField(fg = fg, stroke = stroke, label = "Interests", value = interests) { interests = it }
            ProfileField(fg = fg, stroke = stroke, label = "Preferred tone (Friendly / Formal / Casual)", value = tone) { tone = it }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    com.frit.app.core.UserProfile.save(
                        com.frit.app.core.UserProfile.load().copy(
                            displayName = name.trim(),
                            preferredName = name.trim(),
                            language = language.trim(),
                            city = city.trim(),
                            occupation = occupation.trim(),
                            interests = interests.trim(),
                            tone = tone.trim()
                        )
                    )
                    com.frit.app.core.UserProfile.setOnboarded(true)
                    Toast.makeText(context, "Thanks — FRIT will remember this", Toast.LENGTH_SHORT).show()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text("Save & start", color = bg, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                com.frit.app.core.UserProfile.setOnboarded(true)
                onDone()
            }, modifier = Modifier.fillMaxWidth()) { Text("Skip for now", color = fg) }
        }
    }
}

@Composable
fun ProfileField(fg: Color, stroke: Color, label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = fg.copy(alpha = 0.6f), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
    )
}

@Composable
fun SettingItem(label: String, fg: Color, stroke: Color, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(label, color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = stroke)
    }
}

@Composable
fun SettingToggle(label: String, value: Boolean, onValueChange: (Boolean) -> Unit, fg: Color, stroke: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onValueChange)
    }
    HorizontalDivider(color = stroke)
}

// Collapsible dropdown section (e.g. Accessibilities) — tap header to reveal.
@Composable
fun ExpandableSection(
    title: String,
    subtitle: String,
    fg: Color,
    stroke: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text(subtitle, color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat)
            }
            Text(if (expanded) "▲" else "▼", color = fg, fontSize = 14.sp)
        }
        if (expanded) {
            content()
        }
    }
    HorizontalDivider(color = stroke)
}
