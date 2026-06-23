package com.example.nihongolens

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.*

/**
 * HindiTtsService — Zero-latency Hindi TTS using Android built-in engine.
 *
 * Why Android TTS instead of Kokoro HTTP:
 *  - Zero network latency — speaks instantly, no WAV download
 *  - No gap between sentences — engine queues utterances internally
 *  - Runs on-device, fully offline
 *  - Hindi voice available via Google TTS (install hi_IN voice pack)
 *
 * Gender detection via AudioRecord FFT:
 *  - Tablet mic picks up speaker audio from tablet speakers
 *  - FFT on 100ms windows → find dominant frequency peak
 *  - Male fundamental: 85-165 Hz  → sid=33 (hm_omega)
 *  - Female fundamental: 165-350 Hz → sid=31 (hf_alpha)
 *  - Smoothed over 8 windows, paused during TTS (avoid self-detection)
 *
 * Subtitle sync: subtitle updates immediately on translation.
 * TTS speaks at 1.5x default speed, adjustable 1x-4x.
 */
object HindiTtsService {

    private const val TAG = "HindiTTS"

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false

    private var tts: TextToSpeech?  = null
    private var ttsReady            = false
    private var ctx: Context?       = null
    private val mainHandler         = Handler(Looper.getMainLooper())
    private val scope               = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cachedHindiVoices   = listOf<android.speech.tts.Voice>()

    // Dedup
    private var lastSpokenNorm = ""
    private var uttId          = 0

    // Gender
    private val genderHistory  = ArrayDeque<Gender>()
    private val GENDER_HIST    = 8
    private var genderJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        ctx = context.applicationContext
        initTts(context)
        startGenderDetector()
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("hi", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Hindi voice missing — using default with pitch adjustment")
                    tts?.setLanguage(Locale.ENGLISH)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?)  {
                        isSpeaking = true
                    }
                    override fun onDone(id: String?)   {
                        // Check if more utterances queued — if not, set grace period
                        // Grace period prevents mic from picking up echo of last words
                        if (tts?.isSpeaking == false) {
                            isSpeaking = false
                            speakingUntilMs = System.currentTimeMillis() + 3_000L
                        }
                        // If more queued, isSpeaking stays true until all done
                    }
                    override fun onError(id: String?)  {
                        isSpeaking = false
                        speakingUntilMs = System.currentTimeMillis() + 3_000L
                    }
                })
                ttsReady = true

                // Select specific Hindi voices:
                // Voice I   = mature female, Voice II  = young female
                // Voice III = mature male,   Voice IV  = young male
                // We use Voice II (young female) and Voice IV (young male)
                val allVoices = tts?.voices ?: emptySet()
                val hindiVoices = allVoices
                    .filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }
                    .sortedBy { it.name }
                Log.d(TAG, "Hindi voices available: ${hindiVoices.map { it.name }}")
                // Cache all hindi voices for dynamic switching in speak()
                cachedHindiVoices = hindiVoices
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    @Volatile private var speakingUntilMs = 0L   // grace period after TTS ends

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled || !ttsReady || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n

        val engine = tts ?: return
        val emotion = detectEmotion(hindi)
        val speed = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val gender = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        val pitch  = if (gender == Gender.FEMALE) 0.80f else 1.00f

        // Select specific voice by name:
        // Voice I   = mature female  (index 0)
        // Voice II  = young female   (index 1) ← use for FEMALE
        // Voice III = mature male    (index 2)
        // Voice IV  = young male     (index 3) ← use for MALE
        if (cachedHindiVoices.isNotEmpty()) {
            val targetName = when (gender) {
                Gender.FEMALE -> "Voice II"    // young female
                else          -> "Voice IV"    // young male
            }
            val voice = cachedHindiVoices.firstOrNull { it.name.contains(targetName) }
                ?: cachedHindiVoices.firstOrNull { it.name.contains(
                    if (gender == Gender.FEMALE) "Voice I" else "Voice III") }
                ?: cachedHindiVoices.first()
            engine.voice = voice
            Log.d(TAG, "Voice: ${voice.name} gender=$gender")
        } else {
            engine.setPitch(pitch)
        }

        engine.setSpeechRate(speed)
        engine.setPitch(pitch)

        val id = "utt_${uttId++}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            // STREAM_MUSIC — plays through speakers so mic can detect gender
            // Loop prevention handled by Devanagari blocking + isSuppressed() grace period
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC.toString())
        }

        // Mark as speaking BEFORE queuing — ensures isSuppressed() is true
        // immediately so LiveCaptionReader blocks before TTS even starts
        isSpeaking = true

        engine.speak(hindi, TextToSpeech.QUEUE_ADD, params, id)
        Log.d(TAG, "TTS speak speed=$speed pitch=$pitch gender=$gender '${hindi.take(30)}'")
    }

    fun stopCurrent() {
        tts?.stop()
        isSpeaking = false
        lastSpokenNorm = ""
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) { tts?.stop(); isSpeaking = false; lastSpokenNorm = "" }
    }

    fun setGender(g: Gender) {
        selectedGender = g
        if (g != Gender.AUTO) { genderJob?.cancel(); genderJob = null }
        else startGenderDetector()
    }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun destroy() {
        genderJob?.cancel()
        tts?.stop(); tts?.shutdown(); tts = null
        ttsReady = false
        scope.cancel()
    }

    // ── Gender detection via mic FFT (HPS method) ────────────────────────────
    // Mic picks up speaker audio from tablet speakers.
    // Harmonic Product Spectrum finds F0 accurately even when harmonics dominate.
    // Male F0: 85-165 Hz | Female F0: 165-350 Hz

    private fun startGenderDetector() {
        if (genderJob?.isActive == true) return
        genderJob = scope.launch {
            val SR  = 16_000
            val N   = 4096    // larger window = better frequency resolution
            val MIN_BUF = AudioRecord.getMinBufferSize(
                SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

            var rec: AudioRecord? = null
            for (src in intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT)) {
                try {
                    val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(MIN_BUF, N * 2))
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                    r.release()
                } catch (_: Exception) {}
            }
            if (rec == null) { Log.e(TAG, "Gender: AudioRecord failed"); return@launch }
            Log.d(TAG, "Gender detector started (HPS FFT, SR=$SR N=$N)")
            rec.startRecording()

            val buf  = ShortArray(N)
            val re   = FloatArray(N)
            val im   = FloatArray(N)

            while (isActive) {
                if (isSuppressed()) { delay(300); continue }  // skip during TTS + grace

                val read = rec.read(buf, 0, N)
                if (read < N) { delay(50); continue }

                // RMS energy check
                var energy = 0.0
                for (i in 0 until N) energy += buf[i].toLong() * buf[i]
                val rms = sqrt(energy / N)
                if (rms < 40) { delay(50); continue }   // silence

                // Hann window
                for (i in 0 until N) {
                    val w = 0.5f * (1f - cos(2.0 * PI * i / (N - 1)).toFloat())
                    re[i] = buf[i] * w / 32768f
                    im[i] = 0f
                }

                fft(re, im, N)

                // Magnitude spectrum
                val mag = FloatArray(N / 2) { b -> sqrt(re[b]*re[b] + im[b]*im[b]) }

                // HPS: multiply mag[f] × mag[2f] × mag[3f] × mag[4f]
                // This suppresses harmonics and amplifies the true F0
                val binLo = (60f * N / SR).toInt().coerceAtLeast(2)
                val binHi = (420f * N / SR).toInt().coerceAtMost(N / 5)

                var bestBin = binLo
                var bestHPS = 0f
                for (b in binLo..binHi) {
                    val h1 = mag[b]
                    val h2 = if (b*2 < mag.size) mag[b*2] else 0f
                    val h3 = if (b*3 < mag.size) mag[b*3] else 0f
                    val h4 = if (b*4 < mag.size) mag[b*4] else 0f
                    val hps = h1 * (h2 + 0.001f) * (h3 + 0.001f) * (h4 + 0.001f)
                    if (hps > bestHPS) { bestHPS = hps; bestBin = b }
                }

                if (mag[bestBin] < 0.0002f) { delay(50); continue }  // too quiet

                val f0 = bestBin.toFloat() * SR / N
                val g  = if (f0 >= 165f) Gender.FEMALE else Gender.MALE

                genderHistory.addLast(g)
                if (genderHistory.size > GENDER_HIST) genderHistory.removeFirst()

                val fCount = genderHistory.count { it == Gender.FEMALE }
                // Require 70% majority to switch — stable detection
                val newGender = when {
                    fCount >= (GENDER_HIST * 0.7).toInt() -> Gender.FEMALE
                    (genderHistory.size - fCount) >= (GENDER_HIST * 0.7).toInt() -> Gender.MALE
                    else -> detectedGender   // not confident yet — keep current
                }
                if (newGender != detectedGender) {
                    detectedGender = newGender
                    Log.d(TAG, "Gender → $newGender F0=${f0.toInt()}Hz rms=${rms.toInt()} f=$fCount/${genderHistory.size}")
                }
                delay(80)
            }
            try { rec.stop(); rec.release() } catch (_: Exception) {}
        }
    }

    // Cooley-Tukey radix-2 FFT (in-place)
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var uRe = 1f; var uIm = 0f
                for (k in 0 until len / 2) {
                    val tRe = uRe * re[i+k+len/2] - uIm * im[i+k+len/2]
                    val tIm = uRe * im[i+k+len/2] + uIm * re[i+k+len/2]
                    re[i+k+len/2] = re[i+k] - tRe
                    im[i+k+len/2] = im[i+k] - tIm
                    re[i+k] += tRe; im[i+k] += tIm
                    val nRe = uRe * wRe - uIm * wIm
                    uIm = uRe * wIm + uIm * wRe; uRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }


    // ── Emotion ───────────────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","sad","cry","sorry").any    { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn").any        { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","amazing","खुश","love").any    { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED -> 1.10f
        Emotion.HAPPY   -> 1.05f
        Emotion.CURIOUS -> 0.97f
        Emotion.SAD     -> 0.88f
        Emotion.ANGRY   -> 1.08f
        Emotion.NEUTRAL -> 1.00f
    }
}
