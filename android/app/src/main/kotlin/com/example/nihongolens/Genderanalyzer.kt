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
    private const val HIST         = 77          // ~768ms majority window at 20ms cadence (was 12 @ 128ms cadence)

    // Minimum milliseconds between gender switches — prevents double-switching within one sentence
    // Even with HIST=12, rapid pitch variation (music, noise) can still flip quickly
    // Raised from 3s → 12s: prevents same speaker's voice flipping during
    // brief music/silence gaps between sentences (music F0 reads as MALE)
    private const val MIN_SWITCH_INTERVAL_MS = 2_000L   // fast gender switch (male↔female)

    @Volatile var enabled    = false
    @Volatile var lastStatus = "waiting for screen capture permission"
    @Volatile var lastBgSeq  = 0   // incremented each time BG audio is sent to tts_server

    private val history   = ArrayDeque<HindiTtsService.Gender>()
    private val accum     = ShortArray(WIN)
    private var accumFill = 0

    // ── Sliding-window pitch tracking (Voice Mimic Engine upgrade) ───────────
    // OLD: analyze() ran once every WIN=2048 samples (128ms), non-overlapping.
    // NEW: same WIN-sample analysis window (needed for reliable low-pitch
    // detection — a shorter window can't capture even one full period of a
    // low male voice), but re-run every HOP_SAMPLES instead of every WIN
    // samples — i.e. overlapping windows, bulk-shifted rather than reset.
    //
    // WHY THIS MATTERS: vibrato oscillates at 4-8Hz — a full cycle every
    // 125-250ms. At the OLD 128ms sampling rate there's barely one F0 sample
    // per vibrato cycle — nowhere near enough to detect oscillation at all
    // (Nyquist: you need at least ~2 samples per cycle just to see it exists,
    // and several more to characterize its rate/depth reliably). At HOP_MS
    // resolution below there are enough samples per cycle for real detection.
    //
    // COST WARNING — read before lowering HOP_MS further: the YIN pitch
    // detector is O(WIN * tauMax) per call, roughly 270K float ops. At the
    // old 128ms cadence that's ~2.1M ops/sec; at HOP_MS=20ms it's ~13.4M
    // ops/sec — about 6.4x more CPU spent on pitch detection alone, on top
    // of Whisper transcription + CT2 translation + TTS synthesis already
    // running on a 2-big-core chip. 20ms was chosen (not the requested 10ms)
    // as a middle ground that captures most of the vibrato-detection benefit
    // without a full ~13x cost increase. This needs to be verified against
    // real battery/thermal behavior on-device — that can't be measured from
    // here. Lower HOP_MS only after confirming it doesn't cause throttling
    // or dropped frames elsewhere in the pipeline.
    private const val HOP_MS      = 20
    private val HOP_SAMPLES       = (SR * HOP_MS / 1000).coerceAtLeast(1)
    private val hopStage          = ShortArray(HOP_SAMPLES)
    private var hopFill           = 0

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
            val sample = ((hi shl 8) or lo).toShort()
            i += 2

            if (accumFill < WIN) {
                // Filling the very first window — identical to old behavior
                accum[accumFill++] = sample
                if (accumFill == WIN) analyze()
            } else {
                // Sliding: stage HOP_SAMPLES of new audio, then bulk-shift
                // the window forward by exactly that much and re-analyze.
                hopStage[hopFill++] = sample
                if (hopFill >= HOP_SAMPLES) {
                    System.arraycopy(accum, HOP_SAMPLES, accum, 0, WIN - HOP_SAMPLES)
                    System.arraycopy(hopStage, 0, accum, WIN - HOP_SAMPLES, HOP_SAMPLES)
                    hopFill = 0
                    analyze()
                }
            }
        }
    }

    // ── YIN pitch detection ───────────────────────────────────────────────────

    // ── Breath detection ──────────────────────────────────────────────────────
    // Counts likely breath pauses: a gap of low-RMS "silence" between two
    // voiced stretches, long enough to be a breath rather than a normal
    // micro-pause in speech. Consumed at flushContour() and reset per
    // sentence. NOTE: this tells HindiTtsService "N breaths happened
    // somewhere in this sentence" — it does NOT tell it exactly where in
    // the (differently-worded, differently-timed) Hindi translation they
    // should land, since translated word count/order doesn't correspond
    // 1:1 to the original audio's timing. The best honest use of this is
    // distributing that many short pauses roughly evenly through the
    // translated sentence, not precise alignment.
    private var silentHopStreak         = 0
    private var breathCountThisSentence = 0
    private const val BREATH_MIN_SILENT_HOPS = 8  // 8 * 20ms = ~160ms minimum gap to count as a breath, not just a micro-pause

    private fun analyze() {
        analyzeCount++

        // RMS — skip silence
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) {
            silentHopStreak++
            // Throttled to ~3.84s real-world rate (was "% 30" at the old 128ms cadence; now 192 at 20ms keeps the same interval)
            if (analyzeCount % 192 == 0)
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
                // A voiced frame just resolved a preceding silent streak —
                // if that gap was long enough, count it as a breath.
                if (silentHopStreak >= BREATH_MIN_SILENT_HOPS) breathCountThisSentence++
                silentHopStreak = 0
                onPitch(SR.toFloat() / best, rms)
                return
            }
            tau++
        }

        // Throttled to ~1.92s real-world rate (was "% 15" at the old 128ms cadence; now 96 at 20ms keeps the same interval)
        if (analyzeCount % 96 == 0) {
            var mv = 1f; var mt = tauMin
            for (t in tauMin until tauMax) if (c[t] < mv) { mv = c[t]; mt = t }
            CaptionLogger.log(TAG, "noPitch rms=${rms.toInt()} minCMNDF=${"%.3f".format(mv)} f0est=${SR/mt}Hz thr=$YIN_THRESH")
        }
    }

    // ── Emotion detection state ───────────────────────────────────────────────
    private val f0History   = ArrayDeque<Float>(77)   // recent F0 values for variation (was 16 @ 128ms cadence)
    private val rmsHistory  = ArrayDeque<Float>(77)   // recent RMS for energy analysis (same rescale)
    // prevF0 removed — slope is now computed via a fixed time-lag lookback
    // into f0History (see below), not a single-previous-sample comparison
    private var risingFrames = 0
    private var fallingFrames = 0
    private var highEnergyFrames = 0
    private var sustainedHighF0Frames = 0
    private var emotionFrameCount = 0

    // ── Multi-point F0+RMS contour tracking ──────────────────────────────────
    // Captures 10 evenly-spaced F0 AND RMS samples across the sentence window.
    // F0 samples → per-word pitch variation (not just 3-point start/peak/end)
    // RMS samples → per-word duration estimation (loud=stressed=longer)
    // Both are written to HindiTtsService at sentence flush time.
    //
    // 10 points gives ~10x pitch resolution vs the old 3-point approach —
    // each word maps to its own measured F0 and energy level from the original.
    private var latestPcmFrame: ShortArray? = null  // latest raw PCM for VoiceAnalyzer

    private val contourF0Buffer  = ArrayDeque<Float>(768) // raw F0 frames per sentence (was 120 @ 128ms cadence; ~15s coverage preserved at 20ms)
    private val contourRmsBuffer = ArrayDeque<Float>(768) // raw RMS frames per sentence (same rescale)
    private var contourPeak      = 0f

    // Published to HindiTtsService at flushContour()
    // 10-point curves sampled from the sentence window
    // Raised from 10 to 30 (Voice Mimic Engine upgrade) — finer per-word
    // melody resolution. MUST match HindiTtsService.capturedF0Curve/
    // capturedRmsCurve sizes exactly, since flushContour() copies directly
    // into them via copyInto() — a size mismatch would throw at runtime.
    const val CONTOUR_POINTS = 30
    val capturedF0Curve  = FloatArray(CONTOUR_POINTS)   // F0 at CONTOUR_POINTS time positions
    val capturedRmsCurve = FloatArray(CONTOUR_POINTS)   // RMS at CONTOUR_POINTS time positions (for duration)
    // Legacy 3-point fields kept for backward compat
    @Volatile var lastContourStartF0: Float = 0f
    @Volatile var lastContourPeakF0:  Float = 0f
    @Volatile var lastContourEndF0:   Float = 0f

    // ── Voice Type classification state ──────────────────────────────────────
    // Wider rolling window than emotion detection (5s vs 640ms) — voice type is
    // a STABLE characteristic of the speaker, not a moment-to-moment emotional cue.
    // Classified separately per gender so a switch to a new speaker re-classifies cleanly.
    private val voiceTypeF0History = ArrayDeque<Float>(256)  // ~5s at 20ms cadence (was 40 @ 128ms/~8 samples-per-sec)
    private var voiceTypeFrameCount = 0
    private var lastVoiceTypeSwitchMs = 0L
    // 15s: voice type (Soprano/Tenor/Bass etc.) is a stable speaker characteristic
    // Should only change when speaker genuinely changes, not on momentary noise
    private const val VOICE_TYPE_MIN_SWITCH_MS = 15_000L

    private fun onPitch(f0: Float, rms: Float) {
        frameCount++
        val gender = if (f0 >= F0_FEMALE) HindiTtsService.Gender.FEMALE
                     else                  HindiTtsService.Gender.MALE

        if (frameCount % 32 == 0)  // was % 5 @ 128ms cadence; 32 @ 20ms keeps the same ~640ms interval
            CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz rms=${rms.toInt()} → $gender")

        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()

        val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
        val maj    = if (fCount > history.size * 2 / 3) HindiTtsService.Gender.FEMALE
                     else if (fCount < history.size / 3)   HindiTtsService.Gender.MALE
                     else                                   HindiTtsService.detectedGender // no majority → keep current

        val nowMs = System.currentTimeMillis()
        // Extra guard: don't switch gender if RMS indicates silence/music
        // Low RMS = speaker has stopped talking — pitch reading is noise
        val isSilenceOrMusic = rms < 120f
        if (maj != HindiTtsService.detectedGender &&
            nowMs - lastSwitchMs >= MIN_SWITCH_INTERVAL_MS &&
            !isSilenceOrMusic) {
            lastSwitchMs = nowMs
            HindiTtsService.detectedGender = maj
            // Don't clear spokenTokens on switch — sentences in flight stay valid
            // Only clear if user explicitly wants re-speak (e.g. via stopAndClear)
            lastStatus = "MEDIA audio → $maj (F0=${f0.toInt()}Hz)"
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $maj F0=${f0.toInt()}Hz <<<")
            Log.d(TAG, "Gender→$maj F0=${f0.toInt()} rms=${rms.toInt()}")
            // New speaker confirmed — switch voice, seed new gender's F0 buffer
            voiceTypeF0History.clear()
            // Seed the new gender's F0 buffer with current measurement
            // rather than clearing to zero (which causes pitch=1.0 for 1-2 sentences)
            HindiTtsService.currentMeasuredF0 = f0.takeIf { it > 60f } ?: 0f
            HindiTtsService.seedSentenceF0(f0, maj)  // smooth gender transition
            // Reset Hindi dedup so new speaker's first sentence is never skipped
            LiveCaptionReader.instance?.resetHindiDedup()
        }

        // ── Voice Type classification ─────────────────────────────────────────
        // ── Multi-point contour: accumulate F0 and RMS per voiced frame ────────
        if (f0 > 0f && rms >= 150f) {
            contourF0Buffer.addLast(f0)
            contourRmsBuffer.addLast(rms)
            if (contourF0Buffer.size > 768) { contourF0Buffer.removeFirst(); contourRmsBuffer.removeFirst() }
            if (f0 > contourPeak) contourPeak = f0
        }

        // ── Voice Type classification (wider rolling window) ──────────────────
        // Wider rolling window (~5s) of F0 samples → classify into one of 6
        // standard voice types (Soprano/Mezzo/Contralto/Countertenor/Tenor/
        // Baritone/Bass) based on the SPEAKER'S average speaking pitch.
        // Updates at most once per 4s to stay stable (a voice type shouldn't
        // flicker the way emotion or even gender can).
        voiceTypeF0History.addLast(f0)
        if (voiceTypeF0History.size > 256) voiceTypeF0History.removeFirst()
        voiceTypeFrameCount++

        // EXACT MIRRORING: update measured F0 only from VOICED SPEECH frames
        // RMS floor: frames below this are silence/music noise (not a real speaker)
        // This prevents music/silence from contaminating the F0 average and
        // causing the same speaker to sound like a different person between sentences
        val SPEECH_RMS_FLOOR = 180f  // below this = not voiced speech
        if (voiceTypeF0History.size >= 51 && rms >= SPEECH_RMS_FLOOR) {  // was >= 8 @ 128ms cadence
            val voicedOnly = voiceTypeF0History.filter { it > 80f }
            if (voicedOnly.size >= 25) {  // was >= 4 @ 128ms cadence
                val avgF0 = voicedOnly.average().toFloat()
                HindiTtsService.currentMeasuredF0 = avgF0
                // Feed voiced-speech F0 into EMA — keeps same speaker's pitch stable
                // across all their sentences without wild jumps from music/silence frames
                HindiTtsService.addF0Frame(avgF0, maj)  // feeds per-sentence median buffer
            }
        }

        // Feed VoiceAnalyzer with current frame metrics for full vocal profile
        if (latestPcmFrame != null) {
            VoiceAnalyzer.processFrame(latestPcmFrame!!, rms, f0)
        }

        if (voiceTypeFrameCount >= 128 && voiceTypeF0History.size >= 96) {  // was >= 20 / >= 15 @ 128ms cadence
            voiceTypeFrameCount = 0
            val avgF0 = voiceTypeF0History.average().toFloat()
            val classified = classifyVoiceType(avgF0, maj)
            if (classified != HindiTtsService.currentVoiceType &&
                nowMs - lastVoiceTypeSwitchMs >= VOICE_TYPE_MIN_SWITCH_MS &&
                rms >= 180f) {  // only switch voice type during actual speech
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
        if (f0History.size > 77) f0History.removeFirst()   // was > 12 @ 128ms cadence
        if (rmsHistory.size > 77) rmsHistory.removeFirst()  // same rescale

        // F0 slope: rising = excited/happy/surprised, falling = sad/sighing
        // Compares against ~140ms ago (7 samples at the new 20ms cadence),
        // NOT the immediately-previous sample — at 20ms spacing, adjacent
        // samples are naturally close in value, so comparing only to the
        // last one would make this dominated by short-term measurement
        // jitter rather than genuine directional pitch movement. 7 samples
        // back approximates the ~128ms gap the original >5Hz threshold was
        // tuned for (this comparison used to implicitly be ~1 sample apart
        // at the old 128ms cadence).
        val slopeLagSamples = 7
        val f0Slope = if (f0History.size > slopeLagSamples)
            f0 - f0History.elementAt(f0History.size - 1 - slopeLagSamples)
        else 0f
        if (f0Slope > 5f) risingFrames++ else if (f0Slope < -5f) fallingFrames++

        // High energy = excited/angry
        val baseRms = rmsHistory.average().toFloat()
        if (rms > baseRms * 1.5f) highEnergyFrames++

        // Sustained high F0 = singing or excited
        if (f0 > 250f) sustainedHighF0Frames++ else sustainedHighF0Frames = 0

        emotionFrameCount++

        if (emotionFrameCount >= 64) {  // was >= 10 @ 128ms cadence; 64 @ 20ms keeps the same ~1.28s interval
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
        val f0Buf  = contourF0Buffer.toList()
        val rmsBuf = contourRmsBuffer.toList()
        if (f0Buf.size < 6) return
        val n = f0Buf.size

        // Sample CONTOUR_POINTS evenly-spaced points from the sentence window.
        // Raised from 10 to 30 (Voice Mimic Engine upgrade) — finer melody
        // resolution for buildMelodyContour() in HindiTtsService. Still bounded
        // to a fixed size (not literally per-10ms) because the actual ceiling
        // downstream is Android TTS's per-WORD SSML granularity — sampling far
        // beyond what a typical sentence's word count can use doesn't add
        // audible value, it just costs more to compute and store.
        for (i in 0 until CONTOUR_POINTS) {
            val lo = (i * n / CONTOUR_POINTS)
            val hi = ((i + 1) * n / CONTOUR_POINTS).coerceAtMost(n)
            if (lo < hi) {
                capturedF0Curve[i]  = f0Buf.subList(lo, hi).average().toFloat()
                capturedRmsCurve[i] = rmsBuf.subList(lo, hi).average().toFloat()
            } else {
                capturedF0Curve[i]  = if (i > 0) capturedF0Curve[i-1] else (f0Buf.average().toFloat())
                capturedRmsCurve[i] = if (i > 0) capturedRmsCurve[i-1] else (rmsBuf.average().toFloat())
            }
        }

        // ── Vibrato detection ────────────────────────────────────────────────
        // Vibrato = periodic pitch oscillation, typically 4-8Hz, common in
        // singing and some expressive speech. Detected by counting how often
        // the raw F0 curve crosses its own short-term moving average — a
        // genuine oscillation crosses at a regular rate; random measurement
        // jitter does not. Needs the 20ms sliding-window upgrade above to
        // work at all — at the old 128ms sampling there's barely one sample
        // per vibrato cycle, nowhere near enough to detect the oscillation.
        //
        // HONEST LIMIT: this DETECTS vibrato in the original voice. Android
        // TTS has no pitch-oscillation control whatsoever, so REPRODUCING
        // true vibrato isn't possible on this platform — HindiTtsService can
        // only apply a coarse per-word pitch wobble as an approximation, not
        // real vibrato.
        var vibratoDetected = false
        var vibratoRateHz   = 0f
        if (n >= 20) {
            val smoothWindow = 5
            val baseline = FloatArray(n)
            for (i in 0 until n) {
                val lo = (i - smoothWindow / 2).coerceAtLeast(0)
                val hi = (i + smoothWindow / 2).coerceAtMost(n - 1)
                baseline[i] = f0Buf.subList(lo, hi + 1).average().toFloat()
            }
            var crossings = 0
            var totalDeviation = 0f
            for (i in 1 until n) {
                val prevDev = f0Buf[i - 1] - baseline[i - 1]
                val currDev = f0Buf[i] - baseline[i]
                totalDeviation += kotlin.math.abs(currDev)
                if ((prevDev > 0f) != (currDev > 0f)) crossings++
            }
            val durationSec       = n * HOP_MS / 1000f
            val avgDeviation       = totalDeviation / n
            val avgF0              = f0Buf.average().toFloat()
            val relativeDeviation  = if (avgF0 > 0f) avgDeviation / avgF0 else 0f
            // Each full oscillation cycle crosses the baseline twice
            vibratoRateHz = if (durationSec > 0f) (crossings / 2f) / durationSec else 0f
            // 3.5-9Hz covers natural vibrato rate variation; the relative-
            // deviation floor filters out flat/monotone speech whose zero
            // crossings come from low-amplitude measurement noise, not a
            // real oscillation.
            vibratoDetected = vibratoRateHz in 3.5f..9f && relativeDeviation > 0.01f
        }

        // ── Automatic singing detection (Voice Mimic Engine Phase 3) ──────────
        // Combines three signals, reusing data already computed above:
        //   1. Vibrato present — strong, specific singing indicator on its own
        //   2. Wide pitch range — singing typically spans more semitones than
        //      speech's natural intonation range
        //   3. Sustained energy — held notes have steadier RMS than the
        //      naturally choppy energy pattern of spoken syllables
        // Trusts vibrato alone, OR requires the other two together (covers
        // monotone/chant-like singing that has little audible vibrato).
        //
        // HONEST LIMIT: this DETECTS that singing is likely happening. It
        // does not mean Android TTS can actually preserve note lengths,
        // rhythm, or real vibrato when reproducing it — see the comments in
        // HindiTtsService.buildSingingContour() for what's actually achieved.
        var singingDetected = false
        if (n >= 20) {
            val voicedF0 = f0Buf.filter { it > 60f }
            val f0Min = voicedF0.minOrNull() ?: 0f
            val f0Max = voicedF0.maxOrNull() ?: 0f
            val pitchRangeSemitones = if (f0Min > 0f && f0Max > 0f)
                12f * (kotlin.math.ln(f0Max / f0Min) / kotlin.math.ln(2f)) else 0f

            val rmsValues = rmsBuf.filter { it > 0f }
            val rmsMean = if (rmsValues.isNotEmpty()) rmsValues.average().toFloat() else 0f
            val rmsStability = if (rmsValues.size > 1 && rmsMean > 0f) {
                val variance = rmsValues.sumOf { ((it - rmsMean) * (it - rmsMean)).toDouble() } / rmsValues.size
                1f - (kotlin.math.sqrt(variance).toFloat() / rmsMean).coerceIn(0f, 1f)
            } else 0f

            val wideRange       = pitchRangeSemitones > 5f    // >5 semitones — more than typical speech intonation
            val sustainedEnergy = rmsStability > 0.55f         // fairly stable energy = held notes, not choppy speech

            singingDetected = vibratoDetected || (wideRange && sustainedEnergy)
        }

        // Legacy 3-point fields (still used as fallback)
        lastContourStartF0 = capturedF0Curve[0]
        lastContourPeakF0  = capturedF0Curve.max()
        lastContourEndF0   = capturedF0Curve[CONTOUR_POINTS - 1]

        // Publish to HindiTtsService
        HindiTtsService.capturedStartF0  = lastContourStartF0
        HindiTtsService.capturedPeakF0   = lastContourPeakF0
        HindiTtsService.capturedEndF0    = lastContourEndF0
        capturedF0Curve.copyInto(HindiTtsService.capturedF0Curve)
        capturedRmsCurve.copyInto(HindiTtsService.capturedRmsCurve)
        HindiTtsService.capturedVibratoDetected = vibratoDetected
        HindiTtsService.capturedVibratoRateHz   = vibratoRateHz
        HindiTtsService.capturedBreathCount     = breathCountThisSentence
        HindiTtsService.capturedSingingDetected = singingDetected
        breathCountThisSentence = 0
        silentHopStreak = 0

        val shape = when {
            lastContourEndF0 > lastContourStartF0 * 1.05f -> "↑RISING"
            lastContourEndF0 < lastContourStartF0 * 0.95f -> "↓FALLING"
            else -> "→FLAT"
        }
        CaptionLogger.log("GenderAnalyzer",
            "CONTOUR-${CONTOUR_POINTS}pt [${capturedF0Curve.map{it.toInt()}.joinToString(",")}] $shape" +
            (if (vibratoDetected) " VIBRATO=${vibratoRateHz.format1()}Hz" else "") +
            (if (singingDetected) " SINGING" else ""))

        // Lock per-sentence median F0 — computed from all voiced frames this sentence
        // This gives HindiTtsService ONE stable base pitch for the whole sentence
        HindiTtsService.lockSentenceF0()

        // Reset for next sentence
        contourF0Buffer.clear()
        contourRmsBuffer.clear()
        contourPeak = 0f
    }

    private fun Float.format1(): String = "%.1f".format(this)

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

        // ── New expressive emotions ───────────────────────────────────────────

        // SHOUTING: very high RMS + fast rate + not too high pitch (yelling, not fearful)
        if (rmsMean > 4000f && highEnergy >= 6 && f0Std > 15f && f0Mean < 210f)
            return HindiTtsService.Emotion.SHOUTING

        // PANICKING: extremely fast + very high pitch + high energy together
        if (rising >= 5 && f0Mean > 220f && highEnergy >= 4 && rmsMean > 2000f)
            return HindiTtsService.Emotion.PANICKING

        // CRYING: high pitch + irregular/unstable F0 (voice breaks) + moderate pace
        // F0 instability (high std relative to mean) is the key marker
        if (f0Mean > 175f && f0Std > 35f && highEnergy < 4 && rmsMean in 500f..2500f)
            return HindiTtsService.Emotion.CRYING

        // SOBBING: very low F0, very slow, low energy — deep grief
        if (f0Mean < 120f && falling >= 4 && rmsMean < 700f)
            return HindiTtsService.Emotion.SOBBING

        // LAUGHING: rapid rhythmic energy bursts + high pitch
        if (highEnergy >= 3 && rising >= 3 && f0Mean > 190f && f0Std > 20f)
            return HindiTtsService.Emotion.LAUGHING

        // PLEADING: rapidly rising F0, moderate-high energy, urgent
        if (rising >= 4 && f0Mean in 155f..210f && rmsMean in 1000f..3500f)
            return HindiTtsService.Emotion.PLEADING

        // COMMANDING: very low F0, very low variance (steady authoritative voice)
        if (f0Mean < 105f && f0Std < 12f && rmsMean > 1200f)
            return HindiTtsService.Emotion.COMMANDING

        // WHINING: high pitch, falling, low-moderate energy (complaint tone)
        if (f0Mean > 180f && falling >= 3 && rmsMean < 1500f && f0Std < 25f)
            return HindiTtsService.Emotion.WHINING

        // TAUNTING: mid-high pitch, slow variation, moderate energy
        if (f0Mean in 160f..200f && f0Std in 15f..30f && highEnergy < 3 && rising >= 2)
            return HindiTtsService.Emotion.TAUNTING

        // CONSOLING: low-mid pitch, falling, warm, soft
        if (f0Mean in 100f..155f && falling >= 2 && rmsMean < 1000f && f0Std < 18f)
            return HindiTtsService.Emotion.CONSOLING

        return HindiTtsService.Emotion.NEUTRAL
    }

    private fun rmsStd(rmss: List<Float>): Float = rmss.average().toFloat()
}
