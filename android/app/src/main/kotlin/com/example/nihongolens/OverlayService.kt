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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * OverlayService — Progressive word-by-word Hindi subtitle overlay
 *
 * Same data source as green UI subtitle → no skipping.
 * Each sentence streams word-by-word, then holds for holdMsVar before clearing.
 * FIFO queue with tokens — sentences show in order, stale items discarded on clear.
 *
 * Speed presets (set via setHoldMs):
 *   Fastest = 2s, Fast = 4s, Average = 6s, Slow = 8s, Slowest = 10s
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""

        @Volatile private var pushCallback:  ((String, String) -> Unit)? = null
        @Volatile private var clearCallback: (() -> Unit)?               = null
        @Volatile private var holdCallback:  ((Long) -> Unit)?           = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }

        fun clearQueue() { clearCallback?.invoke() }

        fun setHoldMs(ms: Long) { holdCallback?.invoke(ms) }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    // User-controlled hold time — set via preset buttons, never mid-cycle
    @Volatile private var holdMsVar = 6_000L   // default: Average

    // FIFO sentence queue
    private val tokenCounter  = AtomicLong(0)
    @Volatile private var expectedToken = 0L

    data class Item(val token: Long, val text: String)
    private val queue = LinkedBlockingQueue<Item>()

    // Display state — only touched on main thread
    private var isRunning_    = false   // display loop active
    private var currentToken_ = -1L

    private val handler = Handler(Looper.getMainLooper())
    private var wordRunnable: Runnable? = null
    private var holdRunnable: Runnable? = null
    private var silenceRunnable: Runnable? = null

    // Overlay views
    private var windowManager: WindowManager?              = null
    private var textView:      TextView?                   = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { if (running) buildOverlay() }

        pushCallback  = { _, hindi -> handler.post { onPush(hindi) } }
        clearCallback = { handler.post { onClear() } }
        holdCallback  = { ms ->
            val newMs = ms.coerceIn(0, 15_000)   // 0 = live
            val wasLive = holdMsVar == LIVE_MODE
            holdMsVar = newMs

            handler.post {
                if (newMs == LIVE_MODE) {
                    // Switching TO live — cancel any pending word/hold timers
                    cancelAll(); isRunning_ = false; queue.clear()
                } else if (wasLive) {
                    // Switching FROM live — nothing pending, ready for next push
                } else {
                    // Speed change between non-live presets — reschedule pending hold
                    val pending = holdRunnable
                    if (pending != null) {
                        handler.removeCallbacks(pending)
                        holdRunnable = null
                        handler.postDelayed(pending, newMs)
                        holdRunnable = pending
                    }
                }
            }
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        pushCallback = null; clearCallback = null; holdCallback = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Queue management ──────────────────────────────────────────────────────

    // holdMsVar = 0 means LIVE mode — show instantly, no word-by-word, no hold
    private val LIVE_MODE = 0L

    private fun onPush(hindi: String) {
        if (hindi.isBlank()) return
        val token = tokenCounter.incrementAndGet()

        // LIVE mode: skip queue entirely, show text immediately
        if (holdMsVar == LIVE_MODE) {
            cancelAll()
            isRunning_ = false
            setText(hindi.trim())
            reschedSilence()
            return
        }

        queue.put(Item(token, hindi.trim()))
        reschedSilence()
        if (!isRunning_) showNext()
    }

    private fun onClear() {
        // Advance expectedToken — all pending items silently skipped
        expectedToken = tokenCounter.get() + 1
        queue.clear()
        cancelAll()
        isRunning_    = false
        currentToken_ = -1L
        fadeOut()
    }

    // ── Display loop ──────────────────────────────────────────────────────────

    private fun showNext() {
        cancelAll()

        // Drain stale
        while (true) {
            val head = queue.peek() ?: break
            if (head.token >= expectedToken) break
            queue.poll()
        }

        val item = queue.poll() ?: run {
            isRunning_ = false
            return
        }

        if (item.token < expectedToken) { showNext(); return }

        currentToken_ = item.token
        isRunning_    = true

        // Stream words one by one
        val words = item.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        var index = 0
        var displayed = ""

        fun tick() {
            wordRunnable = null
            if (!running || item.token < expectedToken) {
                isRunning_ = false; fadeOut(); return
            }
            if (index >= words.size) {
                // Sentence complete — hold then advance
                scheduleHold(item.token)
                return
            }
            displayed = if (displayed.isEmpty()) words[index] else "$displayed ${words[index]}"
            index++
            setText(displayed)

            // Schedule next word — interval based on sentence length for natural pace
            // Short sentences: slower (more time per word)
            // Long sentences: faster (need to fit in holdMsVar)
            val totalWords  = words.size.coerceAtLeast(1)
            val msPerWord   = (holdMsVar * 0.6 / totalWords).toLong().coerceIn(80, 400)
            wordRunnable    = Runnable { tick() }
            handler.postDelayed(wordRunnable!!, msPerWord)
        }

        tick()
    }

    private fun scheduleHold(token: Long) {
        val capturedHold = holdMsVar   // read current value
        holdRunnable = Runnable {
            holdRunnable = null
            if (!running) return@Runnable
            if (token < expectedToken) { isRunning_ = false; fadeOut(); return@Runnable }
            fadeOut()
            isRunning_ = false
            if (queue.isNotEmpty()) {
                handler.postDelayed({ showNext() }, 150)
            }
        }
        handler.postDelayed(holdRunnable!!, capturedHold)
    }

    private fun cancelAll() {
        wordRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable?.let { handler.removeCallbacks(it) }
        wordRunnable = null; holdRunnable = null
    }

    private fun reschedSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running || queue.isNotEmpty() || isRunning_) return@Runnable
            fadeOut()
        }
        handler.postDelayed(silenceRunnable!!, 10_000L)
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun setText(text: String) {
        val tv = textView ?: return
        tv.text = text
        if (tv.alpha < 0.5f) tv.animate().cancel().also { tv.animate().alpha(1f).setDuration(120).start() }
    }

    private fun fadeOut() {
        textView?.animate()?.cancel()
        textView?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            textView?.text = ""
        }?.start()
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Color.WHITE)
                setShadowLayer(14f, 1f, 3f, Color.BLACK)
                maxLines   = 2
                background = null   // no box — text only
                setPadding(dp(8), dp(4), dp(8), dp(4))
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
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(90)
            }

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

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "build: ${e.message}")
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
