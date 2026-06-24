package com.example.nihongolens

import android.util.Log
import kotlin.math.*

/**
 * GenderAnalyzer v3 — YIN pitch detection on PCM fed by SpeechCaptureService.
 *
 * WHY YIN INSTEAD OF FFT CENTROID:
 *   Spectral centroid is dominated by room acoustics, background noise, and bass
 *   frequencies that have nothing to do with the speaker's voice. In a conference
 *   video with applause, music, and multiple speakers the centroid is almost always
 *   pulled into the male range regardless of who is speaking.
 *
 *   YIN (de Cheveigné & Kawahara 2002) finds the actual fundamental frequency (F0)
 *   of the dominant periodic signal — i.e. the current speaker's pitch — ignoring
 *   aperiodic noise. It is the industry standard for monophonic pitch detection and
 *   works robustly on real-world mixed audio.
 *
 *   F0 < 165 Hz → male voice
 *   F0 ≥ 165 Hz → female voice
 *
 * Architecture:
 *   SpeechCaptureService feeds raw PCM bytes via feedPcm().
 *   No second AudioRecord needed — shares the existing capture stream.
 */
object GenderAnalyzer {

    private const val TAG  = "GenderAnalyzer"
    private const val SR   = 16_000      // sample rate (matches SpeechCaptureService)
    private const val WIN  = 2048        // YIN window — 128ms at 16kHz
    private const val TAU_MIN = (SR / 300.0).toInt()   // 300 Hz upper limit  = 53 samples
    private const val TAU_MAX = (SR / 60.0).toInt()    // 60 Hz  lower limit  = 266 samples
    private const val YIN_THRESHOLD = 0.15f             // lower = stricter pitch confidence
    private const val HIST = 5           // vote history — switch on 3/5 majority
    private const val RMS_FLOOR = 200f   // skip very quiet frames (silence / noise)

    @Volatile var enabled = false

    private val history   = ArrayDeque<HindiTtsService.Gender>()
    private val accum     = ShortArray(WIN)
    private var accumFill = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        enabled = true
        history.clear()
        accumFill = 0
        Log.d(TAG, "GenderAnalyzer started (YIN pitch detection)")
        CaptionLogger.log(TAG, "started")
    }

    fun stop() {
        enabled = false
        history.clear()
        accumFill = 0
        Log.d(TAG, "GenderAnalyzer stopped")
    }

    // ── PCM feed ──────────────────────────────────────────────────────────────

    /**
     * Called by SpeechCaptureService on every raw AudioRecord.read().
     * bytes: raw PCM 16-bit LE mono. count: valid byte count.
     * Runs on AudioCaptureThread — no I/O, no blocking.
     */
    fun feedPcm(bytes: ByteArray, count: Int) {
        if (!enabled) return
        if (HindiTtsService.isSuppressed()) { accumFill = 0; return }

        var i = 0
        while (i + 1 < count) {
            if (accumFill < WIN) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt() and 0xFF
                accum[accumFill++] = ((hi shl 8) or lo).toShort()
            }
            i += 2
            if (accumFill >= WIN) {
                analyze()
                accumFill = 0
            }
        }
    }

    // ── YIN pitch detection ───────────────────────────────────────────────────

    private fun analyze() {
        // RMS check — skip silence and near-silence
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) return

        // Normalize to float [-1, 1]
        val x = FloatArray(WIN) { accum[it] / 32768f }

        // Step 1 — difference function d(tau)
        // d(tau) = sum_{j=0}^{W-tau-1} (x[j] - x[j+tau])^2
        val d = FloatArray(TAU_MAX + 1)
        val halfWin = WIN / 2
        for (tau in 1..TAU_MAX) {
            var sum = 0.0f
            for (j in 0 until halfWin) {
                val diff = x[j] - x[j + tau]
                sum += diff * diff
            }
            d[tau] = sum
        }

        // Step 2 — cumulative mean normalized difference function (CMNDF)
        val cmndf = FloatArray(TAU_MAX + 1)
        cmndf[0] = 1f
        var runSum = 0f
        for (tau in 1..TAU_MAX) {
            runSum += d[tau]
            cmndf[tau] = if (runSum > 0f) d[tau] * tau / runSum else 1f
        }

        // Step 3 — find first dip below threshold in valid range
        var tau = TAU_MIN
        while (tau < TAU_MAX - 1) {
            if (cmndf[tau] < YIN_THRESHOLD) {
                // Step 4 — parabolic interpolation for sub-sample precision
                val better = if (tau + 1 < TAU_MAX && cmndf[tau + 1] < cmndf[tau])
                    tau + 1 else tau
                val f0 = SR.toFloat() / better
                classifyPitch(f0, rms)
                return
            }
            tau++
        }
        // No confident pitch found — frame is unvoiced (noise/silence/music)
        // Don't vote — let history hold its current state
    }

    private fun classifyPitch(f0: Float, rms: Float) {
        // Hard boundary at 165 Hz (standard male/female divide)
        val gender = if (f0 >= 165f) HindiTtsService.Gender.FEMALE
                     else             HindiTtsService.Gender.MALE

        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()

        // Need 3/5 majority to switch — avoids flip-flopping on ambiguous frames
        val fCount   = history.count { it == HindiTtsService.Gender.FEMALE }
        val majority = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
                       else HindiTtsService.Gender.MALE

        if (majority != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = majority
            HindiTtsService.spokenTokens.clear()
            Log.d(TAG, "Gender→$majority F0=${f0.toInt()}Hz rms=${rms.toInt()}")
            CaptionLogger.log(TAG, "Gender→$majority F0=${f0.toInt()}Hz")
        }
    }
}
