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
import android.view.animation.AlphaAnimation
import android.view.animation.TranslateAnimation
import android.view.animation.AnimationSet
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * OverlayService — Rolling subtitle display (like Live Captions)
 *
 * Shows up to 3 lines of Hindi subtitles.
 * New line appears at bottom, old lines scroll up and fade out.
 * Mimics exactly how Live Captions displays text.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback: ((String, String) -> Unit)? = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }
    }

    // Max lines to show at once (like Live Captions)
    private val MAX_LINES    = 3
    // How long each line stays before fading (ms)
    private val LINE_LIFE_MS = 6_000L
    // Fade out after silence (ms)
    private val SILENCE_MS   = 8_000L

    private var windowManager: WindowManager?              = null
    private var overlayView:   View?                       = null
    private var linesContainer: LinearLayout?              = null
    private var params:         WindowManager.LayoutParams? = null
    private val mainHandler    = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    // Rolling subtitle lines — newest last
    private val subtitleLines = ArrayDeque<String>()
    private val lineViews = mutableListOf<TextView>()

    private var silenceRunnable: Runnable? = null
    private var lastHindi = ""

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }

        pushCallback = { original, hindi ->
            mainHandler.post { onNewText(original, hindi) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running      = false
        pushCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    private fun onNewText(original: String, hindi: String) {
        if (hindi.isBlank()) return
        if (hindi == lastHindi) return
        lastHindi = hindi

        addSubtitleLine(hindi.trim())
        rescheduleSilence()
    }

    // ── Rolling subtitle logic ─────────────────────────────────────────────────

    private fun addSubtitleLine(text: String) {
        val container = linesContainer ?: return

        // Add new line to our data
        subtitleLines.addLast(text)

        // Keep only MAX_LINES
        while (subtitleLines.size > MAX_LINES) {
            subtitleLines.removeFirst()
        }

        // Rebuild the visual display
        rebuildLines(container)
    }

    private fun rebuildLines(container: LinearLayout) {
        // Remove all existing views with slide-up animation for old ones
        val existingViews = (0 until container.childCount).map { container.getChildAt(it) }

        // Animate existing lines sliding up slightly
        existingViews.forEach { view ->
            view.animate()
                .translationY(-dp(4).toFloat())
                .alpha(if (subtitleLines.size >= MAX_LINES) 0f else 1f)
                .setDuration(150)
                .withEndAction {
                    container.removeAllViews()
                    addLineViews(container)
                }
                .start()
        }

        // If container is empty, add directly
        if (existingViews.isEmpty()) {
            addLineViews(container)
        }
    }

    private fun addLineViews(container: LinearLayout) {
        container.removeAllViews()

        subtitleLines.forEachIndexed { index, text ->
            val isNewest = index == subtitleLines.size - 1
            val isOldest = index == 0 && subtitleLines.size == MAX_LINES

            val tv = TextView(this).apply {
                this.text = text
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isNewest) 22f else 18f)
                setTextColor(when {
                    isNewest -> Color.WHITE          // newest — full white
                    isOldest -> Color.parseColor("#99FFFFFF")  // oldest — 60% opacity
                    else     -> Color.parseColor("#CCFFFFFF")  // middle — 80% opacity
                })
                setShadowLayer(10f, 0f, 2f, Color.BLACK)
                setPadding(0, dp(2), 0, dp(2))
                maxLines  = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                // Background pill for newest line (like Live Captions)
                if (isNewest) {
                    setBackgroundResource(0)
                    background = createRoundedBackground()
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(if (isNewest) 0 else 2)
            }
            container.addView(tv, lp)

            // Animate newest line sliding in from bottom
            if (isNewest) {
                tv.translationY = dp(20).toFloat()
                tv.alpha        = 0f
                tv.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .start()

                // Schedule this line to fade after LINE_LIFE_MS
                mainHandler.postDelayed({
                    if (subtitleLines.contains(text)) {
                        subtitleLines.remove(text)
                        if (running) mainHandler.post { rebuildLines(container) }
                    }
                }, LINE_LIFE_MS)
            }
        }
    }

    private fun createRoundedBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape       = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(Color.argb(160, 0, 0, 0))  // semi-transparent black
        }
    }

    private fun rescheduleSilence() {
        silenceRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            // Clear all lines after silence
            subtitleLines.clear()
            linesContainer?.removeAllViews()
            lastHindi = ""
        }
        mainHandler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Overlay window setup ──────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels

            // Outer container — full width, sits at bottom
            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }

            // Lines container — where subtitle lines are added
            linesContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
            }

            outer.addView(linesContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            overlayView = outer

            params = WindowManager.LayoutParams(
                sw,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = 0
                y = dp(80)
            }

            // Draggable
            var startRawX = 0f; var startRawY = 0f
            var initX = 0;      var initY = 0
            outer.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX = p.x;         initY = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initX + (ev.rawX - startRawX).toInt()
                        p.y = initY - (ev.rawY - startRawY).toInt()
                        if (viewAdded) try {
                            windowManager?.updateViewLayout(overlayView, p)
                        } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
