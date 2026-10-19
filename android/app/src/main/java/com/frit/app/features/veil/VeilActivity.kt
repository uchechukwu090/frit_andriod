package com.frit.app.features.veil

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The veil: FRIT's own lock screen shown over the system lock while tasks
 * are armed (see VeilManager). Back is blocked; Home/Recents are pinned via
 * screen-pinning when the user consents (graceful fallback if declined).
 *
 * Modes (EXTRA_MODE): LOCK = PIN pad (Phase 1); VERIFY = pickup check —
 * face challenge (blink + head-turn + match) with the PIN pad kept as
 * fallback, per the "password or face" re-entry design (Phase 2).
 */
class VeilActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "veil_mode"
        const val MODE_LOCK = "lock"
        const val MODE_VERIFY = "verify"
        const val ACTION_ENTER_VERIFY = "com.frit.app.VEIL_ENTER_VERIFY"
    }

    private var dismissReceiver: BroadcastReceiver? = null
    private val verifyRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        VeilManager.init(this)
        VeilManager.veilVisible = true

        // Screen-pinning blocks Home/Recents. First call shows a one-time
        // system consent dialog; if the user declines we still guard with the
        // fullscreen veil (weaker, honest fallback).
        try {
            if (!isInLockTaskMode()) startLockTask()
        } catch (_: Exception) { }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* veil swallows Back */ }
        })

        val filter = IntentFilter(VeilManager.ACTION_DISMISS_VEIL)
        filter.addAction(ACTION_ENTER_VERIFY)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    VeilManager.ACTION_DISMISS_VEIL -> unlockCleanupAndFinish()
                    ACTION_ENTER_VERIFY -> verifyRequested.value = true
                }
            }
        }
        dismissReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        } catch (_: Exception) { }

        val startVerify = intent?.getStringExtra(EXTRA_MODE) == MODE_VERIFY
        if (startVerify) verifyRequested.value = true
        setContent {
            VeilRoot(
                externalVerify = verifyRequested,
                onUnlocked = { unlockCleanupAndFinish() }
            )
        }
    }

    override fun onDestroy() {
        try {
            dismissReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) { }
        dismissReceiver = null
        setVerifyBrightness(false)
        super.onDestroy()
    }

    /** Screen-brightness boost for dark-room face checks. Restored after. */
    fun setVerifyBrightness(boost: Boolean) {
        try {
            val lp = window.attributes
            lp.screenBrightness = if (boost) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } catch (_: Exception) { }
    }

    private fun isInLockTaskMode(): Boolean = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val st = am.lockTaskModeState
        st == ActivityManager.LOCK_TASK_MODE_PINNED || st == ActivityManager.LOCK_TASK_MODE_LOCKED
    } catch (_: Exception) { false }

    private fun unlockCleanupAndFinish() {
        try {
            if (isInLockTaskMode()) stopLockTask()
        } catch (_: Exception) { }
        setVerifyBrightness(false)
        VeilManager.veilVisible = false
        try {
            finish()
        } catch (_: Exception) { }
    }
}

@Composable
private fun VeilRoot(externalVerify: State<Boolean>, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val showVerify = externalVerify.value && FaceVerifier.isEnrolled(ctx)
    fun unlockAsOwner() {
        VeilManager.ownerVerified(ctx)
        onUnlocked()
    }
    if (!showVerify) {
        VeilScreen(onUnlocked = { unlockAsOwner() })
        return
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E0C0B))
            .verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        VerifyPanel(onConcluded = { owner ->
            if (owner) {
                try {
                    android.widget.Toast.makeText(ctx, "Welcome back — tasks resumed", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { }
                unlockAsOwner()
            }
            // Stranger branch is handled inside VerifyPanel (alert + stay locked).
        })
        Spacer(Modifier.height(12.dp))
        VeilScreen(compact = true, onUnlocked = { unlockAsOwner() })
    }
}

private enum class VerifyPhase { SCAN, BLINK, TURN, MATCHING, OWNER, STRANGER, NOFACE }

@Composable
private fun VerifyPanel(onConcluded: (owner: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? VeilActivity
    val refs = remember { FaceVerifier.loadRefs(ctx) }
    var phase by remember { mutableStateOf(if (refs.isEmpty()) VerifyPhase.NOFACE else VerifyPhase.SCAN) }
    var hint by remember {
        mutableStateOf(if (refs.isEmpty()) "Face not enrolled — enter PIN below" else "Looking for a face…")
    }
    var active by remember { mutableStateOf(true) }
    var sawOpen by remember { mutableStateOf(false) }
    var sawClosed by remember { mutableStateOf(false) }
    var turnStart by remember { mutableStateOf<Float?>(null) }
    var attempts by remember { mutableStateOf(0) }

    // Dark-room boost for the whole check; restored on dispose/finish.
    DisposableEffect(Unit) {
        activity?.setVerifyBrightness(true)
        onDispose { activity?.setVerifyBrightness(false) }
    }

    fun conclude(owner: Boolean) {
        if (!active) return
        active = false
        if (!owner && phase != VerifyPhase.NOFACE) {
            try {
                VeilManager.onStrangerConfirmed(ctx)
            } catch (_: Exception) { }
            hint = "Couldn't verify you — tasks paused, owner alerted. Enter PIN if it's you."
            phase = VerifyPhase.STRANGER
        }
        onConcluded(owner)
    }

    // Fail closed on timeout.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        delay(25_000)
        if (active && (phase == VerifyPhase.SCAN || phase == VerifyPhase.BLINK ||
                phase == VerifyPhase.TURN || phase == VerifyPhase.MATCHING)
        ) {
            conclude(false)
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFEBE8E0).copy(alpha = 0.08f))
            .border(1.dp, Color(0xFFEBE8E0).copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Who picked up the phone?", color = Color(0xFFEBE8E0), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = Color(0xFFEBE8E0).copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        if (active && phase != VerifyPhase.NOFACE && phase != VerifyPhase.STRANGER) {
            VeilCameraPreview(
                modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)),
                onFace = { face, bmp, _ ->
                    if (!active) return@VeilCameraPreview
                    when (phase) {
                        VerifyPhase.SCAN -> {
                            phase = VerifyPhase.BLINK
                            sawOpen = false
                            sawClosed = false
                            hint = "Got you — now blink"
                        }
                        VerifyPhase.BLINK -> {
                            val lo = face.leftEyeOpenProbability
                            val ro = face.rightEyeOpenProbability
                            if (lo != null && ro != null) {
                                if (lo > 0.6f && ro > 0.6f) {
                                    if (sawClosed) {
                                        phase = VerifyPhase.TURN
                                        turnStart = face.headEulerAngleY
                                        hint = "Now turn your head slowly side to side"
                                    } else {
                                        sawOpen = true
                                    }
                                } else if (lo < 0.4f && ro < 0.4f && sawOpen) {
                                    sawClosed = true
                                }
                            }
                        }
                        VerifyPhase.TURN -> {
                            val s = turnStart
                            if (s != null && kotlin.math.abs(face.headEulerAngleY - s) > 18f) {
                                phase = VerifyPhase.MATCHING
                                hint = "Checking…"
                                val geo = FaceVerifier.geoVector(face)
                                val thumb = if (geo != null) FaceVerifier.cropThumb(bmp, face) else null
                                if (geo != null && thumb != null && FaceVerifier.verify(geo, thumb, refs)) {
                                    phase = VerifyPhase.OWNER
                                    conclude(true)
                                } else {
                                    attempts += 1
                                    if (attempts < 2) {
                                        phase = VerifyPhase.BLINK
                                        sawOpen = false
                                        sawClosed = false
                                        hint = "Once more — blink, then turn"
                                    } else {
                                        conclude(false)
                                    }
                                }
                            }
                        }
                        else -> { }
                    }
                }
            )
        }
    }
}

@Composable
private fun VeilScreen(onUnlocked: () -> Unit, compact: Boolean = false) {
    val ctx = LocalContext.current
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val taskRunning = remember { VeilManager.isTaskActive() }

    fun submit() {
        if (entered.length < 4) {
            error = "Enter your veil PIN"
            return
        }
        if (VeilManager.verifyPin(ctx, entered)) {
            onUnlocked()
        } else {
            val waitMs = VeilManager.lockoutRemainingMs(ctx)
            error = if (waitMs > 0) "Too many tries — wait ${waitMs / 1000}s"
            else "Wrong PIN — try again"
            entered = ""
        }
    }

    Column(
        modifier = Modifier
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .background(Color(0xFF0E0C0B))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center
    ) {
        Text("FRIT", color = Color(0xFFEBE8E0), fontSize = 30.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text(
            if (taskRunning) "Task running — phone guarded" else "Scheduled tasks armed — phone guarded",
            color = Color(0xFFEBE8E0).copy(alpha = 0.7f), fontSize = 13.sp
        )
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(8) { i ->
                val filled = i < entered.length
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (filled) Color(0xFFEBE8E0) else Color.Transparent)
                        .border(1.dp, Color(0xFFEBE8E0).copy(alpha = 0.5f), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            error ?: "Enter veil PIN to continue",
            color = if (error != null) Color(0xFFE57373) else Color(0xFFEBE8E0).copy(alpha = 0.55f),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(18.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEBE8E0).copy(alpha = 0.08f))
                            .clickable {
                                error = null
                                when {
                                    k == "⌫" -> entered = entered.dropLast(1)
                                    k.isNotEmpty() && entered.length < 8 -> entered += k
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(k, color = Color(0xFFEBE8E0), fontSize = 24.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Button(
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEBE8E0)),
            shape = RoundedCornerShape(12.dp)
        ) { Text("Unlock", color = Color(0xFF0E0C0B), fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(18.dp))
        Text(
            "Keep your phone around you while tasks run.",
            color = Color(0xFFEBE8E0).copy(alpha = 0.4f), fontSize = 11.sp
        )
    }
}
