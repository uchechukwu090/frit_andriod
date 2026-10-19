package com.frit.app.features.wake

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LocalWakeWordDsp — self-contained DSP for the on-device wake-word engine:
 * radix-2 FFT, mel filterbank, MFCC feature frames and DTW scoring.
 * Pure Kotlin, no external dependencies, works fully offline.
 */
object LocalWakeWordDsp {

    const val FFT_SIZE = 512
    private const val N_MEL = 24
    private const val N_MFCC = 12
    private const val SAMPLE_RATE = 16000
    private const val LOW_FREQ = 80.0
    private const val HIGH_FREQ = 7600.0

    // Mel filterbank: [N_MEL][FFT_SIZE/2 + 1]
    val melBank: Array<DoubleArray> by lazy { buildMelFilterBank() }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    private fun buildMelFilterBank(): Array<DoubleArray> {
        val numBins = FFT_SIZE / 2 + 1
        val melHigh = hzToMel(HIGH_FREQ)
        val melLow = hzToMel(LOW_FREQ)
        val melPoints = DoubleArray(N_MEL + 2)
        for (i in 0 until N_MEL + 2) {
            melPoints[i] = melLow + (melHigh - melLow) * i.toDouble() / (N_MEL + 1).toDouble()
        }

        val bank = Array(N_MEL) { DoubleArray(numBins) }
        for (i in 0 until N_MEL) {
            for (k in 0 until numBins) {
                val hz = k.toDouble() * SAMPLE_RATE.toDouble() / FFT_SIZE.toDouble()
                val mel = hzToMel(hz)
                val left = melPoints[i]
                val center = melPoints[i + 1]
                val right = melPoints[i + 2]
                if (mel < left || mel > right) {
                    bank[i][k] = 0.0
                } else if (mel < center) {
                    bank[i][k] = if (center != left) (mel - left) / (center - left) else 1.0
                } else {
                    bank[i][k] = if (right != center) (right - mel) / (right - center) else 1.0
                }
            }
        }
        return bank
    }

    /** Frame RMS, used for VAD and energy normalization. */
    fun rmsOf(frame: FloatArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (s in frame) sum += s * s
        return sqrt(sum / frame.size)
    }

    /** Average frame energy for a captured segment. */
    fun averageEnergy(frames: List<FloatArray>): Double {
        if (frames.isEmpty()) return 0.0
        return frames.map { rmsOf(it) }.average()
    }

    /**
     * Extract MFCC features for a frame (Hamming window -> FFT -> mel -> log -> DCT).
     */
    fun mfccOf(frame: FloatArray): FloatArray {
        val n = FFT_SIZE
        val re = DoubleArray(n)
        val im = DoubleArray(n)

        // Hamming window + pre-emphasis
        for (i in 0 until n) {
            val x = if (i < frame.size) frame[i].toDouble() else 0.0
            val pre = if (i > 0) x - 0.97 * (if (i - 1 < frame.size) frame[i - 1].toDouble() else 0.0) else x
            re[i] = pre * (0.54 - 0.46 * cos(2.0 * PI * i / (n - 1)))
        }

        fft(re, im)

        val mag = DoubleArray(n / 2 + 1)
        for (i in 0 until n / 2 + 1) {
            mag[i] = re[i] * re[i] + im[i] * im[i]
        }

        val melE = DoubleArray(N_MEL)
        for (m in 0 until N_MEL) {
            var sum = 0.0
            val row = melBank[m]
            for (k in 0 until n / 2 + 1) sum += row[k] * mag[k]
            melE[m] = if (sum > 1e-10) ln(sum) else 0.0
        }

        // DCT-II -> first N_MFCC coefficients
        val c = FloatArray(N_MFCC)
        for (i in 0 until N_MFCC) {
            var acc = 0.0
            for (m in 0 until N_MEL) {
                acc += melE[m] * cos(PI * i * (m + 0.5) / N_MEL)
            }
            c[i] = (acc * sqrt(2.0 / N_MEL)).toFloat()
        }
        return c
    }

    /**
     * Extract feature frames from a captured segment, with Cepstral Mean Normalization (CMN)
     * to eliminate channel distortion and volume variance.
     */
    fun extractFeatures(frames: List<FloatArray>): List<FloatArray> {
        if (frames.isEmpty()) return emptyList()
        val raw = frames.map { mfccOf(it) }
        val numFrames = raw.size
        val numCoeffs = raw[0].size
        val means = FloatArray(numCoeffs)
        for (f in raw) {
            for (i in 0 until numCoeffs) {
                means[i] += f[i]
            }
        }
        for (i in 0 until numCoeffs) means[i] /= numFrames.toFloat()
        return raw.map { f ->
            FloatArray(numCoeffs) { i -> f[i] - means[i] }
        }
    }

    /**
     * Iterative radix-2 FFT (in place). n must be a power of two.
     */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n == 0 || (n and (n - 1)) != 0) return

        // bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang: Double = -2.0 * PI / len.toDouble()
            val wRe: Double = cos(ang)
            val wIm: Double = sin(ang)
            var i = 0
            while (i < n) {
                var curRe: Double = 1.0
                var curIm: Double = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a: Double = re[i + k]
                    val b: Double = im[i + k]
                    val c: Double = re[i + k + half]
                    val d: Double = im[i + k + half]
                    val vRe: Double = c * curRe - d * curIm
                    val vIm: Double = c * curIm + d * curRe
                    re[i + k] = a + vRe
                    im[i + k] = b + vIm
                    re[i + k + half] = a - vRe
                    im[i + k + half] = b - vIm
                    val nextRe: Double = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Euclidean DTW distance between two MFCC sequences, normalized by path length.
     */
    fun dtw(a: List<FloatArray>, b: List<FloatArray>): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Double.MAX_VALUE
        val large = Double.MAX_VALUE / 4.0
        val d = Array(n) { DoubleArray(m) { large } }
        for (i in 0 until n) {
            for (kk in 0 until m) {
                d[i][kk] = euclid(a[i], b[kk]) + minOf(
                    if (i > 0 && kk > 0) d[i - 1][kk - 1] else if (i == 0 && kk == 0) 0.0 else large,
                    if (i > 0) d[i - 1][kk] else large,
                    if (kk > 0) d[i][kk - 1] else large
                )
            }
        }
        return d[n - 1][m - 1] / (n + m).toDouble()
    }

    private fun euclid(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        val len = min(a.size, b.size)
        // Skip c0 (overall frame energy): raw MFCC energy is O(10-100) and
        // would dominate the distance, pushing genuine same-phrase scores to
        // ~10+ while the detect threshold clamps near ~2 — the "scores 11.6
        // vs threshold 2.0, overlay never fires" bug. c1..c11 carry the
        // phonetic shape; VAD already gates on energy, so nothing is lost.
        for (i in 1 until len) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }
}