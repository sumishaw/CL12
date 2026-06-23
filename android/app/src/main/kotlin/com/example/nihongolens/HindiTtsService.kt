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
                    override fun onStart(id: String?)  { isSpeaking = true  }
                    override fun onDone(id: String?)   { isSpeaking = false }
                    override fun onError(id: String?)  { isSpeaking = false }
                })
                ttsReady = true
                Log.d(TAG, "TTS ready — voices: ${tts?.voices?.filter { it.locale.language == "hi" }?.map { it.name }}")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled || !ttsReady || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n

        val engine = tts ?: return
        val emotion = detectEmotion(hindi)

        // Speed: user multiplier × emotion modifier
        val speed = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)

        // Pitch: gender-based
        // Female voice: pitch < 1.0 gives softer, more feminine quality on male TTS engine
        // Male voice: pitch = 1.0 natural
        val gender = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        val pitch  = when (gender) {
            Gender.FEMALE -> 0.80f
            Gender.MALE   -> 1.00f
            Gender.AUTO   -> if (detectedGender == Gender.FEMALE) 0.80f else 1.00f
        }

        engine.setSpeechRate(speed)
        engine.setPitch(pitch)

        val id = "utt_${uttId++}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }

        // QUEUE_FLUSH: replace any pending utterance — stay near real-time
        // If you want every sentence spoken: use QUEUE_ADD instead
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

    fun isSuppressed() = isSpeaking

    fun destroy() {
        genderJob?.cancel()
        tts?.stop(); tts?.shutdown(); tts = null
        ttsReady = false
        scope.cancel()
    }

    // ── Gender detection via FFT on mic audio ─────────────────────────────────
    // Tablet mic picks up audio from tablet speakers.
    // FFT reveals fundamental frequency of the speaker's voice.
    // Male: 85-165 Hz | Female: 165-350 Hz

    private fun startGenderDetector() {
        if (genderJob?.isActive == true) return
        genderJob = scope.launch {
            val SR     = 16_000
            val N      = 2048       // FFT window — 128ms at 16kHz
            val minBuf = AudioRecord.getMinBufferSize(
                SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

            var rec: AudioRecord? = null
            for (src in intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT)) {
                try {
                    val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, N * 4))
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                    r.release()
                } catch (_: Exception) {}
            }

            if (rec == null) { Log.e(TAG, "Gender: AudioRecord init failed"); return@launch }

            Log.d(TAG, "Gender detector started (FFT on mic)")
            rec.startRecording()
            val buf  = ShortArray(N)
            val real = FloatArray(N)
            val imag = FloatArray(N)

            while (isActive) {
                // Pause during TTS — avoid detecting own voice
                if (isSpeaking) { delay(200); continue }

                val read = rec.read(buf, 0, N)
                if (read < N) { delay(50); continue }

                // RMS check — skip silence
                var sum = 0.0
                for (i in 0 until N) sum += buf[i].toLong() * buf[i]
                val rms = sqrt(sum / N)
                if (rms < 80) { delay(30); continue }

                // Apply Hann window + copy to real array
                for (i in 0 until N) {
                    val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (N - 1)))
                    real[i] = buf[i] * w / 32768f
                    imag[i] = 0f
                }

                // FFT (radix-2 Cooley-Tukey)
                fft(real, imag, N)

                // Find peak frequency in 80-400 Hz range (vocal fundamental)
                val binLow  = (80f  * N / SR).toInt().coerceAtLeast(1)
                val binHigh = (400f * N / SR).toInt().coerceAtMost(N / 2)

                var peakBin = binLow
                var peakMag = 0f
                for (b in binLow..binHigh) {
                    val mag = sqrt(real[b] * real[b] + imag[b] * imag[b])
                    if (mag > peakMag) { peakMag = mag; peakBin = b }
                }

                val f0 = peakBin.toFloat() * SR / N

                // Require minimum signal strength to avoid noise
                if (peakMag < 0.002f) { delay(50); continue }

                val g = if (f0 >= 165f) Gender.FEMALE else Gender.MALE

                genderHistory.addLast(g)
                if (genderHistory.size > GENDER_HIST) genderHistory.removeFirst()

                val fCount = genderHistory.count { it == Gender.FEMALE }
                val result = if (fCount > genderHistory.size / 2) Gender.FEMALE else Gender.MALE
                if (result != detectedGender) {
                    detectedGender = result
                    Log.d(TAG, "Gender → $result (F0=${f0.toInt()}Hz mag=${peakMag} f=$fCount/${genderHistory.size})")
                }
                delay(100)
            }

            try { rec.stop(); rec.release() } catch (_: Exception) {}
        }
    }

    // Radix-2 Cooley-Tukey FFT (in-place)
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }
                         im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2f * PI.toFloat() / len
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var uRe = 1f; var uIm = 0f
                for (k in 0 until len / 2) {
                    val tRe = uRe * re[i+k+len/2] - uIm * im[i+k+len/2]
                    val tIm = uRe * im[i+k+len/2] + uIm * re[i+k+len/2]
                    re[i+k+len/2] = re[i+k] - tRe; im[i+k+len/2] = im[i+k] - tIm
                    re[i+k] += tRe; im[i+k] += tIm
                    val newURe = uRe * wRe - uIm * wIm
                    uIm = uRe * wIm + uIm * wRe; uRe = newURe
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
