package com.frit.app.features.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import com.frit.app.core.LocalMacroEngine
import com.frit.app.features.market.FritAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AutomationEngine {
    private var baseUrl: String = com.frit.app.core.FritConfig.BASE_URL
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        com.frit.app.core.NetworkSwitch.init(appContext!!)
    }
    fun getAppContext(): Context? = appContext
    fun setBaseUrl(url: String) { baseUrl = url.trimEnd('/') }
    fun status(): String = "Automation Active"

    suspend fun execute(task: String, context: String = "", screenText: String = ""): String = withContext(Dispatchers.IO) {
        android.util.Log.i("AutomationEngine", "Executing: $task")
        com.frit.app.features.veil.VeilManager.taskStarted()
        try {
        if (com.frit.app.features.veil.VeilManager.areTasksPaused())
            return@withContext "Tasks are paused — owner verification needed on the veil screen."

        // 1. High-confidence on-device pattern matching (fast path, zero RAM).
        val local = try { tryLocalActionSuspend(task, automated = true) } catch (e: Exception) { null }
        if (local != null) return@withContext local

        // 1.5. On-device LLM Agent (medium path, handles complexity without cloud).
        // DEPRECATED hijacking: Local brain is chat-only, it shouldn't stop
        // automation tasks that might need tools. Let it fall through to chat().
        /*
        val localAgent = try {
            com.frit.app.core.LocalInferenceEngine.processLocalOnly(task)
        } catch (e: Exception) { null }
        if (localAgent != null) return@withContext localAgent
        */

        // 2. Real multi-step tasks go straight to the CLOUD agent (n8n-style:
        //    the server brain plans steps, the phone executes tools and reports
        //    back). The on-device Gemma model is chat-only — it can't reliably
        //    tool-call, and running its agent loop here risked OOM crashes.
        com.frit.app.core.NetworkSwitch.ensureBestEffortConnectivity()
        val out = try {
            com.frit.app.core.IntegrationCoordinator.chat(task, emptyList())
        } catch (e: Exception) {
            android.util.Log.e("AutomationEngine", "Execution error", e)
            "Error: ${e.message}"
        } finally {
            com.frit.app.core.NetworkSwitch.restore()
        }
        out
        } finally {
            com.frit.app.features.veil.VeilManager.taskFinished()
        }
    }

    /**
     * Local-only entry point. Never falls through to the cloud (avoids a cloud -> local -> cloud loop).
     *
     * `automated = true` means the caller is NOT the user's live utterance
     * (a brain tool, a background task). Calls then go through the
     * confirmation gate (F-18a) instead of being placed outright.
     */
    suspend fun executeLocalOnly(task: String, automated: Boolean = true): String? = withContext(Dispatchers.IO) {
        try { tryLocalActionSuspend(task, automated) } catch (e: Exception) { null }
    }

    fun returnToFrit() {
        val ctx = appContext ?: com.frit.app.features.market.FritAccessibilityService.instance ?: return
        try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            if (intent != null) {
                try {
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    com.frit.app.features.market.FritAccessibilityService.instance?.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AutomationEngine", "returnToFrit failed", e)
        }
    }

    fun installedAppLabels(): List<String> {
        val ctx = appContext ?: com.frit.app.features.market.FritAccessibilityService.instance ?: return emptyList()
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(q, 0).map { it.loadLabel(pm).toString().trim() }.distinct().sorted()
        } catch (e: Exception) { emptyList() }
    }

    fun openAppReport(name: String): Map<String, Any> {
        if (launchApp(name)) return mapOf("ok" to true, "opened" to name)
        val near = installedAppLabels().filter { it.contains(name, true) || name.contains(it, true) }.take(5)
        return mapOf("ok" to false, "error" to "App '$name' not found", "suggestions" to near)
    }

    private suspend fun tryLocalActionSuspend(task: String, automated: Boolean = true): String? {
        val t = task.lowercase().trim()

        if (t.startsWith("run macro ") || t.startsWith("execute macro ")) {
            val name = t.substringAfter("macro ").trim()
            val success = LocalMacroEngine.execute(name)
            return if (success) "Macro '$name' executed successfully." else "Failed to execute macro '$name'."
        }

        return tryLocalAction(task, automated)
    }

    private fun tryLocalAction(task: String, automated: Boolean = true): String? {
        val t = task.lowercase().trim()
        val ctx = appContext ?: return null
        
        // Safety check for critical OS bindings
        if (ctx.applicationContext == null && FritAccessibilityService.instance == null) return "Automation engine OS binding lost"

        // --- Repetitive Task Scheduling ---
        AutomationScheduler.parseRecurringTask(t)?.let { parsed ->
            AutomationScheduler.scheduleTask(parsed.task, parsed.task, parsed.intervalSeconds)
            return "Scheduled repetitive task: '${parsed.task}' every ${parsed.intervalSeconds}s"
        }

        if (t.startsWith("check mt5") || t.startsWith("mt5 status") || t == "positions") {
            val currentApp = try { FritAccessibilityService.getCurrentApp() } catch (e: Exception) { "" }
            if (!currentApp.contains("metatrader5")) {
                launchApp("mt5")
            }
            val positions = try { FritAccessibilityService.getMT5Positions() } catch (e: Exception) { emptyList() }
            if (positions.isEmpty()) {
                return "No open positions found on the MT5 screen right now. Auto-launched MT5 for you, please wait a moment for it to load and try again."
            }
            return "MT5 Status: " + positions.joinToString("\n") { 
                "${it.type} ${it.symbol} ${it.volume} @ ${it.openPrice} | P/L: ${it.profit}" 
            }
        }

        if (t == "stop all tasks" || t == "clear automation" || t == "cancel all tasks") {
            AutomationScheduler.listActiveTasks().forEach { AutomationScheduler.cancelTask(it) }
            return "All repetitive tasks cancelled."
        }

        if (t.startsWith("call ") || t.startsWith("ring ") || t.startsWith("dial ") || t.startsWith("phone ")) {
            Regex("^(?:call|ring|dial|phone)\\s+(.+)").find(t)?.let { m ->
                val target = m.groupValues[1].trim()
                val number = resolveContact(target) ?: target.filter { it.isDigit() || it == '+' }
                if (number.isNotEmpty()) {
                    // F-18a: an automated caller (background task, brain tool,
                    // LLM tool loop) has no user utterance to justify the call,
                    // so it opens the DIALER for the user to confirm instead of
                    // placing the call outright.
                    val decided = if (automated) {
                        com.frit.app.core.CallGuard.Decision.CONFIRM_FIRST
                    } else {
                        com.frit.app.core.CallGuard.decide(
                            task, target, callsAllowed()
                        )
                    }
                    when (decided) {
                        com.frit.app.core.CallGuard.Decision.DENY ->
                            return "Calls are disabled on this device — enable them in Settings if you want FRIT to dial."
                        com.frit.app.core.CallGuard.Decision.PLACE_NOW -> {
                            makeCall(number, confirmed = true)
                            return "Calling $target..."
                        }
                        else -> {
                            makeCall(number, confirmed = false)
                            return "Opening the dialer for $target — tap call to place it."
                        }
                    }
                }
            }
        }

        if (t.startsWith("whatsapp ") || t.startsWith("message on whatsapp ")) {
            Regex("^(?:message on whatsapp|whatsapp message|whatsapp)\\s+(\\S+)(?:\\s+(.+))?").find(t)?.let { m ->
                val name = m.groupValues[1].trim()
                val msg = m.groupValues[2].trim()
                val number = resolveContact(name) ?: name
                openWhatsApp(number, msg)
                return "Opening WhatsApp for $name..."
            }
        }

        if (t.startsWith("text ") || t.startsWith("sms ") || t.startsWith("message ")) {
            Regex("^(?:text|sms|message)\\s+(\\S+)(?:\\s+(.+))?").find(t)?.let { m ->
                val number = resolveContact(m.groupValues[1]) ?: m.groupValues[1]
                openSmsCompose(number, m.groupValues[2])
                return "Opening SMS..."
            }
        }

        if (t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start ")) {
            Regex("^(?:open|launch|start)\\s+(.+)").find(t)?.let { m ->
                val appTarget = m.groupValues[1].trim()
                if (appTarget == "wifi" || appTarget == "wi-fi") { openSettings("wifi"); return "Opening Wi-Fi settings" }
                if (appTarget == "bluetooth") { openSettings("bluetooth"); return "Opening Bluetooth settings" }
                if (appTarget == "settings" || appTarget == "setting") { openSettings("settings"); return "Opening settings" }
                
                if (launchApp(appTarget)) return "Opening $appTarget..."
            }
        }

        if (t.startsWith("set alarm ") || t.startsWith("wake me ")) {
            Regex("^(?:set alarm|wake me|alarm)\\s+(.+)").find(t)?.let { m ->
                setAlarm("FRIT Alarm", m.groupValues[1])
                return "Alarm set for ${m.groupValues[1]}"
            }
        }

        if (t.startsWith("set timer ") || t.startsWith("timer ")) {
            Regex("^(?:set timer|timer|remind me in)\\s+(.+)").find(t)?.let { m ->
                setTimer(m.groupValues[1])
                return "Timer started"
            }
        }

        if (t.startsWith("play ") || t.startsWith("listen to ")) {
            Regex("^(?:play|listen to|put on)\\s+(.+)").find(t)?.let { m ->
                playMusic(m.groupValues[1])
                return "Playing ${m.groupValues[1]}"
            }
        }

        if (t.startsWith("navigate ") || t.startsWith("directions ") || t.startsWith("take me ") || t.startsWith("go to ")) {
            Regex("^(?:navigate|directions|take me|go to)\\s+to\\s+(.+)").find(t)?.let { m ->
                navigateTo(m.groupValues[1])
                return "Navigating to ${m.groupValues[1]}"
            }
        }

        // Direct System Settings shortcuts (guarded against message text hijacking)
        if (isSettingIntent(t, "bluetooth")) { openSettings("bluetooth"); return "Opened Bluetooth settings" }
        if (isSettingIntent(t, "wifi") || isSettingIntent(t, "wi-fi")) { openSettings("wifi"); return "Opened Wi-Fi settings" }
        if (isSettingIntent(t, "mobile data") || isSettingIntent(t, "cellular") || isSettingIntent(t, "data")) { openSettings("mobile_data"); return "Opened Mobile Data settings" }
        if (isSettingIntent(t, "airplane") || isSettingIntent(t, "flight mode")) { openSettings("airplane"); return "Opened Airplane mode settings" }
        if (isSettingIntent(t, "location") || isSettingIntent(t, "gps")) { openSettings("location"); return "Opened Location settings" }
        if (isSettingIntent(t, "battery") || isSettingIntent(t, "power saver")) { openSettings("battery"); return "Opened Battery settings" }

        return null
    }

    private fun isSettingIntent(t: String, keyword: String): Boolean {
        if (!t.contains(keyword)) return false
        return t.matches(Regex("^(?:turn|switch|toggle|open|enable|disable|adjust|show)?\\s*.*\\b" + Regex.escape(keyword) + "\\b.*"))
    }

    // F-18b: exact display-name match wins; otherwise a single partial match.
    // Several different people matching (two "John"s) returns null so the caller
    // does not dial/message the first row by accident. Numbers are de-duplicated
    // by their last 9 digits so +234... and 0... formats of one contact count once.
    private fun resolveContact(name: String): String? {
        val ctx = appContext ?: return null
        val target = name.trim()
        if (target.isEmpty()) return null
        return try {
            val exact = LinkedHashMap<String, String>()
            val partial = LinkedHashMap<String, String>()
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$target%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val display = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.trim().orEmpty()
                    val key = number.filter { it.isDigit() }.takeLast(9)
                    if (key.isEmpty()) continue
                    val bucket = if (display.equals(target, ignoreCase = true)) exact else partial
                    bucket.putIfAbsent(key, number)
                }
            }
            when {
                exact.size == 1 -> exact.values.first()
                exact.size > 1 -> null
                partial.size == 1 -> partial.values.first()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveContactPublic(name: String): String? = resolveContact(name)
    fun launchAppPublic(name: String): Boolean = launchApp(name)

    /** Device kill switch (frit_prefs `allow_calls`), shared with SensitiveGate. */
    fun callsAllowed(): Boolean {
        val ctx = appContext ?: return true
        return try {
            ctx.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                .getBoolean("allow_calls", true)
        } catch (e: Exception) {
            com.frit.app.core.Diag.warn("AutomationEngine", "callsAllowed", e)
            true
        }
    }

    /**
     * Place an outgoing call.
     *
     * F-18a: `Intent.ACTION_CALL` places the call IMMEDIATELY — no dialer, no
     * user gesture. Anything that can reach this without the user having
     * asked for it in that utterance (a background task, a brain tool, the
     * on-device LLM's tool loop) could therefore dial arbitrary numbers.
     *
     * `confirmed = false` opens the DIALER with the number pre-filled
     * (`Intent.ACTION_DIAL`) instead: the user sees the number and taps the
     * call button. That is the confirmation gate — the call is not placed by
     * FRIT.
     */
    fun makeCall(number: String, confirmed: Boolean = true): String {
        val ctx = appContext ?: return "Cannot place call — app not initialised"
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return "Cannot place call — no usable number"
        val action = if (confirmed) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            ctx.startActivity(
                Intent(action, Uri.parse("tel:$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            if (confirmed) "Called $digits" else "Opened the dialer for $digits — tap call to place it"
        } catch (e: Exception) {
            com.frit.app.core.Diag.warn("AutomationEngine", "makeCall", e)
            "Could not open the dialer: ${e.message}"
        }
    }

    fun openWhatsApp(numberOrName: String, message: String = "") {
        val ctx = appContext ?: return
        val number = numberOrName.filter { it.isDigit() || it == '+' }
        val uri = if (number.isNotEmpty()) {
            Uri.parse("https://wa.me/$number?text=${Uri.encode(message)}")
        } else {
            Uri.parse("https://wa.me/?text=${Uri.encode(message)}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openSmsCompose(number: String, message: String = "") {
        appContext?.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openUrl(url: String) {
        val ctx = appContext ?: return
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openCamera(frontCamera: Boolean = false) {
        val ctx = appContext ?: return
        val i = Intent("android.media.action.IMAGE_CAPTURE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra("android.intent.extras.CAMERA_FACING", if (frontCamera) 1 else 0)
        ctx.startActivity(i)
    }

    fun setAlarm(label: String, timeStr: String) {
        val ctx = appContext ?: return
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            val match = Regex("(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", RegexOption.IGNORE_CASE).find(timeStr)
            if (match != null) {
                var hour = match.groupValues[1].toInt()
                val min = match.groupValues[2].toInt()
                val ampm = match.groupValues[3].uppercase()
                if (ampm == "PM" && hour < 12) hour += 12
                if (ampm == "AM" && hour == 12) hour = 0
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, min)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun setTimer(duration: String) {
        val ctx = appContext ?: return
        val seconds = parseToSeconds(duration)
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "FRIT Timer")
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun parseToSeconds(duration: String): Int {
        var total = 0
        Regex("(\\d+)\\s*hour").find(duration)?.let { total += it.groupValues[1].toInt() * 3600 }
        Regex("(\\d+)\\s*min").find(duration)?.let { total += it.groupValues[1].toInt() * 60 }
        Regex("(\\d+)\\s*sec").find(duration)?.let { total += it.groupValues[1].toInt() }
        return if (total == 0) 300 else total
    }

    fun playMusic(query: String) {
        val ctx = appContext ?: return
        val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(spotifyIntent)
        } catch (_: Exception) {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun navigateTo(destination: String) {
        appContext?.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openSettings(section: String) {
        val ctx = appContext ?: return
        val action = when (section.lowercase()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "mobile_data", "cellular", "data", "network", "data_usage" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "airplane", "flight" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "notifications", "notification" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "internal_storage", "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openAppSettings(appName: String) {
        val ctx = appContext ?: return
        val pm = ctx.packageManager
        val pkg = resolvePackage(appName) ?: return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun setVolume(level: Int) {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val safeLevel = (level.coerceIn(0, 100) * max) / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, safeLevel, 0)
    }

    fun setClipboardText(value: String) {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("FRIT", value))
    }

    fun toggleWifi(enabled: Boolean) {
        openSettings("wifi")
    }

    fun toggleBluetooth(enabled: Boolean) {
        openSettings("bluetooth")
    }

    fun toggleMobileData() {
        // Open Mobile Network Settings or Quick Settings so user/accessibility can toggle data
        openSettings("mobile_data")
    }

    private fun launchApp(appName: String): Boolean {
        val ctx = appContext ?: return false
        val pm = ctx.packageManager
        val pkg = resolvePackage(appName) ?: return false
        return try {
            val i = pm.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun resolvePackage(appName: String): String? {
        val ctx = appContext ?: return null
        val pm = ctx.packageManager
        val cleanName = appName.trim()
        val lower = cleanName.lowercase()
        val stripped = lower.replace("[^a-z0-9]".toRegex(), "")

        // 1. Direct package check (if user passed com.whatsapp or similar exact ID)
        try {
            val ai = pm.getApplicationInfo(cleanName, 0)
            if (pm.getLaunchIntentForPackage(ai.packageName) != null) {
                return ai.packageName
            }
        } catch (_: Exception) {}

        val known = mapOf(
            // Trading
            "mt5" to "net.metaquotes.metatrader5",
            "metatrader" to "net.metaquotes.metatrader5",
            "metatrader 5" to "net.metaquotes.metatrader5",
            "metatrader5" to "net.metaquotes.metatrader5",
            "tradingview" to "com.tradingview.tradingviewapp",
            "binance" to "com.binance.dev",
            // Messaging
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "whatsbusiness" to "com.whatsapp.w4b",
            "wa business" to "com.whatsapp.w4b",
            "whatsapp biz" to "com.whatsapp.w4b",
            "telegram" to "org.telegram.messenger",
            "signal" to "org.thoughtcrime.securesms",
            "messenger" to "com.facebook.orca",
            "discord" to "com.discord",
            // Social
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "x app" to "com.twitter.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "snapchat" to "com.snapchat.android",
            "linkedin" to "com.linkedin.android",
            "reddit" to "com.reddit.frontpage",
            "pinterest" to "com.pinterest",
            // Google
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "calendar" to "com.google.android.calendar",
            "photos" to "com.google.android.apps.photos",
            "google photos" to "com.google.android.apps.photos",
            "drive" to "com.google.android.apps.docs",
            "google drive" to "com.google.android.apps.docs",
            "meet" to "com.google.android.apps.meetings",
            "google meet" to "com.google.android.apps.meetings",
            "docs" to "com.google.android.apps.docs",
            // Entertainment
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "youtube music" to "com.google.android.apps.youtube.music",
            "amazon" to "com.amazon.mShop.android.shopping",
            "amazon music" to "com.amazon.mp3",
            "prime video" to "com.amazon.avod.thirdpartyclient",
            "amazon prime" to "com.amazon.avod.thirdpartyclient",
            "twitch" to "tv.twitch.android.app",
            // System / Utility
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "clock" to "com.android.deskclock",
            "camera" to "com.android.camera2",
            "calculator" to "com.android.calculator2",
            "files" to "com.google.android.apps.nbu.files",
            "file manager" to "com.google.android.apps.nbu.files",
            // Banking / Finance (common)
            "paypal" to "com.paypal.android.p2pmobile",
            "cash app" to "com.squareup.cash",
        )

        // 2. Try known map first (fastest path)
        known.entries.firstOrNull { lower.contains(it.key) || stripped == it.key.replace(" ", "") }?.value?.let { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }

        // 3. Dynamic universal scan across ALL installed applications on the device
        return try {
            val installed = pm.getInstalledApplications(0)
            val launchable = installed.mapNotNull { ai ->
                val launchIntent = pm.getLaunchIntentForPackage(ai.packageName)
                if (launchIntent != null) {
                    val label = runCatching { pm.getApplicationLabel(ai).toString().trim() }.getOrDefault("")
                    val labelLower = label.lowercase()
                    val labelStripped = labelLower.replace("[^a-z0-9]".toRegex(), "")
                    Triple(ai.packageName, labelLower, labelStripped)
                } else null
            }

            // Priority 1: Exact label or stripped match (e.g. "chatgpt" matches "ChatGPT")
            launchable.firstOrNull { it.second == lower || it.third == stripped }?.first
                // Priority 2: Label contains query or query contains label
                ?: launchable.firstOrNull { it.second.contains(lower) || lower.contains(it.second) }?.first
                // Priority 3: Stripped label substring
                ?: (if (stripped.length >= 3) launchable.firstOrNull { it.third.contains(stripped) || stripped.contains(it.third) }?.first else null)
                // Priority 4: Package name contains query (e.g. "deriv" matches "com.deriv.app")
                ?: launchable.firstOrNull { it.first.lowercase().contains(lower) }?.first
        } catch (e: Exception) {
            android.util.Log.e("AutomationEngine", "Dynamic package scan failed", e)
            null
        }
    }
}