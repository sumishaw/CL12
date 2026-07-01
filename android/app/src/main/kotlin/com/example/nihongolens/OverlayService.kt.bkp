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

/**
 * OverlayService v3 — Radical simplification
 *
 * DESIGN PRINCIPLE: The overlay is a dumb display surface.
 * It shows whatever text it's given. It never decides to hide text.
 * It only fades after 5 minutes of absolute silence.
 *
 * NO tokens, NO backlog, NO advance(), NO active flag, NO holdRunnable.
 * Every subtitle update calls setTextDirect() and that's it.
 *
 * The old complexity (tokens, expectedToken, advance, active, holdRunnable)
 * created race conditions where multiple timers competed and left the
 * overlay stuck with no error. Removed entirely.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile @JvmField var holdMs: Long = 0L  // kept for API compat, unused in v3
        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile var instance: OverlayService? = null

        // ── Public API ────────────────────────────────────────────────────────

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            if (hindi.isNotBlank()) {
                instance?.showText(hindi)
            }
        }

        fun showTtsText(hindi: String) {
            if (hindi.isNotBlank()) {
                instance?.showText(hindi)
            }
        }

        // Called when TTS finishes — no-op in v3 (overlay keeps showing)
        fun clearTtsText() { /* intentionally empty — overlay never clears on TTS end */ }

        // Called on explicit stop
        fun clearQueue() {
            instance?.handler?.post { instance?.fadeOut() }
        }

        fun setHoldMs(ms: Long) { holdMs = ms.coerceIn(0, 15_000) }
    }

    val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager?              = null
    var textView:      TextView?                   = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null

    @Volatile private var alive     = true
    @Volatile private var viewAdded = false

    // Silence timer — the ONLY timer in v3
    private var silenceToken = 0  // increment to invalidate old timer
    private val SILENCE_MS   = 300_000L  // 5 minutes

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { buildOverlay() }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false; instance = null
        handler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Core display — the ONLY path ─────────────────────────────────────────

    // Called from any thread — posts to main thread
    fun showText(text: String) {
        if (text.isBlank()) return
        handler.post { setTextDirect(text) }
    }

    // Must run on main thread
    private fun setTextDirect(text: String) {
        if (!alive) return
        val tv = textView ?: return
        if (text.isBlank()) return

        // Show text immediately, always
        tv.text = text
        tv.animate().cancel()
        tv.alpha = 1f
        tv.visibility = View.VISIBLE

        CaptionLogger.onOverlayTextSet(text, 1f, true)

        // Reset 5-minute silence timer
        resetSilenceTimer()
    }

    private fun fadeOut() {
        if (!alive) return
        silenceToken++  // invalidate any pending reschedule
        CaptionLogger.onOverlayFadeOut("silence_5min")
        textView?.animate()?.cancel()
        textView?.animate()?.alpha(0f)?.setDuration(500)
            ?.withEndAction {
                textView?.text = ""
                CaptionLogger.onOverlayGone("fade_complete")
            }?.start()
    }

    private fun resetSilenceTimer() {
        val myToken = ++silenceToken
        handler.postDelayed({
            if (myToken != silenceToken) return@postDelayed  // newer text arrived
            if (!alive) return@postDelayed
            // Only fade if TTS is not currently speaking
            if (HindiTtsService.isSpeaking) {
                // TTS still active — postpone fade
                resetSilenceTimer()
                return@postDelayed
            }
            CaptionLogger.log("Overlay", "5min silence — fading out")
            fadeOut()
        }, SILENCE_MS)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.WHITE)
                setShadowLayer(12f, 1f, 2f, Color.BLACK)
                maxLines   = 3
                setLineSpacing(2f, 1.1f)
                setBackgroundColor(android.graphics.Color.argb(160, 0, 0, 0))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                alpha = 0f; text = ""
            }
            textView = tv; overlayView = tv
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(60) }

            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            tv.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx=ev.rawX; sy=ev.rawY; ix=p.x; iy=p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix+(ev.rawX-sx).toInt(); p.y = iy-(ev.rawY-sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView,p) }
                        catch (_:Exception){}
                    }
                }
                true
            }
            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) { android.util.Log.e("OverlayService","build: ${e.message}") }
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
