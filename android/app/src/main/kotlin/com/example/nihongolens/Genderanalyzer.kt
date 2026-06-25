package com.example.nihongolens

import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.util.Log
import kotlin.math.*

/**
 * GenderAnalyzer v6 — Visualizer API + YIN pitch detection.
 *
 * ARCHITECTURE:
 *   Uses android.media.audiofx.Visualizer (session 0 = global audio mix).
 *   Captures waveform data from the Android audio mixer output.
 *   NO MediaProjection needed. NO ForegroundService needed.
 *   Requires only RECORD_AUDIO permission (already in manifest).
 *   Works identically with speakers AND headphones.
 *   Works in BOTH Live Captions mode AND Audio Capture mode.
 *
 * HOW IT WORKS:
 *   Visualizer fires onWaveFormDataCapture() at ~20Hz with 1024 bytes of 8-bit PCM.
 *   We accumulate 4 callbacks → 4096 samples → run YIN pitch detection.
 *   YIN finds F0 of dominant periodic signal (the current speaker's voice).
 *   F0 < 165Hz → male, F0 ≥ 165Hz → female.
 *   HIST=3: needs 2/3 majority to switch (avoids single-frame noise).
 *
 * TTS SEPARATION:
 *   Visualizer captures ALL audio including Hindi TTS.
 *   We skip analysis while isSuppressed() to avoid detecting our own TTS voice.
 *   Hindi TTS (USAGE_ASSISTANT) uses a different pitch range from natural speech,
 *   but suppression is cleaner.
 */
object GenderAnalyzer {

    private const val TAG = "GenderAnalyzer"

    // YIN parameters — tuned for Visualizer's native sample rate
    // Visualizer gives 8-bit PCM at device output sample rate (typically 44100 or 48000)
    // We detect pitch in range 60–300 Hz covering both male and female voice F0
    private const val WIN      = 4096    // accumulate 4 × 1024-byte captures
    private const val F0_MALE_MAX   = 165f   // Hz — above this = female
    private const val YIN_THRESHOLD = 0.30f  // CMNDF confidence threshold
    private const val RMS_FLOOR     = 8f     // 8/128 = ~6% of max — skip near-silence
    private const val HIST          = 3      // 2/3 majority to switch

    @Volatile var enabled  = false

    private var visualizer: Visualizer? = null
    private var sampleRate = 44100       // updated from Visualizer at start

    // Accumulation buffer (8-bit unsigned from Visualizer, converted to float)
    private val accum     = ByteArray(WIN)
    private var accumFill = 0

    private val history = ArrayDeque<HindiTtsService.Gender>()

    // Diagnostics
    private var frameCount   = 0
    private var analyzeCount = 0
    private var captureCount = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start gender detection. Call from anywhere — no projection needed.
     * projection param kept for API compatibility but ignored.
     */
    fun start(projection: MediaProjection? = null) {
        stop()
        CaptionLogger.log(TAG, "start() called — attempting Visualizer(0) global audio mix")
        try {
            // Session 0 = global audio output mix
            // Requires RECORD_AUDIO + MODIFY_AUDIO_SETTINGS permissions
            val v = Visualizer(0)
            val capSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.captureSize   = capSize
            val rawSR = v.samplingRate
            sampleRate = rawSR / 1000   // Visualizer returns mHz → divide by 1000 for Hz
            CaptionLogger.log(TAG, "Visualizer init OK: rawSamplingRate=$rawSR sampleRate=${sampleRate}Hz capSize=$capSize")
            if (sampleRate < 8000) sampleRate = 44100 // fallback

            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(viz: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    if (!enabled) return
                    captureCount++
                    if (captureCount == 1) CaptionLogger.log(TAG, "FIRST waveform! size=${waveform.size} sr=${samplingRate/1000}Hz")
                    feedWaveform(waveform)
                }
                override fun onFftDataCapture(viz: Visualizer, fft: ByteArray, samplingRate: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)

            v.enabled  = true
            visualizer = v
            enabled    = true
            accumFill  = 0
            frameCount = 0; analyzeCount = 0; captureCount = 0
            history.clear()

            CaptionLogger.log(TAG, ">>> STARTED Visualizer SR=${sampleRate}Hz enabled=$enabled <<<")
        } catch (e: Exception) {
            enabled = false
            CaptionLogger.log(TAG, "Visualizer start FAILED: ${e.message}")
            Log.e(TAG, "Visualizer start failed", e)
        }
    }

    fun stop() {
        enabled = false
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() }        catch (_: Exception) {}
        visualizer = null
        history.clear()
        accumFill = 0
        CaptionLogger.log(TAG, "stopped")
    }

    // ── Waveform ingestion ────────────────────────────────────────────────────

    private fun feedWaveform(waveform: ByteArray) {
        // Skip while TTS is playing — avoid detecting our own Hindi voice
        if (HindiTtsService.isSuppressed()) {
            accumFill = 0   // reset so we start fresh after TTS ends
            return
        }

        for (b in waveform) {
            if (accumFill < WIN) accum[accumFill++] = b
            if (accumFill >= WIN) {
                analyze()
                accumFill = 0
            }
        }
    }

    // ── YIN pitch detection ───────────────────────────────────────────────────

    private fun analyze() {
        analyzeCount++

        // Convert 8-bit unsigned PCM (0–255, center=128) to float [-1, 1]
        val x = FloatArray(WIN) { (accum[it].toInt() and 0xFF - 128) / 128f }

        // RMS check — skip silence
        var energy = 0.0f
        for (v in x) energy += v * v
        val rms = sqrt(energy / WIN)
        if (rms < RMS_FLOOR / 128f) {
            if (analyzeCount % 20 == 0)
                CaptionLogger.log(TAG, "SILENT #$analyzeCount rms=${(rms * 128).toInt()} captures=$captureCount")
            return
        }

        // Compute TAU range from actual sample rate
        val tauMin = (sampleRate / 300).coerceAtLeast(1)
        val tauMax = (sampleRate / 60).coerceAtMost(WIN / 2 - 1)
        val halfWin = WIN / 2

        // YIN step 1 — difference function
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var sum = 0.0f
            for (j in 0 until halfWin) {
                val diff = x[j] - x[j + tau]
                sum += diff * diff
            }
            d[tau] = sum
        }

        // YIN step 2 — CMNDF
        val cmndf = FloatArray(tauMax + 1)
        cmndf[0] = 1f
        var runSum = 0f
        for (tau in 1..tauMax) {
            runSum += d[tau]
            cmndf[tau] = if (runSum > 0f) d[tau] * tau / runSum else 1f
        }

        // YIN step 3 — first dip below threshold
        var tau = tauMin
        while (tau < tauMax - 1) {
            if (cmndf[tau] < YIN_THRESHOLD) {
                val best = if (tau + 1 < tauMax && cmndf[tau + 1] < cmndf[tau]) tau + 1 else tau
                val f0   = sampleRate.toFloat() / best
                onPitchDetected(f0, rms * 128f)
                return
            }
            tau++
        }

        // No pitch found — log diagnostics
        if (analyzeCount % 10 == 0) {
            var minVal = 1f; var minTau = tauMin
            for (t in tauMin until tauMax) { if (cmndf[t] < minVal) { minVal = cmndf[t]; minTau = t } }
            CaptionLogger.log(TAG, "noPitch #$analyzeCount rms=${(rms*128).toInt()} " +
                "minCMNDF=${"%.3f".format(minVal)} f0est=${(sampleRate.toFloat()/minTau).toInt()}Hz")
        }
    }

    // ── Gender classification ─────────────────────────────────────────────────

    private fun onPitchDetected(f0: Float, rms: Float) {
        frameCount++
        val gender = if (f0 >= F0_MALE_MAX) HindiTtsService.Gender.FEMALE
                     else                    HindiTtsService.Gender.MALE

        if (frameCount % 3 == 0)
            CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz rms=${rms.toInt()} → $gender cur=${HindiTtsService.detectedGender}")

        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()

        val fCount   = history.count { it == HindiTtsService.Gender.FEMALE }
        val majority = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
                       else                            HindiTtsService.Gender.MALE

        if (majority != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = majority
            HindiTtsService.spokenTokens.clear()
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $majority F0=${f0.toInt()}Hz <<<")
            Log.d(TAG, "Gender→$majority F0=${f0.toInt()}Hz rms=${rms.toInt()}")
        }
    }
}
