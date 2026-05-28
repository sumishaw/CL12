package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * LiveCaptionReader v6 — KEY FIX: use windows list, NOT rootInActiveWindow
 *
 * rootInActiveWindow returns the FOCUSED window (Termux, browser etc.)
 * We must iterate windows list and find the com.google.android.as window specifically.
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000
        private const val DEBOUNCE_MS     = 400L

        @Volatile var isRunning       = false
        @Volatile var lastCaptionText = ""
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:    Job? = null
    private var translateJob:  Job? = null
    private var lastSentText   = ""
    private var lastHindiOut   = ""
    private val translateQueue = LinkedBlockingQueue<String>(8)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 50
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            // Monitor confirmed Live Captions package only
            info.packageNames = LIVE_CAPTION_PACKAGES.toTypedArray()
        }

        startTranslateWorker()
        Log.i(TAG, "LiveCaptionReader v6 connected")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in LIVE_CAPTION_PACKAGES) return

        // CRITICAL FIX: find the com.google.android.as window from the windows list
        // Do NOT use rootInActiveWindow — that returns the focused window (wrong app)
        val captionText = readFromCaptionWindow() ?: return
        if (captionText == lastCaptionText) return
        lastCaptionText = captionText
        Log.d(TAG, "Caption: $captionText")
        scheduleTranslation(captionText)
    }

    private fun readFromCaptionWindow(): String? {
        // Iterate all windows and find the one owned by com.google.android.as
        val allWindows = try { windows } catch (_: Exception) { return null }
        if (allWindows.isNullOrEmpty()) return null

        for (window in allWindows) {
            val root = try { window.root } catch (_: Exception) { continue } ?: continue
            val windowPkg = root.packageName?.toString() ?: ""

            if (windowPkg in LIVE_CAPTION_PACKAGES) {
                // Found the Live Captions window — extract text from it
                val text = extractTextFromNode(root)
                root.recycle()
                if (!text.isNullOrBlank() && isValidCaption(text)) {
                    return text
                }
            } else {
                root.recycle()
            }
        }
        return null
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text   = node.text?.toString()?.trim() ?: ""

        // Check known caption view IDs first
        val isCaptionNode = listOf(
            "caption_text", "captiontext", "live_caption",
            "transcript", "caption_window", "subtitle"
        ).any { viewId.contains(it) }

        if (isCaptionNode && text.isNotBlank()) return text

        // Also return any non-empty text in this window
        // (Live Captions window has very few nodes — its main node IS the caption)
        if (text.length in 3..350) return text

        // Recurse into children
        for (i in 0 until node.childCount) {
            val found = extractTextFromNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 3 || text.length > 350) return false
        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.4) return false
        if (text.contains("http") || text.contains("www.")) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        return true
    }

    private fun scheduleTranslation(text: String) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (text == lastCaptionText && text != lastSentText) {
                lastSentText = text
                if (translateQueue.size >= 8) translateQueue.poll()
                translateQueue.offer(text)
            }
        }
    }

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank() || hindi == lastHindiOut) continue
                lastHindiOut = hindi

                Log.i(TAG, "✓ ${text.take(40)} → ${hindi.take(40)}")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"en","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Translate error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        pendingJob?.cancel(); translateJob?.cancel(); scope.cancel()
        super.onDestroy()
    }
}
