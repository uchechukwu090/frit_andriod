package com.frit.app.ui.creator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.frit.app.core.*
import com.frit.app.data.ChatMessage
import com.frit.app.data.NetworkClient
import com.frit.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.frit.app.ui.naira
import com.frit.app.ui.wallet.WalletButton
import com.frit.app.ui.wallet.WalletField
import android.widget.Toast
import kotlinx.coroutines.withContext

@Composable
fun CreatorScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit,
    onOwnerTap: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adminToken by remember { mutableStateOf(com.frit.app.core.AdminStore.get()) }
    var watchOn by remember { mutableStateOf(com.frit.app.core.AdminStore.creatorMode()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var payments by remember { mutableStateOf(listOf<org.json.JSONObject>()) }
    var withdrawals by remember { mutableStateOf(listOf<org.json.JSONObject>()) }
    var mintDays by remember { mutableStateOf("7") }
    var mintCount by remember { mutableStateOf("5") }
    var mintTier by remember { mutableStateOf("tester") }
    var mintedCodes by remember { mutableStateOf("") }
    var modelRows by remember { mutableStateOf(listOf<org.json.JSONObject>()) }
    var modelEdits by remember { mutableStateOf(mapOf<String, String>()) }
    var modelsLoaded by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            busy = true
            try {
                val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.data.NetworkClient.adminInbox()
                })
                if (o.optString("status") == "error") { notice = o.optString("message", "Error"); }
                else {
                    val ps = mutableListOf<org.json.JSONObject>()
                    val arr = o.optJSONArray("payments")
                    for (i in 0 until (arr?.length() ?: 0)) ps.add(arr.getJSONObject(i))
                    payments = ps
                    val ws = mutableListOf<org.json.JSONObject>()
                    val warr = o.optJSONArray("withdrawals")
                    for (i in 0 until (warr?.length() ?: 0)) ws.add(warr.getJSONObject(i))
                    withdrawals = ws
                    notice = if (ps.isEmpty() && ws.isEmpty()) "Inbox clear — nothing waiting." else ""
                }
            } catch (e: Exception) { notice = "Network error — check connection." }
            busy = false
        }
    }

    LaunchedEffect(Unit) { if (adminToken.isNotBlank()) load() }

    fun nairaL(kobo: Long): String = "₦${"%,.0f".format(kobo / 100.0)}"

    Column(Modifier.fillMaxSize().background(FritCream).padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Creator console", color = FritEspresso, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = FritEspresso) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Owner only. The admin token approves payments and pays withdrawals — never share it, never screenshot it.",
            color = FritEspresso.copy(alpha = 0.7f), fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(12.dp))
        WalletField(value = adminToken, onChange = { adminToken = it }, label = "Admin token (paste once)", fg = FritEspresso, bg = FritCream, stroke = FritStroke)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (watchOn) FritEspresso else FritCream)
                    .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable {
                        com.frit.app.core.AdminStore.set(adminToken)
                        com.frit.app.core.AdminStore.setCreatorWatch(!watchOn)
                        watchOn = !watchOn
                        com.frit.app.core.CreatorWatcher.sync(context)
                        Toast.makeText(context, if (!watchOn) "Watching for payments" else "Watch off", Toast.LENGTH_SHORT).show()
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text(if (watchOn) "Watch ON" else "Watch OFF", color = if (watchOn) FritCream else FritEspresso, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(FritCream)
                    .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable {
                        com.frit.app.core.AdminStore.set(adminToken)
                        com.frit.app.core.CreatorWatcher.sync(context)
                        load()
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Save + refresh", color = FritEspresso, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritEspresso)
                .clickable { onOwnerTap() }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text("Endpoint console (owner tier)", color = FritCream, fontWeight = FontWeight.Bold) }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(notice, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("Payments waiting (${payments.size})", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        payments.forEach { p ->
            val pid = p.optString("id", "")
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritSurface)
                    .border(1.dp, FritEspresso.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Column {
                    Text(
                        "${nairaL(p.optLong("amount_kobo", 0))} · ${p.optString("tier", p.optString("purpose", ""))}",
                        color = FritEspresso, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat
                    )
                    Text("From ${p.optString("user_id", "?")} · ref ${p.optString("reference", "?")}", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    WalletButton("Approve", !busy, FritEspresso, FritCream) {
                        scope.launch {
                            busy = true
                            try {
                                withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.adminApprovePayment(pid) }
                                Toast.makeText(context, "Approved", Toast.LENGTH_SHORT).show()
                                load()
                            } catch (e: Exception) { notice = "Approve failed." }
                            busy = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Tester trial codes — full features, no payment", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(4.dp))
        Text("Mint codes, send one per tester. They open Wallet → \"Redeem a code\" and paste it — chat, images, video, voice, automation and trading all unlock until expiry. Re-pasting refreshes on their device.", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("tester", "creator", "pro").forEach { t ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (mintTier == t) FritEspresso else FritCream)
                        .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .clickable { mintTier = t }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(t.replaceFirstChar { it.uppercase() }, color = if (mintTier == t) FritCream else FritEspresso, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { WalletField(value = mintDays, onChange = { mintDays = it }, label = "Days each (1-365)", fg = FritEspresso, bg = FritCream, stroke = FritStroke, numbersOnly = true) }
            Box(Modifier.weight(1f)) { WalletField(value = mintCount, onChange = { mintCount = it }, label = "How many (1-100)", fg = FritEspresso, bg = FritCream, stroke = FritStroke, numbersOnly = true) }
        }
        Spacer(Modifier.height(8.dp))
        WalletButton("Mint codes", !busy, FritEspresso, FritCream) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.adminMint(mintTier, mintDays.toIntOrNull() ?: 7, mintCount.toIntOrNull() ?: 5)
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", "Mint failed.") }
                    else {
                        val arr = o.optJSONArray("codes")
                        val sb = StringBuilder()
                        for (i in 0 until (arr?.length() ?: 0)) sb.append(arr.getJSONObject(i).optString("code", "")).append("\n")
                        mintedCodes = sb.toString().trim()
                        notice = "Minted ${arr?.length() ?: 0} × $mintTier — copy from below, one per tester."
                    }
                } catch (e: Exception) { notice = "Network error — check connection." }
                busy = false
            }
        }
        if (mintedCodes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(mintedCodes, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        }
        Spacer(Modifier.height(8.dp))
        Text("Brain & media models — change live, no rebuild", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(4.dp))
        Text("New model on OpenRouter? Paste its slug (vendor/model-name) and Save — it takes effect instantly. Reset returns to the default. Verify slugs at openrouter.ai/models.", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        WalletButton(if (modelsLoaded) "Reload models" else "Load models", !busy, FritEspresso, FritCream) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.adminModels()
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", "Failed.") }
                    else {
                        val rows = mutableListOf<org.json.JSONObject>()
                        val arr = o.optJSONArray("models")
                        for (i in 0 until (arr?.length() ?: 0)) rows.add(arr.getJSONObject(i))
                        modelRows = rows
                        modelEdits = rows.associate { it.optString("key", "") to it.optString("value", "") }
                        modelsLoaded = true
                        notice = ""
                    }
                } catch (e: Exception) { notice = "Network error — check connection." }
                busy = false
            }
        }
        modelRows.forEach { m ->
            val k = m.optString("key", "")
            val label = m.optString("label", k)
            val hint = m.optString("hint", "")
            val src = m.optString("source", "")
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritSurface)
                    .border(1.dp, FritEspresso.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(10.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        Text(src.uppercase(), color = FritEspresso.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (hint.isNotBlank()) Text(hint, color = FritEspresso.copy(alpha = 0.6f), fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    WalletField(value = modelEdits[k] ?: "", onChange = { modelEdits = modelEdits + (k to it) }, label = k, fg = FritEspresso, bg = FritCream, stroke = FritStroke)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(FritEspresso)
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                com.frit.app.data.NetworkClient.adminSetModel(k, modelEdits[k] ?: "")
                                            })
                                            notice = if (o.optString("status") == "error" || o.has("error")) o.optString("error", o.optString("message", "Save failed."))
                                            else "$k live → ${o.optString("value", "")}"
                                        } catch (e: Exception) { notice = "Network error." }
                                        busy = false
                                    }
                                }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Save", color = FritCream, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(FritCream)
                                .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                com.frit.app.data.NetworkClient.adminSetModel(k, "")
                                            })
                                            val d = o.optString("value", "")
                                            modelEdits = modelEdits + (k to d)
                                            notice = "$k reset → $d"
                                        } catch (e: Exception) { notice = "Network error." }
                                        busy = false
                                    }
                                }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Reset", color = FritEspresso, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Withdrawals waiting (${withdrawals.size})", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        withdrawals.forEach { w ->
            val wid = w.optString("id", "")
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritSurface)
                    .border(1.dp, FritEspresso.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Column {
                    Text(
                        "${nairaL(w.optLong("amount_kobo", 0))} → ${w.optString("bank_name", "?")} ${w.optString("account_number", "?")}",
                        color = FritEspresso, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat
                    )
                    Text("${w.optString("account_name", "?")} · by ${w.optString("user_id", "?")}", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Send the money from YOUR bank app first, then tap Paid.", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(FritEspresso)
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.adminApproveWithdrawal(wid) }
                                            Toast.makeText(context, "Marked paid", Toast.LENGTH_SHORT).show()
                                            load()
                                        } catch (e: Exception) { notice = "Failed." }
                                        busy = false
                                    }
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Paid", color = FritCream, fontWeight = FontWeight.Bold) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(FritCream)
                                .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.adminDeclineWithdrawal(wid) }
                                            load()
                                        } catch (e: Exception) { notice = "Failed." }
                                        busy = false
                                    }
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Decline", color = FritEspresso, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun OwnerConsole(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("XAUUSD") }
    var postBody by remember { mutableStateOf("{}") }
    var catalog by remember { mutableStateOf(listOf<org.json.JSONObject>()) }

    fun call(title: String, fn: suspend () -> String) {
        scope.launch {
            busy = true
            resultTitle = title
            try {
                val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fn() }
                resultText = try {
                    org.json.JSONObject(raw).toString(1).take(3000)
                } catch (_: Exception) { raw.take(3000) }
            } catch (e: Exception) { resultText = "Error: ${e.message}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            val s = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.frit.app.data.NetworkClient.billingStatus()
            })
            unlocked = s.optString("tier") == "owner"
        } catch (_: Exception) { unlocked = false }
    }
    // Auto endpoint catalog: every server endpoint, fetched live. New server
    // endpoints appear here with NO app update.
    LaunchedEffect(unlocked) {
        if (unlocked != true) return@LaunchedEffect
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.ensureInstallKey() }
            val c = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.frit.app.data.NetworkClient.catalogEndpoints()
            })
            val arr = c.optJSONArray("endpoints")
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until (arr?.length() ?: 0)) list.add(arr.getJSONObject(i))
            catalog = list
        } catch (_: Exception) { catalog = emptyList() }
    }

    fun runCatalog(e: org.json.JSONObject) {
        val method = e.optString("method", "GET")
        var p = e.optString("path", "/")
        // :params in the path are filled from the Input box above.
        Regex(":([A-Za-z0-9_]+)").findAll(p).forEach { m ->
            if (input.isNotBlank()) p = p.replace(m.value, input)
        }
        val admin = e.optBoolean("admin", false)
        val body = if (e.optBoolean("needs_body", false)) postBody.ifBlank { "{}" } else "{}"
        call("$method $p") {
            if (admin) com.frit.app.data.NetworkClient.adminRaw(method, p, body)
            else com.frit.app.data.NetworkClient.appRaw(method, p, body)
        }
    }

    fun masked(t: String): String = if (t.length <= 10) "••••" else t.take(6) + "…" + t.takeLast(4)

    @Composable
    fun Group(title: String, content: @Composable () -> Unit) {
        Spacer(Modifier.height(14.dp))
        Text(title, color = FritEspresso, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        content()
    }

    @Composable
    fun EpBtn(label: String, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritSurface)
                .border(1.dp, FritEspresso.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .clickable(enabled = !busy, onClick = onClick).padding(12.dp)
        ) { Text(label, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat) }
    }

    Column(Modifier.fillMaxSize().background(FritCream).padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Endpoint console", color = FritEspresso, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = FritEspresso) }
        }
        Spacer(Modifier.height(8.dp))
        when (unlocked) {
            null -> Text("Checking owner tier…", color = FritEspresso, fontSize = 14.sp)
            false -> Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritEspresso).padding(18.dp)
            ) {
                Text(
                    "Locked — redeem your master code in Wallet → Redeem first. This console opens only for owner tier.",
                    color = FritCream, fontSize = 14.sp, fontFamily = Montserrat
                )
            }
            else -> {
                // Token card
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritEspresso).padding(16.dp)) {
                    Column {
                        Text("Call credentials on this phone", color = FritCream.copy(alpha = 0.65f), fontSize = 12.sp, fontFamily = Montserrat)
                        Spacer(Modifier.height(4.dp))
                        TokenRow("App token", com.frit.app.core.TokenStore.get())
                        TokenRow("Admin token", com.frit.app.core.AdminStore.get())
                    }
                }

                Group("System") {
                    EpBtn("GET /health") { call("health", { com.frit.app.data.NetworkClient.sysHealth() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /models/free-health") { call("free-health", { com.frit.app.data.NetworkClient.modelFreeHealth() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /systems/status") { call("systems", { com.frit.app.data.NetworkClient.getSystemsStatus() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /strategy/status") { call("strategy", { com.frit.app.data.NetworkClient.strategyStatus() }) }
                }
                Group("Market & positions") {
                    WalletField(value = input, onChange = { input = it.uppercase().filter { c -> c.isLetterOrDigit() } }, label = "Symbol (e.g. XAUUSD)", fg = FritEspresso, bg = FritCream, stroke = FritStroke)
                    Spacer(Modifier.height(6.dp))
                    EpBtn("POST /enhanced/analyze") { val s = input.ifBlank { "XAUUSD" }; call("analyze $s", { com.frit.app.data.NetworkClient.marketAnalyze(s) }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /positions") { call("positions", { com.frit.app.data.NetworkClient.positionsList() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /tools/search (query = input)") { val q = input.ifBlank { "XAUUSD" }; call("search", { com.frit.app.data.NetworkClient.webSearch(q) }) }
                }
                Group("Creative & media") {
                    EpBtn("GET /creative/jobs") { call("creative jobs", { com.frit.app.data.NetworkClient.creativeJobs() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /wallet/withdrawals") { call("my withdrawals", { com.frit.app.data.NetworkClient.walletWithdrawals() }) }
                }
                Group("Money (admin token)") {
                    EpBtn("GET /admin/inbox") { call("inbox", { com.frit.app.data.NetworkClient.adminInbox() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /admin/snapshots") { call("snapshots", { com.frit.app.data.NetworkClient.adminSnapshots() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /admin/ledger/verify") { call("ledger verify", { com.frit.app.data.NetworkClient.adminLedgerVerify() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("POST /admin/backup") { call("backup", { com.frit.app.data.NetworkClient.adminBackupNow() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /feedback (trial reports)") { call("feedback", { com.frit.app.data.NetworkClient.feedbackAll() }) }
                }
                Group("All endpoints — auto-updates (${catalog.size})") {
                    Text(
                        "Every endpoint on the server, fetched live — new ones appear here with no app update. :params in a path are filled from the Symbol/Input box; POST bodies use the JSON box below (admin endpoints need the admin token in Creator console).",
                        color = FritEspresso.copy(alpha = 0.65f), fontSize = 11.sp, fontFamily = Montserrat
                    )
                    Spacer(Modifier.height(6.dp))
                    WalletField(value = postBody, onChange = { postBody = it }, label = "POST body JSON, e.g. {\"symbol\":\"XAUUSD\"}", fg = FritEspresso, bg = FritCream, stroke = FritStroke)
                    Spacer(Modifier.height(6.dp))
                    if (catalog.isEmpty()) {
                        Text("Catalog unavailable offline — curated buttons above still work.", color = FritEspresso.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat)
                    } else {
                        var lastGroup = ""
                        catalog.forEach { e ->
                            val g = e.optString("group", "Other")
                            if (g != lastGroup) {
                                if (lastGroup.isNotEmpty()) Spacer(Modifier.height(6.dp))
                                Text(g, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                                Spacer(Modifier.height(4.dp))
                                lastGroup = g
                            }
                            val tag = if (e.optBoolean("admin", false)) " · admin" else ""
                            EpBtn("${e.optString("method", "GET")} ${e.optString("path", "")}$tag") { runCatalog(e) }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                if (resultTitle.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(resultTitle, color = FritEspresso, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritEspresso).padding(12.dp)
                    ) {
                        Text(
                            resultText.ifBlank { "(empty)" },
                            color = FritCream, fontSize = 11.sp, fontFamily = Montserrat
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TokenRow(label: String, token: String) {
    val clipboard = LocalClipboardManager.current
    val shown = if (token.length <= 10) "••••" else token.take(6) + "…" + token.takeLast(4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = FritCream.copy(alpha = 0.6f), fontSize = 11.sp)
            Text(if (token.isBlank()) "not set" else shown, color = FritCream, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { if (token.isNotBlank()) clipboard.setText(AnnotatedString(token)) },
            border = BorderStroke(1.dp, FritCream.copy(alpha = 0.5f))
        ) { Text("Copy", color = FritCream, fontSize = 12.sp) }
    }
}
