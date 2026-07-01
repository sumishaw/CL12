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
 * GenderAnalyzer v8 — USAGE_MEDIA internal audio capture + YIN pitch detection.
 *
 * ONLY uses AudioPlaybackCaptureConfiguration(USAGE_MEDIA).
 * - Captures the media player digital audio stream directly from Android's audio mixer
 * - Works with speakers AND headphones (capture happens before output device)
 * - Zero mic usage — no ambient noise, no room acoustics
 * - Hindi TTS (USAGE_ASSISTANT) is excluded at the OS level — never analyzed
 *
 * Requires: MediaProjection from SpeechCaptureService.sharedProjection
 * The projection is obtained via the normal screen capture permission dialog
 * which SpeechCaptureService already handles.
 */
object GenderAnalyzer {

    private const val TAG          = "GenderAnalyzer"
    private const val SR           = 16_000
    private const val WIN          = 2048        // 128ms window
    private const val F0_FEMALE    = 165f        // Hz — above = female
    private const val YIN_THRESH   = 0.25f       // stricter threshold for clean internal audio
    private const val RMS_FLOOR    = 80f         // out of 32768 — skip silence

    // STABILITY FIX: Increased from 3 → 12 frames (was switching on 2/3 frames = ~256ms)
    // Now needs 8/12 consecutive frames to switch = ~768ms of sustained pitch change
    // Prevents rapid MALE↔FEMALE oscillation that caused spokenTokens.clear() spam → skipped sentences
    private const val HIST         = 12          // ~768ms majority window (was 3 = ~192ms)

    // Minimum milliseconds between gender switches — prevents double-switching within one sentence
    // Even with HIST=12, rapid pitch variation (music, noise) can still flip quickly
    private const val MIN_SWITCH_INTERVAL_MS = 3_000L  // at most one switch per 3 seconds

    @Volatile var enabled    = false
    @Volatile var lastStatus = "waiting for screen capture permission"
    @Volatile var lastBgSeq  = 0   // incremented each time BG audio is sent to tts_server

    private val history   = ArrayDeque<HindiTtsService.Gender>()
    private val accum     = ShortArray(WIN)
    private var accumFill = 0

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job?         = null
    private var captureRec: AudioRecord? = null

    private var frameCount   = 0
    private var analyzeCount = 0
    private var lastSwitchMs = 0L   // timestamp of last gender switch for rate limiting

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start internal audio capture for gender detection.
     * Must be called with a valid MediaProjection from SpeechCaptureService.
     */
    fun start(projection: MediaProjection? = null, context: android.content.Context? = null) {
        if (enabled) {
            CaptionLogger.log(TAG, "already running — skipping start()")
            return
        }
        if (projection == null) {
            lastStatus = "no projection — grant screen capture permission"
            CaptionLogger.log(TAG, "start() called but projection=null — need screen capture permission")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastStatus = "API < Q — AudioPlaybackCapture not supported"
            CaptionLogger.log(TAG, "API < Q — cannot use AudioPlaybackCaptureConfiguration")
            return
        }
        stop()
        CaptionLogger.log(TAG, "start() — creating USAGE_MEDIA AudioPlaybackCaptureConfiguration")
        captureJob = scope.launch { captureLoop(projection) }
    }

    fun stop() {
        enabled = false
        captureJob?.cancel(); captureJob = null
        try { captureRec?.stop()    } catch (_: Exception) {}
        try { captureRec?.release() } catch (_: Exception) {}
        captureRec = null
        history.clear()
        accumFill = 0
        voiceTypeF0History.clear()
        voiceTypeFrameCount = 0
        HindiTtsService.currentVoiceType = HindiTtsService.VoiceType.UNKNOWN
        HindiTtsService.currentMeasuredF0 = 0f
        if (lastStatus != "waiting for screen capture permission")
            CaptionLogger.log(TAG, "stopped")
    }

    // ── USAGE_MEDIA capture loop ──────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLoop(projection: MediaProjection) = withContext(Dispatchers.IO) {
        // USAGE_MEDIA = video/music player stream ONLY
        // USAGE_ASSISTANT (Hindi TTS) is NOT listed → excluded at OS audio mixer level
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        } catch (e: Exception) {
            enabled = false
            lastStatus = "capture config failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioPlaybackCaptureConfiguration failed: ${e.message}")
            return@withContext
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
            enabled = false
            lastStatus = "AudioRecord failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioRecord.Builder failed: ${e.message}")
            return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            enabled = false
            rec.release()
            lastStatus = "AudioRecord state=${rec.state} — not initialized"
            CaptionLogger.log(TAG, "AudioRecord not initialized state=${rec.state}")
            return@withContext
        }

        captureRec = rec
        enabled    = true
        frameCount = 0; analyzeCount = 0
        lastStatus = "capturing USAGE_MEDIA SR=${SR}Hz"
        rec.startRecording()
        CaptionLogger.log(TAG, ">>> INTERNAL AUDIO CAPTURE STARTED SR=${SR}Hz <<<")

        val buf = ByteArray(WIN * 2)
        var readCount = 0
        try {
            while (currentCoroutineContext().isActive && enabled) {
                val n = rec.read(buf, 0, buf.size)
                when {
                    n > 0 -> {
                        readCount++
                        if (readCount == 1)
                            CaptionLogger.log(TAG, "FIRST internal audio read: $n bytes — media audio flowing!")
                        ingest(buf, n)
                    }
                    n < 0 -> {
                        CaptionLogger.log(TAG, "read error=$n — stopping")
                        break
                    }
                }
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            captureRec = null
            enabled = false
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

    // ── YIN pitch detection ───────────────────────────────────────────────────

    private fun analyze() {
        analyzeCount++

        // RMS — skip silence
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) {
            if (analyzeCount % 30 == 0)
                CaptionLogger.log(TAG, "silent rms=${rms.toInt()} floor=$RMS_FLOOR analyzed=$analyzeCount")
            return
        }

        val tauMin = (SR / 300).coerceAtLeast(1)
        val tauMax = (SR / 60).coerceAtMost(WIN / 2 - 1)
        val half   = WIN / 2

        // YIN difference function — raw PCM, no windowing
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var s = 0f
            for (j in 0 until half) {
                val diff = accum[j].toFloat() / 32768f - accum[j + tau].toFloat() / 32768f
                s += diff * diff
            }
            d[tau] = s
        }

        // CMNDF
        val c = FloatArray(tauMax + 1); c[0] = 1f
        var rs = 0f
        for (tau in 1..tauMax) {
            rs += d[tau]
            c[tau] = if (rs > 0f) d[tau] * tau / rs else 1f
        }

        // First dip below threshold
        var tau = tauMin
        while (tau < tauMax - 1) {
            if (c[tau] < YIN_THRESH) {
                val best = if (tau + 1 < tauMax && c[tau + 1] < c[tau]) tau + 1 else tau
                onPitch(SR.toFloat() / best, rms)
                return
            }
            tau++
        }

        if (analyzeCount % 15 == 0) {
            var mv = 1f; var mt = tauMin
            for (t in tauMin until tauMax) if (c[t] < mv) { mv = c[t]; mt = t }
            CaptionLogger.log(TAG, "noPitch rms=${rms.toInt()} minCMNDF=${"%.3f".format(mv)} f0est=${SR/mt}Hz thr=$YIN_THRESH")
        }
    }

    // ── Emotion detection state ───────────────────────────────────────────────
    private val f0History   = ArrayDeque<Float>(16)   // recent F0 values for variation
    private val rmsHistory  = ArrayDeque<Float>(16)   // recent RMS for energy analysis
    private var prevF0      = 0f
    private var risingFrames = 0
    private var fallingFrames = 0
    private var highEnergyFrames = 0
    private var sustainedHighF0Frames = 0
    private var emotionFrameCount = 0

    // ── F0 Contour tracking (for SSML prosody mirroring) ─────────────────────
    // We track F0 at 3 key points in each sentence window:
    //   startF0: average of first 8 frames (how the speaker begins the phrase)
    //   peakF0:  highest sustained F0 in the window (where energy/stress peaks)
    //   endF0:   average of last 8 frames (how the phrase ends — falling/rising)
    // These 3 points define the pitch CONTOUR of the original utterance, which
    // we then map to SSML <prosody pitch> tags so the Hindi TTS follows the
    // same intonation shape — rising, falling, peaked, or flat.
    private val contourBuffer  = ArrayDeque<Float>(80)  // ~10s of F0 frames
    private var contourPeak    = 0f
    @Volatile var lastContourStartF0: Float = 0f   // read by HindiTtsService
    @Volatile var lastContourPeakF0:  Float = 0f
    @Volatile var lastContourEndF0:   Float = 0f

    // ── Voice Type classification state ──────────────────────────────────────
    // Wider rolling window than emotion detection (5s vs 640ms) — voice type is
    // a STABLE characteristic of the speaker, not a moment-to-moment emotional cue.
    // Classified separately per gender so a switch to a new speaker re-classifies cleanly.
    private val voiceTypeF0History = ArrayDeque<Float>(40)  // ~5s at 8 samples/sec (frame%5==0 sampling)
    private var voiceTypeFrameCount = 0
    private var lastVoiceTypeSwitchMs = 0L
    private const val VOICE_TYPE_MIN_SWITCH_MS = 4_000L  // stable — don't flip-flop on noise

    private fun onPitch(f0: Float, rms: Float) {
        frameCount++
        val gender = if (f0 >= F0_FEMALE) HindiTtsService.Gender.FEMALE
                     else                  HindiTtsService.Gender.MALE

        if (frameCount % 5 == 0)
            CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz rms=${rms.toInt()} → $gender")

        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()

        val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
        val maj    = if (fCount > history.size * 2 / 3) HindiTtsService.Gender.FEMALE
                     else if (fCount < history.size / 3)   HindiTtsService.Gender.MALE
                     else                                   HindiTtsService.detectedGender // no majority → keep current

        val nowMs = System.currentTimeMillis()
        if (maj != HindiTtsService.detectedGender &&
            nowMs - lastSwitchMs >= MIN_SWITCH_INTERVAL_MS) {
            lastSwitchMs = nowMs
            HindiTtsService.detectedGender = maj
            // Don't clear spokenTokens on switch — sentences in flight stay valid
            // Only clear if user explicitly wants re-speak (e.g. via stopAndClear)
            lastStatus = "MEDIA audio → $maj (F0=${f0.toInt()}Hz)"
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $maj F0=${f0.toInt()}Hz <<<")
            Log.d(TAG, "Gender→$maj F0=${f0.toInt()} rms=${rms.toInt()}")
            // New speaker detected (gender flip) — reset voice type window so we
            // don't blend the old speaker's pitch average into the new one.
            voiceTypeF0History.clear()
            HindiTtsService.currentMeasuredF0 = 0f
        }

        // ── Voice Type classification ─────────────────────────────────────────
        // ── F0 Contour: accumulate for start/peak/end detection ────────────
        if (f0 > 0f) {
            contourBuffer.addLast(f0)
            if (contourBuffer.size > 80) contourBuffer.removeFirst()
            if (f0 > contourPeak) contourPeak = f0
        }

        // ── Voice Type classification (wider rolling window) ──────────────────
        // Wider rolling window (~5s) of F0 samples → classify into one of 6
        // standard voice types (Soprano/Mezzo/Contralto/Countertenor/Tenor/
        // Baritone/Bass) based on the SPEAKER'S average speaking pitch.
        // Updates at most once per 4s to stay stable (a voice type shouldn't
        // flicker the way emotion or even gender can).
        voiceTypeF0History.addLast(f0)
        if (voiceTypeF0History.size > 40) voiceTypeF0History.removeFirst()
        voiceTypeFrameCount++

        // EXACT MIRRORING: update the continuous measured F0 every cycle (not
        // gated by the 4s stability window above) — this is what HindiTtsService
        // actually uses for pitch calculation. The rolling average smooths out
        // frame noise while still tracking the speaker's real pitch closely.
        if (voiceTypeF0History.size >= 8) {
            HindiTtsService.currentMeasuredF0 = voiceTypeF0History.average().toFloat()
        }

        if (voiceTypeFrameCount >= 20 && voiceTypeF0History.size >= 15) {
            voiceTypeFrameCount = 0
            val avgF0 = voiceTypeF0History.average().toFloat()
            val classified = classifyVoiceType(avgF0, maj)
            if (classified != HindiTtsService.currentVoiceType &&
                nowMs - lastVoiceTypeSwitchMs >= VOICE_TYPE_MIN_SWITCH_MS) {
                lastVoiceTypeSwitchMs = nowMs
                HindiTtsService.currentVoiceType = classified
                val isFemaleNow = maj == HindiTtsService.Gender.FEMALE
                val exactRatio = HindiTtsService.exactPitchRatio(avgF0, isFemaleNow)
                CaptionLogger.log(TAG, "VOICE-TYPE → $classified avgF0=${avgF0.toInt()}Hz " +
                    "exactPitchRatio=${String.format("%.2f", exactRatio)}")
            }
        }

        // ── FIX BUG 3: Emotion detection from acoustic features ──────────────
        // Track F0 contour and energy to detect emotion every 10 frames (~640ms)
        f0History.addLast(f0)
        rmsHistory.addLast(rms)
        if (f0History.size > 12) f0History.removeFirst()
        if (rmsHistory.size > 12) rmsHistory.removeFirst()

        // F0 slope: rising = excited/happy/surprised, falling = sad/sighing
        val f0Slope = if (prevF0 > 0) f0 - prevF0 else 0f
        prevF0 = f0
        if (f0Slope > 5f) risingFrames++ else if (f0Slope < -5f) fallingFrames++

        // High energy = excited/angry
        val baseRms = rmsHistory.average().toFloat()
        if (rms > baseRms * 1.5f) highEnergyFrames++

        // Sustained high F0 = singing or excited
        if (f0 > 250f) sustainedHighF0Frames++ else sustainedHighF0Frames = 0

        emotionFrameCount++

        if (emotionFrameCount >= 10) {
            emotionFrameCount = 0
            val detectedEmotion = detectEmotionFromAcoustics(
                f0History.toList(), rmsHistory.toList(),
                risingFrames, fallingFrames, highEnergyFrames, sustainedHighF0Frames, f0
            )
            if (detectedEmotion != HindiTtsService.currentEmotion) {
                HindiTtsService.currentEmotion = detectedEmotion
                CaptionLogger.log(TAG, "EMO→$detectedEmotion F0=${f0.toInt()} slope=${f0Slope.toInt()}")
            }
            risingFrames = 0; fallingFrames = 0; highEnergyFrames = 0
        }
    }

    // ── F0 Contour Extractor ─────────────────────────────────────────────────
    // Called by Livecaptionreader when a sentence is about to be enqueued for
    // translation. Extracts the 3-point pitch contour from the accumulated F0
    // buffer and writes it to HindiTtsService for SSML generation.
    fun flushContour() {
        val buf = contourBuffer.toList().filter { it > 0f }
        if (buf.size < 10) return
        val n = buf.size
        val startF0 = buf.take(minOf(8, n/4)).average().toFloat()
        val endF0   = buf.takeLast(minOf(8, n/4)).average().toFloat()
        val peakF0  = contourPeak.takeIf { it > 0f } ?: buf.max()
        lastContourStartF0 = startF0
        lastContourPeakF0  = peakF0
        lastContourEndF0   = endF0
        HindiTtsService.capturedStartF0 = startF0
        HindiTtsService.capturedPeakF0  = peakF0
        HindiTtsService.capturedEndF0   = endF0
        CaptionLogger.log("GenderAnalyzer",
            "CONTOUR start=${startF0.toInt()} peak=${peakF0.toInt()} end=${endF0.toInt()}Hz " +
            if (endF0 > startF0 * 1.05f) "↑RISING" else if (endF0 < startF0 * 0.95f) "↓FALLING" else "→FLAT")
        // Reset for next sentence
        contourBuffer.clear()
        contourPeak = 0f
    }

    // ── Voice Type Classifier ────────────────────────────────────────────────
    // Maps average SPEAKING F0 (not singing range) to one of 6 standard voice
    // types, gated by the already-detected gender (avoids classifying a male
    // bass voice as a female contralto just because ranges can theoretically
    // overlap at the boundary).
    //
    // Speaking F0 reference (much narrower than full singing range — people
    // speak in the bottom third of their total vocal range, in modal/chest voice):
    //   Female: Soprano ~220-320Hz | Mezzo ~180-220Hz | Contralto ~140-180Hz
    //   Male:   Countertenor ~140-180Hz (rare, falsetto-adjacent)
    //           Tenor ~120-140Hz | Baritone ~90-120Hz | Bass ~65-90Hz
    private fun classifyVoiceType(avgF0: Float, gender: HindiTtsService.Gender): HindiTtsService.VoiceType {
        return if (gender == HindiTtsService.Gender.FEMALE) {
            when {
                avgF0 >= 220f -> HindiTtsService.VoiceType.SOPRANO
                avgF0 >= 180f -> HindiTtsService.VoiceType.MEZZO_SOPRANO
                else          -> HindiTtsService.VoiceType.CONTRALTO
            }
        } else {
            when {
                avgF0 >= 140f -> HindiTtsService.VoiceType.COUNTERTENOR  // rare high male
                avgF0 >= 120f -> HindiTtsService.VoiceType.TENOR
                avgF0 >= 90f  -> HindiTtsService.VoiceType.BARITONE
                else          -> HindiTtsService.VoiceType.BASS
            }
        }
    }

    private fun detectEmotionFromAcoustics(
        f0s: List<Float>, rmss: List<Float>,
        rising: Int, falling: Int, highEnergy: Int,
        sustainedHigh: Int, currentF0: Float
    ): HindiTtsService.Emotion {
        if (f0s.isEmpty() || rmss.isEmpty()) return HindiTtsService.Emotion.NEUTRAL

        val f0Mean  = f0s.average().toFloat()
        val f0Std   = f0s.map { (it - f0Mean) * (it - f0Mean) }.average()
                          .let { Math.sqrt(it).toFloat() }
        val rmsMean = rmss.average().toFloat()
        // FIX: rmsStd was calling itself recursively and returning mean not std
        val rmsMeanVal = rmsMean
        val rmsStdVal  = rmss.map { (it - rmsMeanVal) * (it - rmsMeanVal) }.average()
                             .let { Math.sqrt(it).toFloat() }

        // Singing: sustained high F0 (>250Hz), stable pitch (low std)
        if (sustainedHigh >= 5 && f0Std < 30f) return HindiTtsService.Emotion.SINGING

        // Excited: rising pitch + high energy
        if (rising >= 4 && highEnergy >= 3 && f0Mean > 190f)
            return HindiTtsService.Emotion.EXCITED

        // Happy: rising pitch, moderate energy (loosened: was rising>=3, f0Mean>170 — too strict
        // for normal upbeat speech that doesn't reach excited-level highs)
        if (rising >= 2 && f0Mean > 150f && highEnergy >= 1)
            return HindiTtsService.Emotion.HAPPY

        // Surprised: sudden high F0 spike with high variation
        if (currentF0 > 260f && f0Std > 35f)
            return HindiTtsService.Emotion.SURPRISED

        // Fearful: high variable pitch, not high energy
        if (f0Mean > 210f && f0Std > 25f && highEnergy < 3)
            return HindiTtsService.Emotion.FEARFUL

        // Angry: high energy + variable pitch
        if (highEnergy >= 4 && f0Std > 20f && rmsMean > 1500f)
            return HindiTtsService.Emotion.ANGRY

        // Sighing: falling pitch, low energy
        if (falling >= 5 && rmsMean < 1000f)
            return HindiTtsService.Emotion.SIGHING

        // Sad: falling pitch, low F0 overall
        if (falling >= 3 && f0Mean < 140f && rmsMean < 1200f)
            return HindiTtsService.Emotion.SAD

        // Warm: stable smooth mid F0, calm (loosened: was f0Std<20, narrow 110-185Hz band
        // — widened so normal calm/engaged conversational speech qualifies more often
        // instead of defaulting to flat NEUTRAL)
        if (f0Std < 28f && f0Mean in 95f..210f && highEnergy < 3)
            return HindiTtsService.Emotion.WARM

        // Whispery: very low RMS (quiet speech)
        if (rmsMean < 400f && f0Std < 25f)
            return HindiTtsService.Emotion.WHISPERY

        return HindiTtsService.Emotion.NEUTRAL
    }

    private fun rmsStd(rmss: List<Float>): Float = rmss.average().toFloat()
}
