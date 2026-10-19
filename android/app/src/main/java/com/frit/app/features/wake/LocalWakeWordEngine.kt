package com.frit.app.features.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LocalWakeWordEngine — fully on-device key-phrase spotter. No Picovoice, no
 * cloud, works with the local model because it never needs a network.
 *
 * Pipeline (all pure Kotlin):
 *   1. AudioRecord 16 kHz mono PCM capture
 *   2. VAD (frame RMS vs adaptive noise floor)
 *   3. MFCC feature frames (LocalWakeWordDsp)
 *   4. DTW alignment of captured segments against a stored reference template
 *
 * Training is self-calibrated: the user records two utterances of the wake
 * phrase; sample 1 becomes the template and the threshold is derived from how
 * close the two utterances are (so it adapts to the user's own voice/accent).
 */
object LocalWakeWordEngine {

    interface Listener {
        fun onWakeWordDetected()
        fun onTrainingStep(step: Int, message: String)
        fun onError(message: String)
    }

    private const val TAG = "LocalWakeWord"
    private const val SAMPLE_RATE = 16000
    private const val FRAME_SIZE = 320          // 20 ms
    private const val MIN_FRAMES = 16           // >= 160 ms of speech to score
    private const val END_SILENCE_FRAMES = 14   // ~ 140 ms trailing silence
    private const val MAX_SEGMENT_FRAMES = 120  // cap DTW size (~1.2 s)
    private const val TRAIN_SAMPLES = 2
    // After a trigger, ignore further audio for this long so the overlay can be
    // closed without an immediate re-open (false-positive loop).
    private const val TRIGGER_COOLDOWN_MS = 2500L

    val currentRmsFlow = MutableStateFlow(0f)

    // Last DETECT evaluation: (dtwScore, threshold). Published on EVERY scored
    // segment, match or not, so a Test UI can show "you scored X vs Y" and the
    // user can see whether misses are close (retrain clearer) or far (wrong
    // phrase/room). Null until the first evaluation after (re)starting.
    val lastScoreFlow = MutableStateFlow<Pair<Double, Double>?>(null)
    val trainingStateFlow = MutableStateFlow<Pair<Int, String>?>(null)

    private enum class Mode { IDLE, TRAIN, DETECT }

    private var appContext: Context? = null
    private var mode = Mode.IDLE
    private var listener: Listener? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    // Seed value only — overwritten by a real ambient-noise calibration the
    // instant capture starts (see calibrateNoiseFloor). A fixed 0.02 seed was
    // the root cause of "records noise even in silence": on phones whose real
    // mic noise floor sits above that, the floor could never adapt upward
    // (it only ever moved down), so ambient hiss permanently read as speech.
    private var noiseFloor = 0.02
    private var calibrated = false
    private var speechActive = false
    private var silenceFrames = 0
    private var paused = false
    private val segment = ArrayList<FloatArray>()
    // True for the ~400ms right after (re)starting the AudioRecord — HAL/driver
    // warm-up frequently emits a DC-offset click/pop that isn't real ambient
    // noise or speech and must never train the floor or count as an utterance.
    @Volatile private var warmupUntil = 0L

    // ---- reference template ----
    @Volatile private var refTemplate: List<FloatArray>? = null
    @Volatile private var refEnergy = 0.0
    @Volatile private var detectThreshold = 1.0
    @Volatile private var isTrained = false
    @Volatile private var cooldownUntil = 0L

    // ---- training capture ----
    private val trainSamples = ArrayList<List<FloatArray>>()

    fun init(context: Context) {
        appContext = context.applicationContext
        loadTemplate()
    }

    fun isTrained(): Boolean = isTrained && refTemplate != null

    fun setListener(l: Listener) { listener = l }

    fun startListening(l: Listener) {
        listener = l
        if (!isTrained()) {
            l.onError("Wake word not trained. Open FRIT settings and train it first.")
            return
        }
        mode = Mode.DETECT
        paused = false
        resetSegment()
        startCapture()
    }

    fun startTraining(l: Listener) {
        listener = l
        trainSamples.clear()
        mode = Mode.TRAIN
        paused = false
        resetSegment()
        startCapture()
        trainingStateFlow.value = Pair(1, "Say the wake phrase once, clearly. Keep it short.")
        l.onTrainingStep(1, "Say the wake phrase once, clearly. Keep it short.")
    }

    /**
     * Pause detection AND release the microphone so another component (e.g. the
     * voice overlay or a normal voice recording) can capture real audio. Merely
     * skipping frame processing left the AudioRecord open, which made the other
     * recorder read silence on many devices.
     */
    fun pause() {
        if (paused) return
        paused = true
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    /** Resume listening: recreate the AudioRecord and restart the capture loop. */
    fun resume() {
        paused = false
        if (mode == Mode.DETECT || mode == Mode.TRAIN) startCapture()
    }

    fun stop() {
        paused = false
        mode = Mode.IDLE
        trainingStateFlow.value = null
        job?.cancel()
        job = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        resetSegment()
    }

    fun isRunning(): Boolean = job?.isActive == true && !paused && mode != Mode.IDLE

    private fun resetSegment() {
        segment.clear()
        speechActive = false
        silenceFrames = 0
    }

    /* ================= capture loop ================= */

    private var audioRecord: AudioRecord? = null

    private fun startCapture() {
        job?.cancel()
        job = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SIZE)

            while (isActive && mode != Mode.IDLE) {
                var record: AudioRecord? = null
                try {
                    record = try {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufferSize
                        )
                    } catch (_: Exception) { null }

                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        record = try {
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, bufferSize
                            )
                        } catch (_: Exception) { null }
                    }

                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        delay(1500)
                        continue
                    }
                    audioRecord = record
                    record.startRecording()
                    // Fresh AudioRecord instance => fresh warm-up window and a
                    // fresh ambient-noise calibration (do NOT carry over a
                    // calibration taken against a different open/close cycle).
                    warmupUntil = System.currentTimeMillis() + 400L
                    calibrated = false
                    calibrateFramesLeft = 0
                    calibrateSum = 0.0

                    val frame = ShortArray(FRAME_SIZE)
                    while (isActive && mode != Mode.IDLE) {
                        // Yielded to another app (WhatsApp etc.): spin slow so
                        // we don't drain battery while waiting for the mic.
                        if (paused) { delay(200); continue }
                        val read = record.read(frame, 0, FRAME_SIZE)
                        if (read > 0) {
                            processFrame(frame.copyOf(read))
                        } else {
                            if (read < 0) {
                                Log.w(TAG, "AudioRecord read error ($read), reinitializing capture loop...")
                                break
                            }
                            delay(15)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Capture error", e)
                    if (mode != Mode.IDLE) delay(800)
                } finally {
                    try { record?.stop(); record?.release() } catch (_: Exception) {}
                }
            }
        }
        job?.invokeOnCompletion {
            if (mode == Mode.TRAIN && trainSamples.isNotEmpty()) {
                // Capture aborted mid-training -> discard partial state
                trainSamples.clear()
            }
        }
    }

    /* ================= VAD + scoring ================= */

    // First ~300ms of a fresh capture session (calibrateStart..calibrateStart+CALIBRATE_FRAMES)
    // are pure ambient-noise sampling before we trust anything as speech.
    private var calibrateFramesLeft = 0
    private var calibrateSum = 0.0
    private const val CALIBRATE_FRAMES = 15 // ~300ms at 20ms/frame

    private fun processFrame(shorts: ShortArray) {
        val frame = FloatArray(shorts.size) { i -> shorts[i] / 32768f }
        val rms = LocalWakeWordDsp.rmsOf(frame)
        currentRmsFlow.value = rms.toFloat()

        // Ignore HAL warm-up artifacts entirely — don't calibrate on them,
        // don't treat them as speech, don't feed them into anything.
        if (System.currentTimeMillis() < warmupUntil) return

        // One-time real calibration right after (re)starting capture: sample
        // genuine ambient noise instead of trusting the 0.02 seed. This is what
        // fixes "records/triggers on pure background noise" on phones whose
        // real noise floor is higher (or lower) than that seed.
        if (!calibrated) {
            calibrateFramesLeft = if (calibrateFramesLeft == 0 && calibrateSum == 0.0) CALIBRATE_FRAMES else calibrateFramesLeft
            calibrateSum += rms
            calibrateFramesLeft--
            if (calibrateFramesLeft <= 0) {
                val measured = calibrateSum / CALIBRATE_FRAMES
                // Floor to whichever is higher: measured ambient noise, or a
                // small absolute minimum so a dead-silent room doesn't leave
                // the floor at ~0 (which would make EVERYTHING look like speech).
                noiseFloor = maxOf(measured, 0.006)
                calibrated = true
            }
            return
        }

        // Adaptive noise floor — moves BOTH up and down toward the recent quiet
        // level, not just down. The old one-directional version could get stuck
        // below the real ambient level forever and permanently misread noise as
        // speech.
        if (!speechActive) {
            noiseFloor = noiseFloor * 0.98 + rms * 0.02
        }

        if (rms > noiseFloor * 2.2) {
            if (speechActive) {
                if (segment.size < MAX_SEGMENT_FRAMES) segment.add(frame)
                else evaluateSegment()
            } else {
                segment.clear()
                segment.add(frame)
                speechActive = true
            }
            silenceFrames = 0
        } else if (speechActive) {
            segment.add(frame)
            silenceFrames++
            if (silenceFrames >= END_SILENCE_FRAMES) evaluateSegment()
        }
    }

    private fun evaluateSegment() {
        val seg = segment.toList()
        resetSegment()
        if (seg.size < MIN_FRAMES) return
        val frames = LocalWakeWordDsp.extractFeatures(seg)
        if (frames.isEmpty()) return
        val energy = LocalWakeWordDsp.averageEnergy(seg)

        when (mode) {
            Mode.DETECT -> {
                // Cooldown: don't re-trigger right after a wake (covers the
                // "closes but instantly reopens" loop from release of the mic).
                if (System.currentTimeMillis() < cooldownUntil) return
                val ref = refTemplate ?: return
                val score = LocalWakeWordDsp.dtw(ref, frames)
                lastScoreFlow.value = Pair(score, detectThreshold)
                if (score < detectThreshold) {
                    cooldownUntil = System.currentTimeMillis() + TRIGGER_COOLDOWN_MS
                    Log.i(TAG, "Wake word matched! dtw=$score")
                    listener?.onWakeWordDetected()
                }
            }
            Mode.TRAIN -> captureTrainingSample(frames, energy)
            Mode.IDLE -> {}
        }
    }

    private fun captureTrainingSample(frames: List<FloatArray>, energy: Double) {
        trainSamples.add(frames)
        if (trainSamples.size == 1) {
            trainingStateFlow.value = Pair(2, "Got it. Say it once more.")
            listener?.onTrainingStep(2, "Got it. Say it once more.")
        } else {
            finalizeTraining()
        }
    }

    private fun finalizeTraining() {
        if (trainSamples.size < 2) return
        val first = trainSamples[0]
        val second = trainSamples[1]
        val dist = LocalWakeWordDsp.dtw(first, second)
        // Energy-invariant distance now (c0 excluded): same-phrase scores land
        // O(1), so the clamp opens up to match. NOTE: retrain once after this
        // change — stored thresholds from the c0 era are calibrated wrong.
        detectThreshold = if (dist.isFinite() && dist > 0) (dist * 1.5).coerceIn(0.6, 4.0) else 1.4
        refTemplate = first
        refEnergy = first.map { LocalWakeWordDsp.rmsOf(it) }.average()
        isTrained = true
        cooldownUntil = System.currentTimeMillis() + TRIGGER_COOLDOWN_MS
        saveTemplate()
        trainSamples.clear()
        mode = Mode.DETECT
        trainingStateFlow.value = Pair(0, "Wake word trained successfully!")
        listener?.onTrainingStep(0, "Wake word trained. Listening.")
    }

    /* ================= template persistence ================= */

    private fun templateFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, "wake_template.json")
    }

    private fun saveTemplate() {
        val ref = refTemplate ?: return
        try {
            templateFile()?.delete()
            val framesArr = JSONArray()
            ref.forEach { f ->
                val row = JSONArray()
                f.forEach { row.put(it.toDouble()) }
                framesArr.put(row)
            }
            val json = JSONObject().apply {
                put("frames", framesArr)
                put("energy", refEnergy)
                put("threshold", detectThreshold)
            }
            templateFile()?.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Save template failed", e)
        }
    }

    private fun loadTemplate() {
        try {
            val file = templateFile() ?: return
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val framesArr = json.getJSONArray("frames")
            val frames = ArrayList<FloatArray>()
            for (i in 0 until framesArr.length()) {
                val row = framesArr.getJSONArray(i)
                val f = FloatArray(row.length()) { k -> row.getDouble(k).toFloat() }
                frames.add(f)
            }
            if (frames.isNotEmpty()) {
                refTemplate = frames
                refEnergy = json.optDouble("energy", 0.0)
                detectThreshold = json.optDouble("threshold", 1.4)
                isTrained = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load template failed", e)
            isTrained = false
        }
    }
}