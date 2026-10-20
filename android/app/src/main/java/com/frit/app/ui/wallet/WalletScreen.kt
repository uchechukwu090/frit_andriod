package com.frit.app.ui.wallet

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
import android.widget.Toast
import kotlinx.coroutines.withContext

@Composable
private fun SelectBox(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    fg: Color,
    bg: Color,
    stroke: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) fg else bg)
            .border(1.dp, if (selected) fg else stroke, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (selected) bg else fg, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat, modifier = Modifier.weight(1f))
                if (selected) Text("●", color = bg, fontSize = 10.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = if (selected) bg.copy(alpha = 0.75f) else fg.copy(alpha = 0.65f), fontSize = 10.sp, fontFamily = Montserrat)
        }
    }
}

@Composable
internal fun WalletField(
    value: String, onChange: (String) -> Unit, label: String,
    fg: Color, bg: Color, stroke: Color,
    numbersOnly: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (numbersOnly) it.filter { c -> c.isDigit() } else it) },
        label = { Text(label, color = fg.copy(alpha = 0.55f), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = fg, unfocusedTextColor = fg,
            focusedBorderColor = fg, unfocusedBorderColor = stroke,
            focusedContainerColor = bg, unfocusedContainerColor = bg
        )
    )
}

@Composable
internal fun WalletButton(text: String, enabled: Boolean, fg: Color, bg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = fg, disabledContainerColor = fg.copy(alpha = 0.4f))
    ) { Text(text, color = bg, fontWeight = FontWeight.Bold, fontFamily = Montserrat) }
}

@Composable
private fun PasscodeCard(code: String, caption: String, fg: Color, bg: Color) {
    if (code.isBlank()) return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(fg).padding(10.dp)
    ) {
        Column {
            Text(caption, color = bg.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = Montserrat)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(code, color = bg, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                    border = BorderStroke(1.dp, bg.copy(alpha = 0.5f))
                ) { Text("Copy", color = bg, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
fun WalletScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit,
    onCreatorTap: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var tierSel by remember { mutableStateOf("creator") }
    var methodSel by remember { mutableStateOf("bank") } // bank | usdt | btc
    var txNet by remember { mutableStateOf("usdt") } // usdt | btc
    var topupNaira by remember { mutableStateOf("") }
    var creditTopupNaira by remember { mutableStateOf("") }
    var payId by remember { mutableStateOf("") }
    var orderPass by remember { mutableStateOf("") }
    var orderInfo by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var txid by remember { mutableStateOf("") }
    var paidResult by remember { mutableStateOf("") }
    var redeemCode by remember { mutableStateOf("") }

    // Parsed account state (never raw JSON on screen).
    var tier by remember { mutableStateOf("free") }
    var expiry by remember { mutableStateOf("—") }
    var useToday by remember { mutableStateOf("") }
    var useMonth by remember { mutableStateOf("") }
    var balanceKobo by remember { mutableStateOf(0L) }
    var credits by remember { mutableStateOf(0) }
    var creditsMo by remember { mutableStateOf(0) }
    var planFeatures by remember { mutableStateOf(listOf<String>()) }
    var starterPrice by remember { mutableStateOf("") }
    var creatorPrice by remember { mutableStateOf("") }
    var traderPrice by remember { mutableStateOf("") }
    var proPrice by remember { mutableStateOf("") }
    var starterFeat by remember { mutableStateOf("") }
    var creatorFeat by remember { mutableStateOf("") }
    var traderFeat by remember { mutableStateOf("") }
    var proFeat by remember { mutableStateOf("") }
    var bankLine by remember { mutableStateOf("") }
    var bscAddress by remember { mutableStateOf("") }

    var fundOpen by remember { mutableStateOf(false) }
    var wdOpen by remember { mutableStateOf(false) }
    var wdBank by remember { mutableStateOf("") }
    var wdNumber by remember { mutableStateOf("") }
    var wdName by remember { mutableStateOf("") }
    var wdNaira by remember { mutableStateOf("") }
    var withdrawals by remember { mutableStateOf(listOf<Triple<String, String, String>>()) }
    var linked by remember { mutableStateOf(true) }
    var fbMsg by remember { mutableStateOf("") }
    var fbRating by remember { mutableStateOf(5) }

    fun netErr(): String = "Network error — check connection and try again."

    // Each section fetches independently: one failing call must never blank
    // the rest of the screen (that failure mode showed ₦0 prices + 0 credits).
    fun refresh() {
        scope.launch {
            busy = true
            var failures = 0
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.ensureInstallKey() }
            } catch (_: Exception) { failures++ }
            try {
                val s = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.billingStatus() })
                if (s.optString("status") == "error") { failures++ }
                else {
                    tier = s.optString("tier", "free")
                    expiry = s.optString("expires_at", "—").take(10)
                    credits = s.optInt("credits", 0)
                    creditsMo = s.optInt("credits_mo", 0)
                    linked = s.optBoolean("linked", true)
                    planFeatures = s.optJSONArray("features")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    val t = s.optJSONObject("usage_today")
                    val m = s.optJSONObject("usage_month")
                    useToday = "Turns ${t?.optInt("turns", 0) ?: 0}"
                    useMonth = "Turns ${m?.optInt("turns", 0) ?: 0} · Clips ${m?.optInt("clips", 0) ?: 0} · Images ${m?.optInt("images", 0) ?: 0}"
                }
            } catch (_: Exception) { failures++ }
            try {
                val b = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.walletBalance() })
                if (b.optString("status") == "error") failures++ else balanceKobo = b.optLong("balance_kobo", 0)
            } catch (_: Exception) { failures++ }
            try {
                val p = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.billingPayInfo() })
                if (p.optString("status") == "error") { failures++ }
                else {
                    val pt = p.optJSONObject("tiers")
                    // Server sends kobo (x100); fall back to legacy naira x100 for old servers.
                    fun priceKobo(koboKey: String, nairaKey: String): String {
                        val k = pt?.optLong(koboKey, 0) ?: 0
                        val v = if (k > 0) k else (pt?.optLong(nairaKey, 0) ?: 0) * 100
                        return naira(v) + "/mo"
                    }
                    starterPrice = priceKobo("starter_kobo", "starter_naira")
                    creatorPrice = priceKobo("creator_kobo", "creator_naira")
                    traderPrice = priceKobo("trader_kobo", "trader_naira")
                    proPrice = priceKobo("pro_kobo", "pro_naira")
                    val bk = p.optJSONObject("bank")
                    bankLine = "${bk?.optString("bank_name", "")} · ${bk?.optString("account_number", "")} · ${bk?.optString("account_name", "")}"
                    bscAddress = p.optJSONObject("crypto")?.optString("bsc_address", "") ?: ""
                }
            } catch (_: Exception) { failures++ }
            try {
                val tiersResp = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.billingTiers() })
                if (tiersResp.optString("status") == "error") { failures++ }
                else {
                    val tiers = tiersResp.optJSONObject("tiers")
                    val labelMap = tiersResp.optJSONObject("feature_labels")?.let { lm ->
                        lm.keys().asSequence().associateWith { lm.optString(it, it) }
                    } ?: emptyMap()
                    fun feat(k: String): String {
                        val o = tiers?.optJSONObject(k) ?: return ""
                        val cr = o.optInt("credits_mo", 0)
                        val feats = o.optJSONArray("features")?.let { arr ->
                            (0 until arr.length()).map { labelMap[arr.getString(it)] ?: arr.getString(it) }
                        } ?: emptyList()
                        return "$cr credits/mo · ${feats.joinToString(", ")}"
                    }
                    starterFeat = feat("starter"); creatorFeat = feat("creator"); traderFeat = feat("trader"); proFeat = feat("pro")
                }
            } catch (_: Exception) { failures++ }
            try {
                val wds = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.walletWithdrawals() }).optJSONArray("withdrawals")
                val list = mutableListOf<Triple<String, String, String>>()
                for (i in 0 until (wds?.length() ?: 0)) {
                    val w = wds.getJSONObject(i)
                    list.add(Triple(w.optString("id", ""), naira(w.optLong("amount_kobo", 0)), w.optString("status", "")))
                }
                withdrawals = list
            } catch (_: Exception) { failures++ }
            notice = when {
                failures >= 4 -> netErr()
                !linked -> "Linking this device… connect to the internet and reopen Wallet to claim free credits."
                else -> ""
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wallet", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Status card ----
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fg).padding(12.dp)) {
            Column {
                Text("Current plan", color = bg.copy(alpha = 0.65f), fontSize = 10.sp, fontFamily = Montserrat)
                Text(tier.uppercase(), color = bg, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text("Active until $expiry", color = bg.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = Montserrat)
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = bg.copy(alpha = 0.2f))
                Spacer(Modifier.height(6.dp))
                Text("Credits: $credits / $creditsMo per month", color = bg, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text("Today · $useToday", color = bg, fontSize = 11.sp, fontFamily = Montserrat)
                Text("This month · $useMonth", color = bg.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = Montserrat)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Wallet balance card (tap to fund) ----
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(12.dp))
                .clickable { fundOpen = !fundOpen }.padding(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Spending wallet", color = fg.copy(alpha = 0.65f), fontSize = 10.sp, fontFamily = Montserrat)
                        Text(naira(balanceKobo), color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                    }
                    Text(if (fundOpen) "▲" else "▼", color = fg, fontSize = 12.sp)
                }
                if (fundOpen) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Money for the AI to spend FOR you — flight and order bookings, services. " +
                            "Fund it below; unspent balance stays yours and is withdrawable anytime.",
                        color = fg.copy(alpha = 0.75f), fontSize = 12.sp, fontFamily = Montserrat
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Pay to: $bankLine", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
                    if (bscAddress.isNotBlank()) Text("Crypto (BNB Smart Chain): $bscAddress", color = fg, fontSize = 11.sp, fontFamily = Montserrat)
                    Spacer(Modifier.height(6.dp))
                    WalletField(value = topupNaira, onChange = { topupNaira = it }, label = "Amount in naira (min ₦100)", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("bank" to "Bank", "usdt" to "USDT", "btc" to "BTC").forEach { (v, l) ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                    .background(if (methodSel == v) fg else bg)
                                    .border(1.dp, stroke, RoundedCornerShape(8.dp))
                                    .clickable { methodSel = v }.padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(l, color = if (methodSel == v) bg else fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    WalletButton("Fund wallet", !busy, fg = fg, bg = bg) {
                        val kobo = (topupNaira.toLongOrNull() ?: 0L) * 100L
                        if (kobo < 10000) { notice = "Minimum top-up is ₦100."; return@WalletButton }
                        scope.launch {
                            busy = true
                            try {
                                val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.frit.app.data.NetworkClient.walletTopup(kobo, if (methodSel == "bank") "bank" else "crypto")
                                })
                                payId = o.optString("payment_id", "")
                                orderPass = o.optString("passcode", "")
                                orderInfo = o.optString("instruction", "")
                                notice = ""
                            } catch (e: Exception) { notice = "Network error — check connection and try again." }
                            busy = false
                        }
                    }
                }
            }
        }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(notice, color = Color(0xFF9C3A2F), fontSize = 12.sp, fontFamily = Montserrat)
        }

        Spacer(Modifier.height(10.dp))
        Text("Choose your plan — yearly = 10x monthly (2 months free)", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        SelectBox("Automation", "$starterPrice\n$starterFeat", tierSel == "starter", { tierSel = "starter" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectBox("Content Creator", "$creatorPrice\n$creatorFeat", tierSel == "creator", { tierSel = "creator" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
            SelectBox("Trader", "$traderPrice\n$traderFeat", tierSel == "trader", { tierSel = "trader" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        SelectBox("Pro — everything", "$proPrice\n$proFeat", tierSel == "pro", { tierSel = "pro" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text("Free forever: 200 credits/mo · chat + market data. No card needed.", color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))

        // ---- Credit top-up (any amount) ----
        Text("Top up credits — any amount, ₦10 = 1 credit", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletField(value = creditTopupNaira, onChange = { creditTopupNaira = it }, label = "Amount in naira (min ₦100 = 10 credits)", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
        val previewCredits = (creditTopupNaira.toLongOrNull() ?: 0L) / 10L
        if (previewCredits > 0) Text("≈ $previewCredits credits (1 credit ≈ 1 chat · 4 credits ≈ 1 image · 65 credits ≈ 1 video clip)", color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletButton("Buy credits", !busy, fg = fg, bg = bg) {
            val kobo = (creditTopupNaira.toLongOrNull() ?: 0L) * 100L
            if (kobo < 10000) { notice = "Minimum credit top-up is ₦100."; return@WalletButton }
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingTopupCredits(kobo, if (methodSel == "bank") "bank" else "crypto")
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", o.optString("error", "Top-up failed.")) }
                    else {
                        payId = o.optString("payment_id", "")
                        orderPass = o.optString("passcode", "")
                        orderInfo = o.optString("instruction", "")
                        notice = ""
                    }
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Pay with", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectBox("Bank", "Transfer + narration", methodSel == "bank", { methodSel = "bank" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
            SelectBox("USDT", "BNB Smart Chain", methodSel == "usdt", { methodSel = "usdt" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
            SelectBox("BTC", "BNB Smart Chain", methodSel == "btc", { methodSel = "btc" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        WalletButton("Subscribe — $tierSel", !busy, fg = fg, bg = bg) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingSubscribe(tierSel, if (methodSel == "bank") "bank" else "crypto")
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", o.optString("error", "Subscribe failed.")) }
                    else {
                        payId = o.optString("payment_id", "")
                        orderPass = o.optString("passcode", "")
                        orderInfo = o.optString("instruction", "")
                        notice = ""
                    }
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        if (orderPass.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            PasscodeCard(orderPass, "Your payment passcode — use as transfer narration", fg = fg, bg = bg)
            Spacer(Modifier.height(6.dp))
            Text(orderInfo, color = fg.copy(alpha = 0.8f), fontSize = 12.sp, fontFamily = Montserrat)
        }

        Spacer(Modifier.height(10.dp))
        Text("I've paid", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletField(value = passcode, onChange = { passcode = it }, label = "Passcode from your order", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletField(value = sender, onChange = { sender = it }, label = "Sender name (bank only, optional)", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletField(value = txid, onChange = { txid = it }, label = "Crypto tx hash (bank payers skip)", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("usdt" to "USDT", "btc" to "BTC").forEach { (v, l) ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (txNet == v) fg else bg)
                        .border(1.dp, stroke, RoundedCornerShape(8.dp))
                        .clickable { txNet = v }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Text(l, color = if (txNet == v) bg else fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        WalletButton("Confirm payment", !busy, fg = fg, bg = bg) {
            if (payId.isBlank()) { notice = "Create a subscribe or top-up order first."; return@WalletButton }
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingSubmit(payId, passcode, sender, txid, txNet)
                    })
                    paidResult = if (o.optString("status") == "approved") {
                        val ac = o.optString("activation_code", "")
                        if (ac.isNotBlank()) "Approved! Activation code: $ac" else "Approved! Wallet credited."
                    } else {
                        o.optString("error", o.optString("note", "Queued for confirmation."))
                    }
                    passcode = ""
                    notice = ""
                    refresh()
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        if (paidResult.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(paidResult, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        }

        Spacer(Modifier.height(10.dp))
        Text("Redeem a code", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletField(value = redeemCode, onChange = { redeemCode = it }, label = "Activation, trial or master code", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletButton("Redeem", !busy, fg = fg, bg = bg) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingRedeem(redeemCode)
                    })
                    notice = if (o.optString("status") == "error" || o.has("error")) o.optString("error", o.optString("message", "Redeem failed."))
                    else if (o.optBoolean("refreshed", false)) "Unlocked ${o.optString("tier", "?")} until ${o.optString("expires_at", "?").take(10)}."
                    else "Unlocked ${o.optString("tier", "?")} until ${o.optString("expires_at", "?").take(10)}. (Re-pasting later only shows status — it never extends.)"
                    redeemCode = ""
                    refresh()
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        Text("Got a tester code? Paste it above — full features unlock instantly. Each code works once per subscription; re-pasting only shows status.", color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(12.dp))

        // ---- Withdraw ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Withdraw to my bank", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat, modifier = Modifier.weight(1f))
            Text(if (wdOpen) "▲" else "▼", color = fg, fontSize = 13.sp, modifier = Modifier.clickable { wdOpen = !wdOpen }.padding(8.dp))
        }
        if (wdOpen) {
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdBank, onChange = { wdBank = it }, label = "Your bank name", fg = fg, bg = bg, stroke = stroke)
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdNumber, onChange = { wdNumber = it }, label = "Your account number", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdName, onChange = { wdName = it }, label = "Account name", fg = fg, bg = bg, stroke = stroke)
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdNaira, onChange = { wdNaira = it }, label = "Amount in naira (min ₦500)", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
            Spacer(Modifier.height(6.dp))
            WalletButton("Request withdrawal", !busy, fg = fg, bg = bg) {
                val kobo = (wdNaira.toLongOrNull() ?: 0L) * 100L
                scope.launch {
                    busy = true
                    try {
                        val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.frit.app.data.NetworkClient.walletWithdraw(wdBank, wdNumber, wdName, kobo)
                        })
                        notice = if (o.has("error")) o.optString("error") else "Withdrawal requested — paid manually within 24h."
                        wdBank = ""; wdNumber = ""; wdName = ""; wdNaira = ""
                        refresh()
                    } catch (e: Exception) { notice = "Network error — check connection and try again." }
                    busy = false
                }
            }
        }
        if (withdrawals.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            withdrawals.forEach { (_, amt, st) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(amt, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(st.uppercase(), color = if (st == "paid") Color(0xFF4A7C59) else fg.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Trial feedback ----
        Text("Testing the app? Tell us", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { r ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (fbRating == r) fg else bg)
                        .border(1.dp, stroke, RoundedCornerShape(8.dp))
                        .clickable { fbRating = r }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Text("$r", color = if (fbRating == r) bg else fg, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        WalletField(value = fbMsg, onChange = { fbMsg = it }, label = "What worked, what broke?", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletButton("Send feedback", !busy, fg = fg, bg = bg) {
            scope.launch {
                busy = true
                try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.postFeedback(fbMsg, fbRating) }
                    notice = "Thanks — feedback received."
                    fbMsg = ""
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Creator console",
            color = fg.copy(alpha = 0.55f), fontSize = 11.sp, fontFamily = Montserrat,
            modifier = Modifier.clickable { onCreatorTap() }.padding(8.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}
