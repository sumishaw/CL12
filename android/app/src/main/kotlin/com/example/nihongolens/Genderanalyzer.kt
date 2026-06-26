package com.example.nihongolens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * GenderAnalyzer v9 — Gender + Emotion detection from USAGE_MEDIA internal audio.
 * All 23 emotion/voice types detected acoustically. No mic. No Visualizer.
 */
object GenderAnalyzer {

    private const val TAG           = "GenderAnalyzer"
    private const val SR            = 16_000
    private const val WIN           = 2048
    private const val F0_VOICE_MIN  = 85f    // below = music bass, not voice
    private const val F0_FEMALE_MIN = 165f   // male: 85–164Hz, female: 165–400Hz
    private const val F0_VOICE_MAX  = 400f   // above = falsetto/noise
    private const val YIN_THRESH    = 0.22f
    private const val RMS_FLOOR     = 80f
    private const val HIST          = 3      // 2/3 majority to switch gender

    @Volatile var enabled       = false
    @Volatile var lastStatus    = "waiting for screen capture permission"
    @Volatile var detectedEmotion: HindiTtsService.Emotion = HindiTtsService.Emotion.NEUTRAL

    private val history        = ArrayDeque<HindiTtsService.Gender>()
    private val emotionHistory = ArrayDeque<HindiTtsService.Emotion>()
    private val accum          = ShortArray(WIN)
    private var accumFill      = 0

    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob:    Job?         = null
    private var captureRec:    AudioRecord? = null

    private var prevF0      = 0f
    private var f0History   = FloatArray(8)
    private var f0HistIdx   = 0
    private var frameCount  = 0
    private var analyzeCount = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(projection: MediaProjection? = null) {
        if (enabled) return
        if (projection == null) {
            lastStatus = "no projection — grant screen capture permission"
            CaptionLogger.log(TAG, "start() — no projection")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastStatus = "API < Q — not supported"; return
        }
        stop()
        captureJob = scope.launch { captureLoop(projection) }
    }

    fun stop() {
        enabled = false
        captureJob?.cancel(); captureJob = null
        try { captureRec?.stop()    } catch (_: Exception) {}
        try { captureRec?.release() } catch (_: Exception) {}
        captureRec = null
        history.clear(); emotionHistory.clear()
        accumFill = 0
        if (lastStatus != "waiting for screen capture permission")
            CaptionLogger.log(TAG, "stopped")
    }

    // ── USAGE_MEDIA capture loop ──────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLoop(projection: MediaProjection) = withContext(Dispatchers.IO) {
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        } catch (e: Exception) {
            enabled = false; lastStatus = "config failed: ${e.message}"
            CaptionLogger.log(TAG, "config failed: ${e.message}"); return@withContext
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(WIN * 4)

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            enabled = false; lastStatus = "AudioRecord failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioRecord failed: ${e.message}"); return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            enabled = false; rec.release()
            lastStatus = "AudioRecord state=${rec.state}"
            CaptionLogger.log(TAG, "AudioRecord not initialized"); return@withContext
        }

        captureRec = rec; enabled = true
        lastStatus = "capturing USAGE_MEDIA SR=${SR}Hz"
        rec.startRecording()
        CaptionLogger.log(TAG, ">>> INTERNAL AUDIO CAPTURE STARTED SR=${SR}Hz <<<")

        val buf = ByteArray(WIN * 2)
        var readCount = 0
        try {
            while (currentCoroutineContext().isActive && enabled) {
                val n = rec.read(buf, 0, buf.size)
                when {
                    n > 0  -> { readCount++; if (readCount == 1) CaptionLogger.log(TAG, "FIRST read: $n bytes — audio flowing!"); ingest(buf, n) }
                    n < 0  -> { CaptionLogger.log(TAG, "read error=$n"); break }
                }
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            captureRec = null; enabled = false
            CaptionLogger.log(TAG, "captureLoop ended reads=$readCount")
        }
    }

    // ── PCM ingestion ─────────────────────────────────────────────────────────

    private fun ingest(bytes: ByteArray, count: Int) {
        var i = 0
        while (i + 1 < count) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            accum[accumFill++] = ((hi shl 8) or lo).toShort()
            i += 2
            if (accumFill >= WIN) { analyze(); accumFill = 0 }
        }
    }

    // ── YIN pitch + emotion features ─────────────────────────────────────────

    private fun analyze() {
        analyzeCount++
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) return

        val tauMin = (SR / 300).coerceAtLeast(1)
        val tauMax = (SR / 60).coerceAtMost(WIN / 2 - 1)
        val half   = WIN / 2

        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var s = 0f
            for (j in 0 until half) {
                val diff = accum[j].toFloat() / 32768f - accum[j + tau].toFloat() / 32768f
                s += diff * diff
            }
            d[tau] = s
        }

        val c = FloatArray(tauMax + 1); c[0] = 1f; var rs = 0f
        for (tau in 1..tauMax) {
            rs += d[tau]; c[tau] = if (rs > 0f) d[tau] * tau / rs else 1f
        }

        var tau = tauMin; var minCmndf = 1f
        while (tau < tauMax - 1) {
            if (c[tau] < minCmndf) minCmndf = c[tau]
            if (c[tau] < YIN_THRESH) {
                val best = if (tau + 1 < tauMax && c[tau + 1] < c[tau]) tau + 1 else tau
                onPitch(SR.toFloat() / best, rms, 1f - minCmndf)
                return
            }
            tau++
        }
        if (analyzeCount % 15 == 0) {
            var mv = 1f; var mt = tauMin
            for (t in tauMin until tauMax) if (c[t] < mv) { mv = c[t]; mt = t }
            CaptionLogger.log(TAG, "noPitch rms=${rms.toInt()} minCMNDF=${"%.3f".format(mv)} f0est=${SR/mt}Hz")
        }
    }

    // ── Gender + Emotion classification ──────────────────────────────────────

    private fun onPitch(f0: Float, rms: Float, hnr: Float) {
        frameCount++

        // ── VOICE RANGE GATE — filters music bass (<85Hz) and noise (>400Hz) ──
        if (f0 < F0_VOICE_MIN || f0 > F0_VOICE_MAX) {
            if (frameCount % 15 == 0)
                CaptionLogger.log(TAG, "IGNORE F0=${f0.toInt()}Hz outside voice ${F0_VOICE_MIN.toInt()}–${F0_VOICE_MAX.toInt()}Hz")
            return
        }

        // ── GENDER ────────────────────────────────────────────────────────────
        val gender = if (f0 >= F0_FEMALE_MIN) HindiTtsService.Gender.FEMALE
                     else                      HindiTtsService.Gender.MALE

        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()
        val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
        val maj    = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
                     else                            HindiTtsService.Gender.MALE

        if (maj != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = maj
            HindiTtsService.spokenTokens.clear()
            lastStatus = "MEDIA → $maj F0=${f0.toInt()}Hz"
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $maj F0=${f0.toInt()}Hz <<<")
        }

        // ── EMOTION FEATURES ──────────────────────────────────────────────────
        val f0Slope  = if (prevF0 > 0f) (f0 - prevF0) / prevF0 else 0f
        prevF0 = f0
        f0History[f0HistIdx % f0History.size] = f0; f0HistIdx++
        val validF0   = f0History.filter { it > 0f }
        val f0Mean    = if (validF0.isEmpty()) f0 else validF0.average().toFloat()
        val f0Jitter  = if (validF0.size < 2) 0f else
            validF0.map { abs(it - f0Mean) }.average().toFloat() / f0Mean.coerceAtLeast(1f)
        val rmsNorm   = (rms / 3000f).coerceIn(0f, 3f)

        // ── 23-TYPE EMOTION CLASSIFIER ────────────────────────────────────────
        // Priority: Rhythmic > Intense > Breathive/Warm > Basic
        val emotion: HindiTtsService.Emotion = when {

            // RHYTHMIC & EXPRESSIVE
            f0Slope > 0.20f && rmsNorm > 2.0f && f0Jitter > 0.18f ->
                HindiTtsService.Emotion.GASPING
            f0Jitter > 0.22f && rmsNorm > 1.8f && f0 > f0Mean * 1.02f ->
                HindiTtsService.Emotion.PANTING
            f0 < f0Mean * 0.85f && f0Jitter < 0.06f && rmsNorm in 0.3f..1.0f && hnr > 0.4f ->
                HindiTtsService.Emotion.MOANING
            f0Slope < -0.12f && rmsNorm < 0.6f && hnr > 0.3f ->
                HindiTtsService.Emotion.SIGHING

            // INTENSE & PHYSIOLOGICAL
            f0 > f0Mean * 1.12f && rmsNorm > 1.3f && f0Jitter > 0.10f && hnr < 0.5f ->
                HindiTtsService.Emotion.STRAINED
            f0 < f0Mean * 0.80f && hnr < 0.30f && f0Jitter > 0.10f ->
                HindiTtsService.Emotion.GRAVELLY
            hnr < 0.35f && rmsNorm > 1.0f && f0Jitter > 0.08f ->
                HindiTtsService.Emotion.RASPY
            hnr < 0.45f && rmsNorm in 0.7f..1.5f && f0Jitter in 0.05f..0.12f ->
                HindiTtsService.Emotion.HUSKY

            // BASIC HIGH-ENERGY
            f0Slope > 0.15f && rmsNorm > 0.8f ->
                HindiTtsService.Emotion.SURPRISED
            rmsNorm > 1.4f && hnr < 0.5f ->
                HindiTtsService.Emotion.ANGRY
            f0 > f0Mean * 1.05f && f0Jitter > 0.12f ->
                HindiTtsService.Emotion.FEARFUL

            // BREATHIVE & LOW-INTENSITY
            rmsNorm < 0.25f && hnr < 0.25f ->
                HindiTtsService.Emotion.WHISPERY
            f0 < f0Mean * 0.88f && rmsNorm < 0.4f && hnr < 0.4f ->
                HindiTtsService.Emotion.MURMURED
            rmsNorm < 0.35f && hnr < 0.40f && f0Jitter < 0.06f ->
                HindiTtsService.Emotion.HUSHED
            hnr < 0.40f && rmsNorm in 0.2f..0.8f && f0Jitter < 0.07f ->
                HindiTtsService.Emotion.BREATHY

            // WARM & AFFECTIONATE
            f0 < f0Mean * 0.92f && hnr > 0.65f && f0Jitter < 0.05f && rmsNorm < 0.9f ->
                HindiTtsService.Emotion.SULTRY
            rmsNorm < 0.45f && hnr > 0.60f && f0Jitter < 0.05f ->
                HindiTtsService.Emotion.TENDER
            f0 < f0Mean * 0.97f && hnr > 0.70f && f0Jitter < 0.04f ->
                HindiTtsService.Emotion.VELVETY
            hnr > 0.65f && f0Jitter < 0.05f && rmsNorm in 0.4f..1.1f ->
                HindiTtsService.Emotion.WARM

            // BASIC EMOTIONAL STATES
            f0 > f0Mean * 1.08f && f0Jitter < 0.08f && rmsNorm > 0.6f && hnr > 0.6f ->
                HindiTtsService.Emotion.HAPPY
            f0 < f0Mean * 0.93f && abs(f0Slope) < 0.05f && rmsNorm < 0.7f ->
                HindiTtsService.Emotion.SAD
            f0Slope < -0.10f && rmsNorm < 0.9f && hnr < 0.45f ->
                HindiTtsService.Emotion.DISGUST

            else -> HindiTtsService.Emotion.NEUTRAL
        }

        // Smooth over 7 frames — prevent rapid flicker
        emotionHistory.addLast(emotion)
        if (emotionHistory.size > 7) emotionHistory.removeFirst()
        val smoothed = emotionHistory.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: HindiTtsService.Emotion.NEUTRAL

        if (smoothed != detectedEmotion) {
            detectedEmotion = smoothed
            HindiTtsService.currentEmotion = smoothed
            CaptionLogger.log(TAG, "Emotion→${smoothed.name}[${smoothed.category}] " +
                "F0=${f0.toInt()}Hz slope=${"%.2f".format(f0Slope)} " +
                "jitter=${"%.3f".format(f0Jitter)} rms=${rmsNorm.format()} hnr=${hnr.format()} " +
                "spd=${smoothed.speedMult} pch=${smoothed.pitchMult}")
        }

        if (frameCount % 5 == 0)
            CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz → $gender | ${smoothed.name} " +
                "spd=${smoothed.speedMult} pch=${smoothed.pitchMult}")
    }

    private fun Float.format() = String.format("%.2f", this)
}
