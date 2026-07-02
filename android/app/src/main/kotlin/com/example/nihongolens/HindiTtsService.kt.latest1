package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * HindiTtsService — Android TextToSpeech (hi-IN) + Background Music Mixing
 *
 * VOICE QUALITY:
 *   Uses Android's built-in Google TTS (hi-IN locale) which has:
 *   - Real Indian female voice (pitch=1.15f)
 *   - Real Indian male voice (pitch=0.88f)
 *   - No Piper server needed for voice generation
 *   - Near-zero synthesis latency (Google TTS is highly optimized)
 *
 * BACKGROUND MUSIC:
 *   BackgroundMusicRecorder captures USAGE_MEDIA at 44100Hz stereo
 *   Each 2s chunk is POSTed to server /bg?seq=N
 *   At speak() time: snapshot currentBgSeq
 *   Server mixes bg chunk #N under TTS speech at 28% volume
 *   Result: Hindi voice + original background music/sounds in ONE WAV
 *
 * EMOTION ADAPTATION:
 *   GenderAnalyzer sets currentEmotion on this object
 *   Each emotion → pitch + rate adjustment on Android TTS
 *   No Piper params needed — Android TTS handles it natively
 *
 * FIFO + SUBTITLE SYNC:
 *   speak() → fetchQueue → synthesizeToFile → playQueue → PLAY → showTtsText()
 *   Subtitle shown ONLY when audio starts (FIFO token-locked)
 */
object HindiTtsService {

    private const val TAG = "HindiTTS"

    // ── Emotion enum (26 values, used by GenderAnalyzer) ─────────────────────
    enum class Emotion {
        NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS,
        WARM, FEARFUL, SURPRISED, SIGHING,
        SINGING, GASPING, PANTING, MOANING,
        STRAINED, GRAVELLY, RASPY, HUSKY,
        WHISPERY, MURMURED, HUSHED, BREATHY,
        SULTRY, TENDER, VELVETY, DISGUST,
        // New expressive emotions
        CRYING,      // tearful, voice breaks — high trembling pitch, slow, unstable
        SHOUTING,    // yelling — very high energy, fast, clipped
        LAUGHING,    // bright, rhythmic, high pitch bursts
        SOBBING,     // deep grief — very low, very slow, broken
        PLEADING,    // begging — rising desperate pitch, fast
        COMMANDING,  // authoritative — low firm pitch, slower deliberate pace
        WHINING,     // complaining — nasal-like high pitch, drawn out
        TAUNTING,    // mocking — slightly sing-song, high pitch, slow
        PANICKING,   // extreme fear/rush — very fast, very high, irregular
        CONSOLING    // calming another — soft, low, warm, slow
    }

    enum class Gender { AUTO, MALE, FEMALE }

    // ── Voice Type Classification (for labeling/logging) ─────────────────────
    // 6 standard voice types — used as a HUMAN-READABLE label in logs only.
    // The ACTUAL pitch mirroring below is continuous (exact Hz), not bucketed —
    // two Sopranos at 220Hz and 320Hz get DIFFERENT TTS pitch, not the same one.
    enum class VoiceType {
        SOPRANO, MEZZO_SOPRANO, CONTRALTO,        // female, high → low
        COUNTERTENOR, TENOR, BARITONE, BASS,      // male, high → low
        UNKNOWN
    }

    @Volatile var currentVoiceType: VoiceType = VoiceType.UNKNOWN

    // ── EXACT F0 mirroring — the real mimicry mechanism ──────────────────────
    // GenderAnalyzer continuously measures the original speaker's exact F0 (Hz)
    // and writes it here. speak() reads this EXACT value (not a bucket average)
    // and computes a precise pitch ratio for Android TTS — so a speaker at
    // 242Hz gets a DIFFERENT, more precise pitch than one at 238Hz or 255Hz.
    //
    // Updated by GenderAnalyzer's rolling 5s average (stable, not jittery
    // frame-to-frame) — same cadence as currentVoiceType, just continuous.
    @Volatile var currentMeasuredF0: Float = 0f   // raw rolling average (used for contour)

    // ── EMA-smoothed F0 — the key to consistent same-speaker pitch ────────────
    // Problem: currentMeasuredF0 (rolling 5s avg) jumps sentence-to-sentence
    // because music/silence frames contaminate the window, making the same male
    // voice sound like a different male voice each sentence.
    //
    // Solution: Exponential Moving Average with low alpha (very slow to change).
    //   new_ema = alpha * new_sample + (1-alpha) * old_ema
    //   alpha=0.08 → very slow drift, very stable across sentences
    //   A sudden spike (noise/music) barely moves it — needs many consistent
    //   frames to shift, which only happens with a genuine speaker change.
    //
    // This is THE number used for the TTS base pitch — not currentMeasuredF0.
    // ── Per-Sentence Locked Median F0 (replaces EMA) ───────────────────────
    // EMA caused within-sentence voice drift — basePitch shifted every 10ms frame.
    // Median locks ONE F0 value per sentence using all voiced frames in the window.
    // Every word in a sentence gets the SAME base pitch. Natural intonation variation
    // comes from the per-word SSML contour, not from a drifting base.

    @Volatile var sentenceF0     : Float = 0f
    @Volatile var sentenceGender : Gender = Gender.AUTO
    private val f0SentenceBuffer = java.util.concurrent.CopyOnWriteArrayList<Float>()

    fun addF0Frame(f0: Float, gender: Gender) {
        if (f0 <= 60f) return
        if (gender != sentenceGender) { f0SentenceBuffer.clear(); sentenceGender = gender }
        f0SentenceBuffer.add(f0)
        if (f0SentenceBuffer.size > 300) f0SentenceBuffer.removeAt(0)
    }

    // Called at sentence commit (flushContour) — computes robust median and locks it
    fun lockSentenceF0(): Float {
        val frames = f0SentenceBuffer.toList().filter { it > 60f }
        if (frames.isEmpty()) return sentenceF0.takeIf { it > 0f } ?: 0f
        val sorted = frames.sorted()
        val median = sorted[sorted.size / 2]
        val q1 = sorted[sorted.size / 4]; val q3 = sorted[3 * sorted.size / 4]
        val iqr = q3 - q1
        val filtered = sorted.filter { it >= median - 1.5f * iqr && it <= median + 1.5f * iqr }
        val robust = if (filtered.isNotEmpty()) filtered[filtered.size / 2] else median
        sentenceF0 = robust
        currentMeasuredF0 = robust
        val isFemale = sentenceGender == Gender.FEMALE
        CaptionLogger.log("HindiTTS",
            "SENTENCE-F0: ${robust.toInt()}Hz (${frames.size} frames) " +
            "ratio=${String.format("%.2f", exactPitchRatio(robust, isFemale))}")
        f0SentenceBuffer.clear()
        return robust
    }

    fun resetSentenceF0() {
        sentenceF0 = 0f; sentenceGender = Gender.AUTO; f0SentenceBuffer.clear()
    }

    // Called on gender switch — seeds new gender buffer with current F0
    // so the first sentence after switch gets a real pitch, not the flat 1.0 default
    fun seedSentenceF0(f0: Float, gender: Gender) {
        sentenceGender = gender
        f0SentenceBuffer.clear()
        if (f0 > 60f) {
            // Add several copies to give the median enough data immediately
            repeat(10) { f0SentenceBuffer.add(f0) }
            sentenceF0 = f0
            CaptionLogger.log("HindiTTS",
                "SEED-F0 gender=$gender f0=${f0.toInt()}Hz ratio=${String.format("%.2f", exactPitchRatio(f0, gender == Gender.FEMALE))}")
        }
    }

        // Stable pitch ratio using EMA F0 — same speaker always sounds consistent
    // ── VoiceProfile → TTS parameter mapping ─────────────────────────────────
    // Called by VoiceAnalyzer every ~2s with updated vocal metrics.
    // Maps each measured metric directly to Android TTS parameters.
    @Volatile var voiceProfile: VoiceProfile? = null

    fun applyVoiceProfile(profile: VoiceProfile) {
        voiceProfile = profile
        val isFemale = detectedGender == Gender.FEMALE

        // Feed meanF0 into sentence buffer
        if (profile.meanF0 > 60f)
            addF0Frame(profile.meanF0, if (isFemale) Gender.FEMALE else Gender.MALE)

        // Category 2: Centroid → subtle pitch brightness trim (±8% max)
        val centroidRef = if (isFemale) 2500f else 1800f
        centroidPitchTrim = ((profile.spectralCentroid - centroidRef) / centroidRef * 0.06f)
            .coerceIn(-0.08f, 0.08f)

        // Category 4: Syllable rate → direct rate ratio (4.5/s = 1.0 reference)
        syllableRateMultiplier = if (profile.syllableRate > 1f)
            (profile.syllableRate / 4.5f).coerceIn(0.6f, 1.8f) else 1.0f

        // Category 3: Voice quality → texture emotion
        voiceTextureEmotion = when {
            profile.jitter > 1.5f && profile.shimmer > 8f -> Emotion.GRAVELLY
            profile.shimmer > 7f && profile.hnr < 14f     -> Emotion.BREATHY
            profile.hnr < 13f && profile.jitter < 1f      -> Emotion.WHISPERY
            else                                           -> null
        }

        CaptionLogger.log("HindiTTS",
            "PROFILE: sentF0=${sentenceF0.toInt()}Hz " +
            "syllRate=${"%.1f".format(profile.syllableRate)}/s→×${"%.2f".format(syllableRateMultiplier)} " +
            "centTrim=${(centroidPitchTrim*100).toInt()}% texture=$voiceTextureEmotion")
    }

    @Volatile var centroidPitchTrim:      Float = 0f
    @Volatile var syllableRateMultiplier: Float = 1.0f
    @Volatile var voiceTextureEmotion:    Emotion? = null


    fun stablePitchRatio(isFemale: Boolean): Float = when {
        sentenceF0 > 0f -> exactPitchRatio(sentenceF0, isFemale)
        currentMeasuredF0 > 0f -> exactPitchRatio(currentMeasuredF0, isFemale)
        else -> 1.0f
    }

    // ── Captured F0+RMS contour for SSML prosody ────────────────────────────
    // Written by GenderAnalyzer.flushContour() just before each sentence.
    // 10-point curves give per-word pitch AND duration accuracy.
    @Volatile var capturedStartF0: Float = 0f   // legacy 3-point (fallback)
    @Volatile var capturedPeakF0:  Float = 0f
    @Volatile var capturedEndF0:   Float = 0f
    val capturedF0Curve  = FloatArray(10)  // 10-point F0 across sentence duration
    val capturedRmsCurve = FloatArray(10)  // 10-point RMS (energy) across sentence

    // Android TTS "natural" reference F0 — the approximate average speaking
    // pitch of the underlying recorded voice corpus when pitch=1.0 (no shift).
    // These are empirical reference points for Google TTS hi-IN voices.
    // Google TTS hi-IN Voice baselines (empirically measured):
    // Female (Voice II): natural speaking pitch ~200Hz
    // Male (Voice IV): natural speaking pitch ~120Hz
    //
    // These are the actual recorded corpus pitches. Getting these right is
    // critical — wrong baseline = ratio too high/low → unnatural voice.
    // 
    // Pitch ratio is then CLAMPED to 0.75–1.45 to stay in natural range.
    // Android TTS sounds robotic above 1.6 regardless of input.
    private const val TTS_NATURAL_F0_FEMALE = 200f  // Voice II natural F0
    private const val TTS_NATURAL_F0_MALE   = 120f  // Voice IV natural F0

    /**
     * Exact continuous pitch ratio: captured_F0 / TTS_natural_F0.
     * This is THE core mimicry formula — no discrete buckets, no rounding to
     * voice-type categories. Every Hz of difference in the original speaker's
     * voice produces a proportionally different Hindi TTS pitch.
     *
     * Example (from spec): captured 242Hz female speaker
     *   ratio = 242 / 200 = 1.21  →  Android TTS pitch = 1.21
     *   (this naturally falls in "Soprano" territory but isn't snapped to a
     *    fixed Soprano constant — it's the speaker's OWN exact pitch ratio)
     */
    fun exactPitchRatio(measuredF0: Float, isFemale: Boolean): Float {
        if (measuredF0 <= 0f) return 1.0f  // no measurement yet — neutral
        val naturalF0 = if (isFemale) TTS_NATURAL_F0_FEMALE else TTS_NATURAL_F0_MALE
        // Natural-sounding range: 0.75–1.45
        // Below 0.75 or above 1.45: Android TTS sounds robotic/squeaky
        return (measuredF0 / naturalF0).coerceIn(0.75f, 1.45f)
    }

    // Kept for log labeling only — classifies the ratio into a human-readable
    // voice type name for debug logs (NOT used for the actual pitch calculation).
    fun voiceTypePitchMultiplier(vt: VoiceType): Float = when (vt) {
        VoiceType.SOPRANO       -> 1.35f
        VoiceType.MEZZO_SOPRANO -> 1.10f
        VoiceType.CONTRALTO     -> 0.90f
        VoiceType.COUNTERTENOR  -> 1.15f
        VoiceType.TENOR         -> 1.00f
        VoiceType.BARITONE      -> 0.85f
        VoiceType.BASS          -> 0.70f
        VoiceType.UNKNOWN       -> 1.00f
    }

    // ── State ─────────────────────────────────────────────────────────────────
    @JvmField @Volatile var enabled           = true
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.6f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false
    @Volatile var currentEmotion: Emotion     = Emotion.NEUTRAL
    @Volatile private var speakingUntilMs     = 0L

    @JvmField val spokenTokens = ConcurrentHashMap<Int, Boolean>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null

    // ── Android TTS ───────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var cacheDir: File? = null
    private var am: AudioManager? = null

    // Selected voices: Voice II (female) and Voice IV (male) from Hindi (India) voices list
    // Discovered at init time by enumerating tts.voices filtered to hi-IN locale
    // User picked Voice II for female and Voice IV for male (from Settings screenshot)
    @Volatile private var voiceFemale: android.speech.tts.Voice? = null
    @Volatile private var voiceMale:   android.speech.tts.Voice? = null

    // Pending utterance callbacks: utteranceId → resumption function
    // Mutex: Android TTS synthesizeToFile is NOT thread-safe when called concurrently
    // on the same TextToSpeech object. Two workers calling it simultaneously causes:
    //   - Wrong pitch/voice applied (F2 sets voice=male while F1 is synthesizing female)  
    //   - CompletableDeferred callback mismatch (F2 completion triggers F1's deferred)
    //   - Silent failure: synthesizeToFile returns SUCCESS but produces empty WAV
    // Mutex ensures only ONE synthesis at a time. Workers can still fetch/decode in parallel.
    private val synthesizeMutex = Mutex()

    private val pendingUtterances = ConcurrentHashMap<String, () -> Unit>()

    // ── FIFO queues ───────────────────────────────────────────────────────────
    data class FetchItem(
        val text: String,
        val gender: String,
        val pitch: Float,
        val rate: Float,
        val srcText: String = "",
        val emotion: Emotion = Emotion.NEUTRAL,
        val bgSeq: Int = 0,
        val enqMs: Long = System.currentTimeMillis()
    )
    data class PlayItem(val text: String, val wavFile: File, val durMs: Long)

    private val fetchQueue = LinkedBlockingQueue<FetchItem>()
    private val playQueue  = LinkedBlockingQueue<PlayItem>()

    // Debug accessors for CaptionLogger state snapshot
    fun fetchQueueSize() = fetchQueue.size

    // Called by GenderAnalyzer when a confirmed gender switch occurs (new speaker)
    // EMA resets automatically in updateEmaF0 when gender changes
    fun playQueueSize()  = playQueue.size

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        cacheDir = context.cacheDir
        am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Prevent LC from capturing TTS audio
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            am?.allowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_NONE
        }

        // Initialize Android TTS — discover hi-IN voices and select Voice II (female) / Voice IV (male)
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    // Enumerate all available hi-IN voices, sorted alphabetically (matches Settings order)
                    val hiVoices = tts?.voices
                        ?.filter { v ->
                            (v.locale.language == "hi" || v.locale.toLanguageTag().startsWith("hi")) &&
                            !v.isNetworkConnectionRequired
                        }
                        ?.sortedBy { it.name }
                        ?: emptyList()

                    CaptionLogger.log(TAG, "hi-IN voices found: ${hiVoices.size}")
                    hiVoices.forEachIndexed { i, v ->
                        CaptionLogger.log(TAG, "  Voice ${i+1}: ${v.name} quality=${v.quality}")
                    }

                    voiceFemale = hiVoices.getOrNull(1)  // Voice II
                    voiceMale   = hiVoices.getOrNull(3)  // Voice IV

                    if (voiceFemale == null && hiVoices.isNotEmpty())
                        voiceFemale = hiVoices.getOrNull(0)
                    if (voiceMale == null && hiVoices.size >= 2)
                        voiceMale = hiVoices.getOrNull(1) ?: voiceFemale

                    CaptionLogger.log(TAG, "FEMALE voice: ${voiceFemale?.name ?: "not found"}")
                    CaptionLogger.log(TAG, "MALE voice:   ${voiceMale?.name ?: "not found"}")

                } catch (e: Exception) {
                    // Voice enumeration failed — TTS still works with default voice
                    CaptionLogger.log(TAG, "Voice enum failed: ${e.message} — using default hi-IN")
                    voiceFemale = null; voiceMale = null
                }

                // Set default locale
                val result = tts?.setLanguage(Locale("hi", "IN"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("hi"))
                    CaptionLogger.log(TAG, "TTS: hi-IN not found, using generic hi")
                }

                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id -> pendingUtterances.remove(id)?.invoke() }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { id -> pendingUtterances.remove(id)?.invoke() }
                    }
                })

                ttsReady = true
                CaptionLogger.log(TAG, "TTS READY — female=${voiceFemale?.name ?: "default"} male=${voiceMale?.name ?: "default"}")
            } else {
                CaptionLogger.log(TAG, "TTS init failed: $status")
            }
        }

        startFetchWorker()
        startPlayWorker()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) { fetchQueue.clear(); playQueue.clear(); stopAudio() }
    }
    fun setGender(g: Gender)         { selectedGender = g }
    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }
    fun isSuppressed()               = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun stopAndClear() {
        fetchQueue.clear(); playQueue.clear()
        tts?.stop()
        stopAudio()
        isSpeaking = false
        speakingUntilMs = System.currentTimeMillis() + 300L
        spokenTokens.clear()
    }

    fun destroy() {
        fetchWorker?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopAudio()
        tts?.stop(); tts?.shutdown(); tts = null
        scope.cancel()
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String, srcText: String = "") {
        if (!enabled) return
        if (hindi.isBlank()) return
        if (!ttsReady) {
            CaptionLogger.log(TAG, "SPEAK-WAIT ttsReady=false, dropping '${hindi.take(20)}'")
            return
        }
        val n = hindi.trim().replace(Regex("\\s+"), " ")

        val isFemale = when (selectedGender) {
            Gender.FEMALE -> true
            Gender.MALE   -> false
            Gender.AUTO   -> (detectedGender == Gender.FEMALE)
        }

        // FIX BUG 1: Include gender in dedup token.
        // Previously token = n.hashCode() — when gender switched on same sentence,
        // putIfAbsent found existing token → skipped → gender never applied.
        // Now token includes gender so re-play occurs on switch.
        val genderBit = if (isFemale) 0x80000000.toInt() else 0
        // FIX: numbers/short texts use seq-based token to prevent dedup silencing them
        // "20 pounds" repeated across sentences must speak each time
        val isNumericHeavy = n.count { it.isDigit() } > n.length / 3
        val token = if (isNumericHeavy) System.currentTimeMillis().toInt()
                    else n.hashCode() xor genderBit
        if (!isNumericHeavy && spokenTokens.putIfAbsent(token, true) != null) return
        if (spokenTokens.size > 300) spokenTokens.clear()
        val genderTag = if (isFemale) "female" else "male"

        // Emotion → pitch/rate adjustments
        // pitchMult/rateMult now computed below using effectiveEmotion (includes VoiceProfile texture)

        // EXACT MIRRORING: instead of approximating the gender with a fixed
        // basePitch, use the speaker's EXACT measured F0 to compute a precise
        // pitch ratio. If no measurement yet (currentMeasuredF0=0), fall back
        // to the old gender-only approximation so voices aren't silent/flat
        // before GenderAnalyzer has gathered enough samples.
        val hasSpecificVoice = if (isFemale) voiceFemale != null else voiceMale != null

        // Per-sentence locked median — same voice throughout each sentence.
        // stablePitchRatio() reads from sentenceF0 (locked median per sentence).
        // Per-sentence locked median — one stable pitch for the whole sentence.
        // When no F0 measured yet: use 1.0 (natural TTS voice without shift).
        // This prevents the first sentence sounding different from subsequent ones.
        val basePitch: Float = when {
            sentenceF0 > 0f        -> stablePitchRatio(isFemale)
            currentMeasuredF0 > 0f -> exactPitchRatio(currentMeasuredF0, isFemale)
            else                   -> 1.0f  // no data: natural voice, no shift
        }

        // Apply VoiceProfile-derived adjustments on top of base pitch/rate
        // centroidPitchTrim: spectral brightness offset (high centroid = brighter pitch)
        // syllableRateMultiplier: measured speaking speed → TTS rate
        // 0f: articulation crispness → rate
        // Direct metric mapping — no SSML, no EMA:
        // pitch = measured F0 / TTS natural F0 + centroid brightness trim
        // rate  = measured syllable rate / reference rate (4.5/s)
        val profilePitch = (basePitch + centroidPitchTrim).coerceIn(0.75f, 1.45f)
        val profileRate  = (ttsSpeedMultiplier * syllableRateMultiplier).coerceIn(0.5f, 2.0f)

        // Use voiceTextureEmotion if active and no other emotion detected
        val effectiveEmotion = if (voiceTextureEmotion != null && currentEmotion == Emotion.NEUTRAL)
            voiceTextureEmotion!! else currentEmotion
        val (pitchMult2, rateMult2) = emotionPitchRate(effectiveEmotion)

        val finalPitch = (profilePitch * pitchMult2).coerceIn(0.75f, 1.45f)  // stay in natural range
        val finalRate  = (profileRate  * rateMult2).coerceIn(0.5f, 3.0f)

        // Snapshot background music sequence at enqueue time for timing alignment
        val bgSeq = BackgroundMusicRecorder.currentSeq.get()

        CaptionLogger.log(TAG, "SPEAK emo=$currentEmotion spd=${String.format("%.2f", finalRate)} " +
            "pitch=${String.format("%.2f", finalPitch)} $genderTag " +
            "F0=${currentMeasuredF0.toInt()}Hz voiceType=$currentVoiceType bg=$bgSeq '${n.take(50)}'")

        fetchQueue.offer(FetchItem(n, genderTag, finalPitch, finalRate, srcText,
            currentEmotion, bgSeq, System.currentTimeMillis()))
    }

    // ── Fetch worker: Android TTS → WAV file ──────────────────────────────────

    private fun startFetchWorker() {
        // Single fetch worker — synthesizeMutex makes dual workers pointless:
        // W2 would just wait 4-8s for W1 to finish then synthesize itself = no benefit.
        // With single worker: clear FIFO order, no mutex contention, no 12s waits.
        fetchWorker = scope.launch { fetchLoop("F1") }
    }

    private suspend fun fetchLoop(workerName: String) {
        while (currentCoroutineContext().isActive) {
            val item = fetchQueue.take()
            if (!enabled || !ttsReady) continue

            // FIFO: no stale/overload drops — every sentence IS spoken
            // Backlog plays in order; clears only when Caption Lens is stopped
            val ageMs = System.currentTimeMillis() - item.enqMs
            if (ageMs > 30_000L)
                CaptionLogger.log(TAG, "TTS-BACKLOG ${ageMs/1000}s '${item.text.take(30)}'")

            val t0 = System.currentTimeMillis()
            val textToSpeak = if (item.gender == "female") toFeminineHindi(item.text) else item.text
            if (textToSpeak != item.text)
                CaptionLogger.log(TAG, "FEM-CONV '${item.text.take(30)}' → '${textToSpeak.take(30)}'")

            val spokenItem = item.copy(text = textToSpeak)
            val wavFile = synthesizeToFile(spokenItem) ?: continue
            val ms = System.currentTimeMillis() - t0

            val fileSize = wavFile.length()
            val sr = 22050L
            val durMs = if (fileSize > 44) ((fileSize - 44) * 1000L) / (sr * 2L) else 2000L
            val mixedFile = mixBgMusic(wavFile, item.bgSeq) ?: wavFile

            CaptionLogger.log(TAG, "TTS-WAV[$workerName] ${ms}ms ${durMs}ms bg=${item.bgSeq} '${textToSpeak.take(40)}'")
            playQueue.offer(PlayItem(textToSpeak, mixedFile, durMs))
        }
    }

    // BG_MIX_VOLUME: bg music at 28% — audible ambience without drowning speech
    private val BG_MIX_VOLUME = 0.28f

    private fun mixBgMusic(ttsWav: File, bgSeq: Int): File? {
        if (bgSeq <= 0) return null
        val bgPcm = BackgroundMusicRecorder.getChunk(bgSeq) ?: return null
        try {
            val ttsBytes = ttsWav.readBytes()
            if (ttsBytes.size <= 44) return null

            val sr     = readInt(ttsBytes, 24)
            val nch    = readShort(ttsBytes, 22)
            val bit    = readShort(ttsBytes, 34)
            val ttsPcm = ttsBytes.copyOfRange(44, ttsBytes.size)

            // TTS: mono 22050Hz int16
            val ttsShorts = ShortArray(ttsPcm.size / 2) { i ->
                ((ttsPcm[i*2+1].toInt() shl 8) or (ttsPcm[i*2].toInt() and 0xFF)).toShort()
            }

            // BG: stereo 44100Hz int16 → mono 22050Hz (downsample 2:1 by averaging)
            val bgShorts = ShortArray(bgPcm.size / 2) { i ->
                ((bgPcm[i*2+1].toInt() shl 8) or (bgPcm[i*2].toInt() and 0xFF)).toShort()
            }
            // Stereo channels → mono (every 2 shorts = L+R → average)
            val bgMono44k = ShortArray(bgShorts.size / 2) { i ->
                ((bgShorts[i*2].toInt() + bgShorts[i*2+1].toInt()) / 2).toShort()
            }
            // Downsample 44100 → 22050 (every 2 samples → 1 by averaging)
            val bgMono22k = ShortArray(bgMono44k.size / 2) { i ->
                ((bgMono44k[i*2].toInt() + bgMono44k[i*2+1].toInt()) / 2).toShort()
            }

            // Normalize BG energy to ~20% of TTS energy
            val ttsRms = ttsShorts.map { it.toLong() * it }.average().let { Math.sqrt(it) }
            val bgRms  = bgMono22k.map { it.toLong() * it }.average().let { Math.sqrt(it) }
            val normFactor = if (bgRms > 10) (ttsRms / bgRms * BG_MIX_VOLUME).toFloat() else 0f

            // Mix and clip
            val n = minOf(ttsShorts.size, bgMono22k.size)
            val mixed = ByteArray(ttsShorts.size * 2)
            for (i in ttsShorts.indices) {
                val ttsS = ttsShorts[i].toInt()
                val bgS  = if (i < bgMono22k.size) (bgMono22k[i] * normFactor).toInt() else 0
                val out  = (ttsS + bgS).coerceIn(-32767, 32767).toShort()
                mixed[i*2]   = (out.toInt() and 0xFF).toByte()
                mixed[i*2+1] = (out.toInt() shr 8).toByte()
            }

            // Write mixed WAV
            val outFile = File(cacheDir, "tts_mixed_${bgSeq}.wav")
            writeWav(outFile, mixed, sr, nch, bit)
            ttsWav.delete()

            CaptionLogger.log(TAG, "BG-MIX seq=$bgSeq norm=${String.format("%.2f", normFactor)} merged")
            return outFile
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "BG-MIX failed: ${e.message}")
            return null
        }
    }

    private fun writeWav(file: File, pcm: ByteArray, sr: Int, nch: Int, bit: Int) {
        file.outputStream().use { out ->
            val dataLen = pcm.size
            val header = ByteArray(44)
            fun putInt(buf: ByteArray, off: Int, v: Int) {
                buf[off]   = (v and 0xFF).toByte(); buf[off+1] = (v shr 8 and 0xFF).toByte()
                buf[off+2] = (v shr 16 and 0xFF).toByte(); buf[off+3] = (v shr 24 and 0xFF).toByte()
            }
            fun putShort(buf: ByteArray, off: Int, v: Int) {
                buf[off] = (v and 0xFF).toByte(); buf[off+1] = (v shr 8 and 0xFF).toByte()
            }
            header[0]=82;header[1]=73;header[2]=70;header[3]=70  // RIFF
            putInt(header, 4, 36 + dataLen)
            header[8]=87;header[9]=65;header[10]=86;header[11]=69  // WAVE
            header[12]=102;header[13]=109;header[14]=116;header[15]=32  // fmt
            putInt(header, 16, 16); putShort(header, 20, 1)
            putShort(header, 22, nch); putInt(header, 24, sr)
            putInt(header, 28, sr * nch * bit / 8)
            putShort(header, 32, nch * bit / 8); putShort(header, 34, bit)
            header[36]=100;header[37]=97;header[38]=116;header[39]=97  // data
            putInt(header, 40, dataLen)
            out.write(header); out.write(pcm)
        }
    }
    // ── Android TTS synthesize to file (async with coroutine bridge) ──────────

    // ── SSML builder: wraps Hindi text in prosody tags that mirror original F0 ──
    // Splits text into word-groups and assigns a pitch tag to each group based
    // on where it falls in the start→peak→end contour of the original utterance.
    // Android TTS supports SSML <speak><prosody pitch='...'> natively.
    //
    // Contour shapes:
    //   RISING  (endF0 > startF0): statement that trails up — question-like
    //   FALLING (endF0 < startF0): statement, conclusion — confident, definitive
    //   PEAKED  (peakF0 >> startF0 & endF0): emphasis in the middle
    //   FLAT    (all similar): calm, neutral — minimal prosody tags
    //
    // Build SSML with emotion-specific texture BEYOND just pitch/rate
    // SSML <break>, <emphasis>, and per-word <prosody> give expressive emotions
    // texture that setPitch() alone cannot achieve.
    private fun emotionSsml(text: String, emotion: Emotion, basePitchPct: String): String? {
        val esc = android.text.Html.escapeHtml(text)
        return when (emotion) {
            Emotion.CRYING -> {
                // Voice breaks: insert small pauses between word groups
                val words = text.split(" ")
                val tagged = words.mapIndexed { i, w ->
                    val br = if (i > 0 && i % 3 == 0) "<break time='120ms'/>" else ""
                    "$br<prosody pitch='${if (i % 2 == 0) basePitchPct else "+35%"}'>" +
                    android.text.Html.escapeHtml(w) + "</prosody>"
                }.joinToString(" ")
                "<speak><prosody rate='slow'>$tagged</prosody></speak>"
            }
            Emotion.SOBBING -> {
                // Deep grief: long breaks, dropping pitch
                val words = text.split(" ")
                val tagged = words.mapIndexed { i, w ->
                    val br = if (i % 4 == 0 && i > 0) "<break time='250ms'/>" else ""
                    "$br${android.text.Html.escapeHtml(w)}"
                }.joinToString(" ")
                "<speak><prosody pitch='-28%' rate='x-slow'>$tagged</prosody></speak>"
            }
            Emotion.SHOUTING -> {
                // Every word emphasised, clipped
                val tagged = text.split(" ").joinToString(" ") { w ->
                    "<emphasis level='strong'>${android.text.Html.escapeHtml(w)}</emphasis>"
                }
                "<speak><prosody pitch='+8%' rate='fast'>$tagged</prosody></speak>"
            }
            Emotion.PANICKING -> {
                // No pauses, very compressed, high pitch
                "<speak><prosody pitch='+30%' rate='x-fast'>$esc</prosody></speak>"
            }
            Emotion.PLEADING -> {
                // Rises urgently across the phrase
                val words = text.split(" ")
                val n = words.size.coerceAtLeast(1)
                val tagged = words.mapIndexed { i, w ->
                    val pct = -5 + (i * 30 / n)
                    "<prosody pitch='${if(pct>=0)"+${pct}%" else "${pct}%"}'>" +
                    android.text.Html.escapeHtml(w) + "</prosody>"
                }.joinToString(" ")
                "<speak><prosody rate='medium-fast'>$tagged</prosody></speak>"
            }
            Emotion.COMMANDING -> {
                // Strong emphasis on key words (every other), slow deliberate
                val words = text.split(" ")
                val tagged = words.mapIndexed { i, w ->
                    if (i % 2 == 0)
                        "<emphasis level='strong'>${android.text.Html.escapeHtml(w)}</emphasis>"
                    else android.text.Html.escapeHtml(w)
                }.joinToString(" ")
                "<speak><prosody pitch='-18%' rate='slow'>$tagged</prosody></speak>"
            }
            Emotion.TAUNTING -> {
                // Sing-song: alternating high-low pitch across words
                val words = text.split(" ")
                val tagged = words.mapIndexed { i, w ->
                    val p = if (i % 2 == 0) "+22%" else "+5%"
                    "<prosody pitch='$p'>${android.text.Html.escapeHtml(w)}</prosody>"
                }.joinToString(" ")
                "<speak><prosody rate='slow'>$tagged</prosody></speak>"
            }
            Emotion.WHINING -> {
                // Drawn out, high pitch, add stretch
                "<speak><prosody pitch='+28%' rate='slow'>$esc</prosody></speak>"
            }
            Emotion.LAUGHING -> {
                // Bright, rhythmic breaks between phrases
                val words = text.split(" ")
                val tagged = words.mapIndexed { i, w ->
                    val br = if (i > 0 && i % 4 == 0) "<break time='80ms'/>" else ""
                    "$br${android.text.Html.escapeHtml(w)}"
                }.joinToString(" ")
                "<speak><prosody pitch='+26%' rate='fast'>$tagged</prosody></speak>"
            }
            Emotion.CONSOLING -> {
                // Warm, falling reassurance
                "<speak><prosody pitch='-8%' rate='slow'><emphasis level='reduced'>$esc</emphasis></prosody></speak>"
            }
            else -> null  // use standard F0-contour SSML below
        }
    }

    // Build SSML for a sentence.
    //
    // ANDROID TTS BEHAVIOUR (discovered through testing):
    //   - Per-word <prosody PITCH> → sounds robotic (instant jumps, no glide)
    //   - Per-word <prosody RATE>  → sounds natural (duration variation = prosody)
    //   - Sentence-level pitch via setPitch() → most natural
    //
    // So this function applies:
    //   - ONE pitch for the whole sentence (from setPitch, already applied)
    //   - Per-word RATE from measured RMS energy (loud=slow/stressed, quiet=fast)
    //   - ONE stress emphasis at the measured RMS peak word
    //   - Punctuation pauses
    //
    // This mimics natural prosody: stressed syllables are held longer (slower)
    // and unstressed syllables are clipped (faster) — exactly what real speech does.
    private fun buildSsml(text: String, basePitch: Float,
                           startF0: Float, peakF0: Float, endF0: Float,
                           naturalF0: Float): String {

        fun esc(s: String) = android.text.Html.escapeHtml(s)

        val words = text.split(" ").filter { it.isNotBlank() }
        val n = words.size
        if (n == 0) return "<speak>${esc(text)}</speak>"

        // Sentence-level rate from measured syllable rate
        val baseRate: String = when {
            voiceProfile?.syllableRate ?: 0f > 6.5f -> "x-fast"
            voiceProfile?.syllableRate ?: 0f > 5.5f -> "fast"
            voiceProfile?.syllableRate ?: 0f < 2.5f -> "x-slow"
            voiceProfile?.syllableRate ?: 0f < 3.5f -> "slow"
            else                                     -> "medium"
        }

        // If no RMS curve data: return simple sentence-rate SSML
        val hasRms = capturedRmsCurve.any { it > 0f }
        if (!hasRms) {
            return "<speak><prosody rate='$baseRate'>${esc(text)}</prosody></speak>"
        }

        val maxRms = capturedRmsCurve.max().coerceAtLeast(1f)
        val minRms = capturedRmsCurve.filter { it > 0f }.minOrNull() ?: 0f
        val rmsRange = (maxRms - minRms).coerceAtLeast(1f)

        // Find stressed word (RMS peak)
        val peakRmsPos = capturedRmsCurve.indices.maxByOrNull { capturedRmsCurve[it] } ?: -1
        val stressIdx = if (peakRmsPos >= 0)
            (peakRmsPos.toFloat() / 9f * (n - 1)).toInt().coerceIn(0, n - 1) else -1

        val sb = StringBuilder("<speak>")
        words.forEachIndexed { i, word ->
            val t        = i.toFloat() / (n - 1).coerceAtLeast(1)
            val curvePos = (t * 9).toInt().coerceIn(0, 9)
            val rmsHere  = capturedRmsCurve[curvePos]

            // Per-word rate from RMS energy:
            // High RMS = speaker is stressing this word = speak slower (hold longer)
            // Low RMS = unstressed syllable = speak faster (clip it)
            // This is exactly how natural speech works — prosody via duration
            val normRms = ((rmsHere - minRms) / rmsRange).coerceIn(0f, 1f)
            val wordRate: String = when {
                normRms > 0.80f -> "slow"      // very stressed — hold it
                normRms > 0.55f -> "medium"    // moderately stressed
                normRms > 0.25f -> "fast"      // light syllable
                else            -> "x-fast"    // unstressed filler
            }

            // Stress emphasis at RMS peak
            val isStressed = (i == stressIdx)

            // Punctuation pauses
            val breakAfter = when (word.lastOrNull()) {
                '.', '!', '?', '।', '॥' -> "<break time='180ms'/>"
                ','                       -> "<break time='80ms'/>"
                ';', '—'                  -> "<break time='120ms'/>"
                else                      -> ""
            }

            sb.append("<prosody rate='$wordRate'>")
            if (isStressed) sb.append("<emphasis level='moderate'>${esc(word)}</emphasis>")
            else            sb.append(esc(word))
            sb.append("</prosody>$breakAfter")
            if (i < n - 1) sb.append(" ")
        }

        sb.append("</speak>")
        return sb.toString()
    }











    private suspend fun synthesizeToFile(item: FetchItem): File? {
        // Timeout on mutex acquire — if previous synthesis hung, don't wait forever.
        // Uses withLock (not manual lock()/unlock()) because withLock is
        // cancellation-safe: if withTimeoutOrNull cancels while lock() is
        // resuming, withLock guarantees it never leaves the mutex locked
        // without an owner. The old manual lock()+finally{unlock()} pattern
        // had a race where a timeout landing exactly as lock() acquired could
        // skip the unlock(), permanently wedging the mutex — every future
        // synthesis call would then time out forever with no way to recover.
        val result = try {
            withTimeoutOrNull(8_000L) {
                synthesizeMutex.withLock { synthesizeToFileInner(item) }
            }
        } catch (_: Exception) { null }
        if (result == null) {
            CaptionLogger.log(TAG, "SYNTH-MUTEX-TIMEOUT: previous synthesis hung, skipping", CaptionLogger.LEVEL_ERROR)
        }
        return result
    }

    private suspend fun synthesizeToFileInner(item: FetchItem): File? =
        withContext(Dispatchers.IO) {
            val localTts = tts ?: return@withContext null
            val outFile = File(cacheDir, "tts_${UUID.randomUUID()}.wav")

            // GENDER SWITCH FIX: Set the actual voice object (Voice II or Voice IV)
            // Previously only used setPitch() which doesn't change the voice identity —
            // it was always the same default voice with a pitch shift = still sounds male.
            // Now: voiceFemale = Voice II, voiceMale = Voice IV (user-confirmed mapping)
            val isFemale = item.gender == "female"
            val targetVoice = if (isFemale) voiceFemale else voiceMale
            if (targetVoice != null) {
                localTts.voice = targetVoice
                CaptionLogger.log(TAG, "TTS voice=${targetVoice.name} gender=${item.gender}")
            } else {
                // Fallback: use pitch to approximate gender difference
                CaptionLogger.log(TAG, "TTS no specific voice found, using pitch=${item.pitch}")
            }

            // Set emotion-based pitch and rate ON TOP of voice identity
            // Voice identity = who speaks; pitch/rate = how they feel
            localTts.setSpeechRate(item.rate)
            localTts.setPitch(item.pitch)

            // Build SSML from captured F0 contour when available
            // SSML gives word-group level pitch variation instead of one flat pitch
            val naturalF0 = if (item.gender == "female") TTS_NATURAL_F0_FEMALE else TTS_NATURAL_F0_MALE
            // capturedF0Curve still used by VoiceAnalyzer for stress detection in buildSsml
            val inputText: String
            val inputBundle: android.os.Bundle?

            val safeText = item.text
                .replace("&", "and")
                .replace("<", "").replace(">", "").trim()
            if (safeText.isBlank()) return@withContext null

            // APPROACH: plain setPitch/setSpeechRate for normal speech
            // This sounds MORE natural than SSML <prosody> per-word tags
            // because Android TTS handles internal prosody smoothly,
            // whereas SSML pitch tags JUMP between values (robotic staircase).
            // Only expressive emotions (CRYING/SHOUTING etc.) use SSML
            // for their break/emphasis texture effects.
            val expressiveSsml = emotionSsml(safeText, item.emotion,
                run { val pct = ((item.pitch - 1f) * 100f).toInt()
                      if (pct >= 0) "+${pct}%" else "${pct}%" })

            if (expressiveSsml != null) {
                inputText   = expressiveSsml
                inputBundle = android.os.Bundle().apply {
                    putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, 3)
                }
                CaptionLogger.log(TAG, "SSML-EXPRESSIVE: ${item.emotion}")
            } else {
                // Plain text — setPitch/setSpeechRate applied above are sufficient.
                // No SSML: per-word SSML tags cause robotic pitch jumps on Android TTS.
                inputText   = safeText
                inputBundle = null
                CaptionLogger.log(TAG, "TTS pitch=${String.format("%.2f", item.pitch)} rate=${String.format("%.2f", item.rate)}")
            }

            // Bridge Android TTS callback to coroutine
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Unit>()

            pendingUtterances[id] = { deferred.complete(Unit) }

            try {
                val result = localTts.synthesizeToFile(
                    inputText,
                    inputBundle,
                    outFile,
                    id
                )

                if (result != TextToSpeech.SUCCESS) {
                    pendingUtterances.remove(id)
                    CaptionLogger.log(TAG, "TTS synthesizeToFile failed result=$result")
                    return@withContext null
                }

                // Wait for synthesis to complete (max 8s)
                withTimeoutOrNull(12_000L) { deferred.await() }
                    ?: run {
                        pendingUtterances.remove(id)
                        CaptionLogger.log(TAG, "TTS-TIMEOUT 12s")
                        return@withContext null
                    }

                if (!outFile.exists() || outFile.length() < 100) {
                    CaptionLogger.log(TAG, "TTS output file empty — retrying with plain text", CaptionLogger.LEVEL_WARN)
                    // SSML may have caused empty output — retry with plain text
                    if (inputText.startsWith("<speak>")) {
                        val plainId = UUID.randomUUID().toString()
                        val plainDeferred = CompletableDeferred<Unit>()
                        pendingUtterances[plainId] = { plainDeferred.complete(Unit) }
                        val plainResult = localTts.synthesizeToFile(safeText, null, outFile, plainId)
                        if (plainResult == TextToSpeech.SUCCESS) {
                            withTimeoutOrNull(8_000L) { plainDeferred.await() }
                        } else {
                            pendingUtterances.remove(plainId)
                        }
                        if (!outFile.exists() || outFile.length() < 100) {
                            CaptionLogger.log(TAG, "TTS plain text also empty — skip")
                            return@withContext null
                        }
                        CaptionLogger.log(TAG, "TTS plain text fallback succeeded")
                    } else {
                        return@withContext null
                    }
                }

                outFile
            } catch (e: Exception) {
                pendingUtterances.remove(id)
                CaptionLogger.log(TAG, "TTS-EXC ${e.message}")
                null
            }
        } // end withContext

    // ── Play worker: WAV → AudioTrack ─────────────────────────────────────────

    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                val item = playQueue.take()
                if (!enabled) { item.wavFile.delete(); continue }
                try {
                    isSpeaking = true
                    CaptionLogger.log(TAG, "PLAY ${item.durMs}ms '${item.text.take(40)}'")
                    // Subtitle is now shown by deliverHindi() immediately when translation arrives.
                    // Play worker only handles audio — no longer controls overlay display.
                    // This decouples subtitle speed (CT2 ~0.8s) from audio speed (TTS ~1s).
                    playWavFile(item.wavFile)
                    speakingUntilMs = System.currentTimeMillis() + 200L
                } catch (e: Exception) {
                    CaptionLogger.log(TAG, "PLAY-ERR ${e.message}")
                } finally {
                    isSpeaking = false
                    try { item.wavFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    // ── AudioTrack playback ───────────────────────────────────────────────────

    private var audioTrack: AudioTrack? = null

    private fun stopAudio() {
        try { audioTrack?.stop() }  catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private suspend fun playWavFile(file: File) = withContext(Dispatchers.IO) {
        try {
            val bytes = file.readBytes()
            if (bytes.size <= 44) return@withContext

            val sr   = readInt(bytes, 24).coerceAtLeast(8_000)
            val nch  = readShort(bytes, 22)
            val bit  = readShort(bytes, 34)
            val pcm  = bytes.copyOfRange(44, bytes.size)
            val fmt  = if (bit == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
            val chan = if (nch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val dur  = ((pcm.size.toLong() * 1000L) / (sr.toLong() * nch * (bit / 8))).coerceAtLeast(100L)

            val minBuf = AudioTrack.getMinBufferSize(sr, chan, fmt).coerceAtLeast(pcm.size)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sr).setChannelMask(chan).setEncoding(fmt).build())
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            stopAudio()
            audioTrack = track
            track.write(pcm, 0, pcm.size)
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            delay(dur + 150L)
            track.stop(); track.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "playWavFile: ${e.message}")
        }
    }

    // ── Emotion → pitch + rate multipliers ───────────────────────────────────
    // FIX BUG 3: Previous values were too subtle (e.g. HAPPY: pitch 1.05 → barely noticeable)
    // Now each emotion has strong, clearly distinguishable vocal character:
    //   pitch: applied ON TOP of base (female=1.15, male=0.88)
    //   rate: multiplied with ttsSpeedMultiplier (default 1.6)
    // Range: pitch 0.70–1.40 (wide), rate 0.70–1.40 (wide)
    private fun emotionPitchRate(e: Emotion): Pair<Float, Float> = when (e) {
        // ── Core emotions (very distinct) ────────────────────────────────────
        // NEUTRAL: was a perfectly flat 1.00/1.00 — since most ordinary dialogue
        // lands here (the acoustic detector only flags STRONG signals for the
        // named emotions below), a dead-flat neutral made most everyday speech
        // sound monotone. A small natural lift/pace makes baseline speech less robotic
        // without pretending to detect an emotion that wasn't actually measured.
        Emotion.NEUTRAL    -> Pair(1.04f, 1.05f)   // slight natural lift, not dead flat
        Emotion.HAPPY      -> Pair(1.30f, 1.25f)   // high pitched, fast, upbeat
        Emotion.SAD        -> Pair(0.72f, 0.68f)   // low, slow, heavy
        Emotion.ANGRY      -> Pair(0.82f, 1.35f)   // forceful, clipped, fast
        Emotion.EXCITED    -> Pair(1.40f, 1.40f)   // very high, very fast
        Emotion.CURIOUS    -> Pair(1.18f, 0.90f)   // slightly rising, thoughtful
        // ── Textured emotions ────────────────────────────────────────────────
        Emotion.WARM       -> Pair(0.95f, 0.85f)   // gentle, warm, slower
        Emotion.FEARFUL    -> Pair(1.32f, 1.28f)   // high, fast, tense
        Emotion.SURPRISED  -> Pair(1.45f, 1.18f)   // very high pitch spike
        Emotion.SIGHING    -> Pair(0.78f, 0.70f)   // low, very slow, breathy
        Emotion.SINGING    -> Pair(1.20f, 0.65f)   // less flat than speech, still no melody tracking
        Emotion.GASPING    -> Pair(1.38f, 1.42f)   // high, very fast
        Emotion.PANTING    -> Pair(1.22f, 1.40f)   // high, rapid
        Emotion.MOANING    -> Pair(0.70f, 0.62f)   // very low, very slow
        Emotion.STRAINED   -> Pair(0.88f, 0.85f)   // strained effort
        Emotion.GRAVELLY   -> Pair(0.68f, 0.90f)   // very low, rough
        Emotion.RASPY      -> Pair(0.73f, 0.92f)   // low, slightly raspy
        Emotion.HUSKY      -> Pair(0.78f, 0.88f)   // husky, low
        Emotion.WHISPERY   -> Pair(0.88f, 0.78f)   // quiet, slow
        Emotion.MURMURED   -> Pair(0.83f, 0.75f)   // very quiet, slow
        Emotion.HUSHED     -> Pair(0.86f, 0.73f)   // hushed whisper
        Emotion.BREATHY    -> Pair(0.90f, 0.82f)   // breathy, slow
        Emotion.SULTRY     -> Pair(0.76f, 0.70f)   // very low, very slow
        Emotion.TENDER     -> Pair(0.95f, 0.80f)   // gentle, soft
        Emotion.VELVETY    -> Pair(0.83f, 0.78f)   // smooth, low, slow
        Emotion.DISGUST    -> Pair(0.86f, 1.12f)   // low, sharp, clipped

        // ── Expressive emotions (new) ─────────────────────────────────────────
        // These use SSML inside buildSsml() for additional texture beyond pitch/rate
        Emotion.CRYING     -> Pair(1.25f, 0.72f)   // high trembling pitch, slow breaks
        Emotion.SHOUTING   -> Pair(0.92f, 1.45f)   // forceful, very fast, clipped words
        Emotion.LAUGHING   -> Pair(1.30f, 1.30f)   // bright high pitch, fast rhythmic
        Emotion.SOBBING    -> Pair(0.68f, 0.60f)   // very low, very slow, broken pauses
        Emotion.PLEADING   -> Pair(1.28f, 1.20f)   // desperate rising pitch, urgent pace
        Emotion.COMMANDING -> Pair(0.78f, 0.88f)   // low firm authoritative, deliberate
        Emotion.WHINING    -> Pair(1.32f, 0.85f)   // high drawn-out nasal-ish
        Emotion.TAUNTING   -> Pair(1.18f, 0.80f)   // sing-song slow mock
        Emotion.PANICKING  -> Pair(1.38f, 1.55f)   // very high, extremely fast
        Emotion.CONSOLING  -> Pair(0.88f, 0.78f)   // soft low warm slow
    }

    // ── WAV helpers ───────────────────────────────────────────────────────────

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl  8) or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    // ── Pronoun hint (verb forms only) ────────────────────────────────────────

    fun hasFemininePronouns(src: String): Boolean {
        if (src.isBlank()) return false
        val t = " ${src.lowercase()} "
        return t.contains(" she ") || t.contains(" her ") ||
               t.contains(" herself ") || t.contains(" she's ") ||
               t.contains(" she'd ") || t.contains(" she'll ")
    }

    fun toFeminineHindi(text: String): String {
        // FIX: if text is mainly numbers/digits, return unchanged
        // "20 pounds", "100%", "247" must not have verb replacements applied
        if (text.count { it.isDigit() } > text.length / 3) return text
        var t = text

        // ── Pronouns ─────────────────────────────────────────────────────────
        t = t.replace("वह", "वह")          // stays same but triggers fem verbs below
        t = t.replace("मैं", "मैं")         // stays same

        // ── First person present ──────────────────────────────────────────────
        t = t.replace("ता हूँ", "ती हूँ")
        t = t.replace("ता हूं", "ती हूं")
        t = t.replace("रहा हूँ", "रही हूँ")
        t = t.replace("रहा हूं", "रही हूं")
        t = t.replace("सकता हूँ", "सकती हूँ")
        t = t.replace("सकता हूं", "सकती हूं")
        t = t.replace("चाहता हूँ", "चाहती हूँ")
        t = t.replace("चाहता हूं", "चाहती हूं")
        t = t.replace("जाता हूँ", "जाती हूँ")
        t = t.replace("जाता हूं", "जाती हूं")
        t = t.replace("खाता हूँ", "खाती हूँ")
        t = t.replace("खाता हूं", "खाती हूं")
        t = t.replace("आता हूँ", "आती हूँ")
        t = t.replace("आता हूं", "आती हूं")
        t = t.replace("देता हूँ", "देती हूँ")
        t = t.replace("देता हूं", "देती हूं")
        t = t.replace("लेता हूँ", "लेती हूँ")
        t = t.replace("लेता हूं", "लेती हूं")
        t = t.replace("करता हूँ", "करती हूँ")
        t = t.replace("करता हूं", "करती हूं")
        t = t.replace("सोचता हूँ", "सोचती हूँ")
        t = t.replace("सोचता हूं", "सोचती हूं")
        t = t.replace("लगता हूँ", "लगती हूँ")
        t = t.replace("लगता हूं", "लगती हूं")

        // ── Third person present singular ─────────────────────────────────────
        t = t.replace("ता है", "ती है")
        t = t.replace("ते हैं", "ती हैं")
        t = t.replace("रहा है", "रही है")
        t = t.replace("रहे हैं", "रही हैं")
        t = t.replace("सकता है", "सकती है")
        t = t.replace("सकते हैं", "सकती हैं")
        t = t.replace("चाहता है", "चाहती है")
        t = t.replace("चाहते हैं", "चाहती हैं")
        t = t.replace("होता है", "होती है")
        t = t.replace("होते हैं", "होती हैं")
        t = t.replace("लगता है", "लगती है")
        t = t.replace("लगते हैं", "लगती हैं")
        t = t.replace("जाता है", "जाती है")
        t = t.replace("जाते हैं", "जाती हैं")
        t = t.replace("आता है", "आती है")
        t = t.replace("आते हैं", "आती हैं")
        t = t.replace("करता है", "करती है")
        t = t.replace("करते हैं", "करती हैं")
        t = t.replace("मिलता है", "मिलती है")
        t = t.replace("देता है", "देती है")
        t = t.replace("लेता है", "लेती है")
        t = t.replace("सोचता है", "सोचती है")
        t = t.replace("बोलता है", "बोलती है")
        t = t.replace("समझता है", "समझती है")
        t = t.replace("रखता है", "रखती है")

        // ── Past tense (था → थी) ──────────────────────────────────────────────
        t = t.replace("था", "थी")           // USER REQUESTED: था → थी
        t = t.replace("थे", "थीं")
        t = t.replace("ता था", "ती थी")
        t = t.replace("रहा था", "रही थी")
        t = t.replace("सकता था", "सकती थी")
        t = t.replace("चाहता था", "चाहती थी")
        t = t.replace("होता था", "होती थी")
        t = t.replace("जाता था", "जाती थी")
        t = t.replace("आता था", "आती थी")
        t = t.replace("करता था", "करती थी")
        t = t.replace("लगता था", "लगती थी")

        // ── Past participle ────────────────────────────────────────────────────
        t = t.replace("गया", "गई")
        t = t.replace("गए", "गईं")
        t = t.replace("आया", "आई")
        t = t.replace("आए", "आईं")
        t = t.replace("किया", "की")
        t = t.replace("लिया", "ली")
        t = t.replace("दिया", "दी")
        t = t.replace("पाया", "पाई")
        t = t.replace("पाए", "पाईं")
        t = t.replace("बताया", "बताई")
        t = t.replace("सुनाया", "सुनाई")
        t = t.replace("बनाया", "बनाई")
        t = t.replace("लाया", "लाई")
        t = t.replace("सिखाया", "सिखाई")
        t = t.replace("समझाया", "समझाई")
        t = t.replace("भेजा", "भेजी")
        t = t.replace("छोड़ा", "छोड़ी")
        t = t.replace("छोड़े", "छोड़ीं")
        t = t.replace("पकड़ा", "पकड़ी")
        t = t.replace("पढ़ा", "पढ़ी")
        t = t.replace("देखा", "देखी")
        t = t.replace("मारा", "मारी")
        t = t.replace("बुलाया", "बुलाई")
        t = t.replace("खाया", "खाई")
        t = t.replace("पिया", "पी")
        t = t.replace("पहना", "पहनी")
        t = t.replace("ओढ़ा", "ओढ़ी")
        t = t.replace("उठाया", "उठाई")
        t = t.replace("डाला", "डाली")
        t = t.replace("निकाला", "निकाली")
        t = t.replace("बचाया", "बचाई")
        t = t.replace("बुझाया", "बुझाई")

        // ── ि → ी suffix pattern (core feminine marker) ───────────────────────
        // Masculine adjective/verb suffix 'ा' (aa) → 'ी' (ii) for feminine
        // Applied selectively to avoid over-replacement
        t = t.replace("अच्छा", "अच्छी")
        t = t.replace("बुरा", "बुरी")
        t = t.replace("बड़ा", "बड़ी")
        t = t.replace("छोटा", "छोटी")
        t = t.replace("नया", "नई")
        t = t.replace("पुराना", "पुरानी")
        t = t.replace("सुंदर", "सुंदर")   // invariant
        t = t.replace("काला", "काली")
        t = t.replace("लाल", "लाल")        // invariant
        t = t.replace("पीला", "पीली")
        t = t.replace("हरा", "हरी")
        t = t.replace("नीला", "नीली")
        t = t.replace("सफेद", "सफेद")      // invariant
        t = t.replace("मोटा", "मोटी")
        t = t.replace("पतला", "पतली")
        t = t.replace("लंबा", "लंबी")
        t = t.replace("ऊँचा", "ऊँची")
        t = t.replace("नीचा", "नीची")
        t = t.replace("गरम", "गरम")        // invariant
        t = t.replace("ठंडा", "ठंडी")
        t = t.replace("भारी", "भारी")       // already fem
        t = t.replace("हल्का", "हल्की")
        t = t.replace("मेरा", "मेरी")
        t = t.replace("तेरा", "तेरी")
        t = t.replace("हमारा", "हमारी")
        t = t.replace("तुम्हारा", "तुम्हारी")
        t = t.replace("उसका", "उसकी")
        t = t.replace("इसका", "इसकी")
        t = t.replace("उनका", "उनकी")
        t = t.replace("इनका", "इनकी")
        t = t.replace("आपका", "आपकी")

        // ── Adverbs with gender agreement ─────────────────────────────────────
        t = t.replace("तैयार हुआ", "तैयार हुई")
        t = t.replace("खुश हुआ", "खुश हुई")
        t = t.replace("दुखी हुआ", "दुखी हुई")
        t = t.replace("परेशान हुआ", "परेशान हुई")
        t = t.replace("थका हुआ", "थकी हुई")
        t = t.replace("जागा हुआ", "जागी हुई")
        t = t.replace("सोया हुआ", "सोई हुई")
        t = t.replace("बैठा हुआ", "बैठी हुई")
        t = t.replace("खड़ा हुआ", "खड़ी हुई")
        t = t.replace("लेटा हुआ", "लेटी हुई")

        // ── Continuous (rahi/raha) ─────────────────────────────────────────────
        t = t.replace("रहा हूँ", "रही हूँ")   // idempotent guard
        t = t.replace("रहा हूं", "रही हूं")

        return t
    }
}
