package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicLong

/**
 * OverlayService — Progressive word-by-word Hindi subtitle overlay
 *
 * Single TextView, maxLines=2, words stream in at WORD_INTERVAL_MS pace.
 * When sentence complete or 2 lines full → hold HOLD_MS → clear → next sentence.
 * FIFO + token: no drops, no duplicates, no stale items after LC gone.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback:  ((String, String) -> Unit)? = null
        @Volatile private var clearCallback: (() -> Unit)?               = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original; latestHindi = hindi
            pushCallback?.invoke(original, hindi)
        }
        fun clearQueue() { clearCallback?.invoke() }
    }

    // ── Timing ────────────────────────────────────────────────────────────────
    // Words appear instantly (0ms gap) — fills 2 lines fast matching speech pace
    // When 2 lines full: hold HOLD_MS so user can read, then clear and continue
    // LINE_CHARS: approximate chars that fit 2 lines at 20sp on a tablet
    private val WORD_INTERVAL_MS = 0L      // instant — words pop in fast
    private val HOLD_MS          = 3_500L  // read time when 2 lines full
    private val SILENCE_MS       = 8_000L  // fade after no speech
    // Hindi words average 5-8 chars. At 20sp on a 12-inch tablet, ~32 chars per line.
    // 2 lines = ~64 chars. Use 90 to be safe and truly fill both lines.
    private val LINE_CHARS       = 90

    private val tokenCounter  = AtomicLong(0)
    private var expectedToken = 0L

    data class Item(val token: Long, val words: List<String>)
    private val queue = ArrayDeque<Item>()

    private var currentWords  = listOf<String>()   // kept for compat — not used in display
    private var wordIndex     = 0
    private var displayedText = ""
    private var isProgressing = false
    private var currentToken  = -1L

    private var wordRunnable:    Runnable? = null
    private var holdRunnable:    Runnable? = null
    private var silenceRunnable: Runnable? = null

    private var windowManager: WindowManager?              = null
    private var textView:      TextView?                   = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null
    private val handler        = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else
                startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        handler.post { if (running) buildOverlay() }
        pushCallback  = { _, hindi -> handler.post { onPush(hindi) } }
        clearCallback = { handler.post { onClear() } }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        pushCallback = null; clearCallback = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Push / Clear ──────────────────────────────────────────────────────────

    private fun onPush(hindi: String) {
        if (hindi.isBlank()) return
        val words = hindi.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        val token = tokenCounter.incrementAndGet()
        queue.addLast(Item(token, words))
        reschedSilence()
        if (!isProgressing) startNext()
        // If progressing, new words will be picked up by holdThenWait or next tickWords cycle
    }

    private fun onClear() {
        expectedToken = tokenCounter.get() + 1
        queue.clear()
        cancelAll()
        allWords      = listOf()
        allIndex      = 0
        isProgressing = false
        currentToken  = -1L
        hideText()
    }

    // ── Progressive display ───────────────────────────────────────────────────

    // allWords: flat list of all words from all currently queued sentences
    // Rebuilt whenever startNext() or onPush() kicks display
    private var allWords   = listOf<String>()
    private var allIndex   = 0

    private fun startNext() {
        cancelAll()
        while (queue.isNotEmpty() && queue.first().token < expectedToken)
            queue.removeFirst()
        if (queue.isEmpty()) { isProgressing = false; return }

        // Drain ALL queued sentences into one flat word list
        // This lets us fill 2 lines with content from multiple sentences
        val words = mutableListOf<String>()
        val tokens = mutableListOf<Long>()
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (item.token < expectedToken) continue
            words.addAll(item.words)
            tokens.add(item.token)
        }
        if (words.isEmpty()) { isProgressing = false; return }

        allWords      = words
        allIndex      = 0
        currentToken  = tokens.last()
        isProgressing = true
        displayedText = ""
        hideText()
        tickWords()
    }

    private fun tickWords() {
        wordRunnable = null
        if (!running || currentToken < expectedToken) {
            isProgressing = false; hideText(); return
        }

        if (allIndex >= allWords.size) {
            // All words shown — hold then wait for new content
            holdThenWait(); return
        }

        // Append next word
        val word = allWords[allIndex++]
        displayedText = if (displayedText.isEmpty()) word else "$displayedText $word"
        showText(displayedText)

        // Check if 2 lines are full
        if (displayedText.length >= LINE_CHARS) {
            // 2 lines full — hold so user can read, then clear and continue
            val cap = currentToken
            holdRunnable = Runnable {
                holdRunnable = null
                if (!running || cap < expectedToken) { hideText(); isProgressing = false; return@Runnable }
                displayedText = ""
                hideText()
                // If more words remain in allWords, continue ticking
                if (allIndex < allWords.size) {
                    tickWords()
                } else if (queue.isNotEmpty()) {
                    startNext()
                } else {
                    isProgressing = false
                }
            }
            handler.postDelayed(holdRunnable!!, HOLD_MS)
            return
        }

        // More words — schedule next immediately or after interval
        wordRunnable = Runnable { tickWords() }
        if (WORD_INTERVAL_MS > 0) handler.postDelayed(wordRunnable!!, WORD_INTERVAL_MS)
        else handler.post(wordRunnable!!)
    }

    private fun holdThenWait() {
        val cap = currentToken
        holdRunnable = Runnable {
            holdRunnable = null
            if (!running) return@Runnable
            if (cap < expectedToken) { isProgressing = false; hideText(); return@Runnable }
            if (queue.isNotEmpty()) {
                // Seamlessly continue with newly arrived sentences
                val words = mutableListOf<String>()
                val tokens = mutableListOf<Long>()
                while (queue.isNotEmpty()) {
                    val item = queue.removeFirst()
                    if (item.token < expectedToken) continue
                    words.addAll(item.words)
                    tokens.add(item.token)
                }
                if (words.isNotEmpty()) {
                    // Append new words to current display without clearing
                    allWords  = allWords + words
                    currentToken = tokens.last()
                    tickWords()
                    return@Runnable
                }
            }
            // Nothing new — clear and wait
            hideText()
            isProgressing = false
        }
        handler.postDelayed(holdRunnable!!, HOLD_MS)
    }

    // ── Display helpers ───────────────────────────────────────────────────────

    private fun showText(text: String) {
        val tv = textView ?: return
        tv.text  = text
        if (tv.alpha < 0.5f) {
            tv.animate().cancel()
            tv.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun hideText() {
        val tv = textView ?: return
        tv.animate().cancel()
        tv.alpha = 0f
        tv.text  = ""
        displayedText = ""
    }

    private fun cancelAll() {
        wordRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable?.let { handler.removeCallbacks(it) }
        wordRunnable = null; holdRunnable = null
    }

    private fun reschedSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running || queue.isNotEmpty() || isProgressing) return@Runnable
            hideText()
        }
        handler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, Color.BLACK)
                maxLines  = 2
                setLineSpacing(dp(4).toFloat(), 1f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(190, 0, 0, 0))
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
                alpha = 0f
                text  = ""
            }
            textView    = tv
            overlayView = tv

            params = WindowManager.LayoutParams(
                (sw * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(90) }

            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            tv.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ix = p.x; iy = p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix + (ev.rawX - sx).toInt()
                        p.y = iy - (ev.rawY - sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView, p) }
                        catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(tv, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
