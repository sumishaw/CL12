package com.example.nihongolens

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * CaptionLogger v2 — Advanced debug logger for Caption Lens
 * Buffer: 2000 lines | Levels: D/I/W/E | Download to Downloads/
 */
object CaptionLogger {

    private const val LOGCAT_TAG = "CaptionLens"
    private const val MAX_LINES  = 2000

    // Severity levels — used as CaptionLogger.LEVEL_WARN etc. to avoid inner-class issues
    const val LEVEL_DEBUG = "D"
    const val LEVEL_INFO  = "I"
    const val LEVEL_WARN  = "W"
    const val LEVEL_ERROR = "E"

    private val fmt     = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val buffer  = LinkedList<String>()
    private val lock    = Any()

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private var sessionStartMs     = System.currentTimeMillis()
    private var subtitleGoneCount  = 0
    private var lastSubtitleText   = ""
    private var lastSubtitleTimeMs = 0L

    // ── Init ──────────────────────────────────────────────────────────────────

    @JvmStatic
    fun init(context: Context) {
        sessionStartMs = System.currentTimeMillis()
        log("Logger", "=== Caption Lens v2 Logger ready — buffer=$MAX_LINES lines ===")
    }

    // ── Core log (2-arg overload for backward compatibility) ──────────────────

    @JvmStatic
    fun log(tag: String, msg: String) = log(tag, msg, LEVEL_INFO)

    @JvmStatic
    fun log(tag: String, msg: String, level: String) {
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000.0
        val line = "${fmt.format(Date())} [$level][$tag] $msg  (+${"%.1f".format(elapsed)}s)"
        when (level) {
            LEVEL_DEBUG -> android.util.Log.d(LOGCAT_TAG, "[$tag] $msg")
            LEVEL_WARN  -> android.util.Log.w(LOGCAT_TAG, "[$tag] $msg")
            LEVEL_ERROR -> android.util.Log.e(LOGCAT_TAG, "[$tag] $msg")
            else        -> android.util.Log.i(LOGCAT_TAG, "[$tag] $msg")
        }
        synchronized(lock) {
            buffer.addLast(line)
            if (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        counters.getOrPut(tag) { AtomicLong(0) }.incrementAndGet()
    }

    @JvmStatic
    fun logState(tag: String, key: String, value: String) = log(tag, "$key=$value")

    // ── Overlay state tracking ────────────────────────────────────────────────

    @JvmStatic
    fun onOverlayTextSet(text: String, alpha: Float, visible: Boolean) {
        if (text.isNotBlank()) {
            lastSubtitleText   = text
            lastSubtitleTimeMs = System.currentTimeMillis()
        }
        log("Overlay", "setText='${text.take(40)}' alpha=${"%.2f".format(alpha)} vis=$visible", LEVEL_DEBUG)
    }

    @JvmStatic
    fun onOverlayFadeOut(reason: String) {
        val age = System.currentTimeMillis() - lastSubtitleTimeMs
        log("Overlay", "FADE-OUT reason=$reason lastText='${lastSubtitleText.take(30)}' age=${age}ms", LEVEL_WARN)
    }

    @JvmStatic
    fun onOverlayGone(reason: String) {
        subtitleGoneCount++
        val age = System.currentTimeMillis() - lastSubtitleTimeMs
        log("Overlay",
            "SUBTITLE-GONE #$subtitleGoneCount reason=$reason " +
            "lastText='${lastSubtitleText.take(30)}' age=${age}ms",
            LEVEL_ERROR)
        // State snapshot — TTS.enabled logged here; queue sizes logged by HindiTtsService itself
        log("Overlay",
            "STATE: TTS.enabled=${HindiTtsService.enabled} speaking=${HindiTtsService.isSpeaking}",
            LEVEL_ERROR)
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @JvmStatic
    fun getStats(): String {
        val top = counters.entries
            .sortedByDescending { it.value.get() }
            .take(6)
            .joinToString(" | ") { "${it.key}:${it.value.get()}" }
        return synchronized(lock) {
            "Lines:${buffer.size}/$MAX_LINES | Gone:$subtitleGoneCount | $top"
        }
    }

    // ── Retrieval ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun getRecentLines(n: Int = MAX_LINES): String = synchronized(lock) {
        if (buffer.size <= n) buffer.joinToString("\n")
        else buffer.toList().takeLast(n).joinToString("\n")
    }

    @JvmStatic
    fun clearLines() {
        synchronized(lock) { buffer.clear() }
        counters.clear()
        subtitleGoneCount  = 0
        lastSubtitleText   = ""
        lastSubtitleTimeMs = 0L
        sessionStartMs     = System.currentTimeMillis()
        log("Logger", "=== Log buffer reset ===")
    }

    @JvmStatic
    fun stop() = log("Logger", "=== Logger stopped === ${getStats()}")

    // ── Download to /storage/emulated/0/captionlog/ ───────────────────────────

    @JvmStatic
    fun downloadLogs(context: Context): String = try {
        val fname = "captionlens_${dateFmt.format(Date())}.log"
        val dir   = File("/storage/emulated/0/captionlog")
        dir.mkdirs()
        val file  = File(dir, fname)
        val header = "=== Caption Lens Debug Log ===\n" +
            "Date: ${Date()}\n" +
            "Device: ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}\n" +
            "Stats: ${getStats()}\n" +
            "=".repeat(60) + "\n\n"
        file.writeText(header + getRecentLines())
        log("Logger", "Saved: ${file.absolutePath}")
        file.absolutePath
    } catch (e: Exception) {
        log("Logger", "Download failed: ${e.message}", LEVEL_ERROR)
        "Error: ${e.message}"
    }

    @JvmStatic
    fun getLogPath(): String = "/storage/emulated/0/captionlog/captionlens_*.log"
}
