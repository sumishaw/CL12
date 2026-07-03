package com.example.nihongolens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {

    companion object {
        @Volatile var instance: MainActivity? = null

        // LC-mode MediaProjection for GenderAnalyzer
        @Volatile var lcProjection: android.media.projection.MediaProjection? = null

        private const val REQ_MEDIA_PROJECTION  = 200
        private const val REQ_GENDER_PROJECTION  = 201
        private const val REQ_AUDIO_PERMISSION   = 100
        private const val TAG                    = "MainActivity"
        private const val WHISPER_HEALTH_URL     = "http://127.0.0.1:8765/ready"
        private const val IDLE_HEALTH_POLL_MS    = 30_000L
    }

    private val CHANNEL = "overlay_channel"
    private var methodChannel: MethodChannel? = null

    @Volatile private var pendingProjectionResult:    MethodChannel.Result? = null
    @Volatile private var pendingGenderOnly          = false
    @Volatile private var isSpeechCaptureUserStarted = false
    @Volatile private var pendingGenderResult:     MethodChannel.Result? = null

    private val healthExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler    = Handler(Looper.getMainLooper())
    private var idlePollRunnable: Runnable? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {

                "openAccessibilitySettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(true)
                }

                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }

                // ── All-files-access storage permission ─────────────────────────
                // MANAGE_EXTERNAL_STORAGE is declared in the manifest already, but
                // unlike normal permissions it has NO runtime request dialog and
                // does NOT appear in the regular grouped Permissions screen — it
                // lives on its own dedicated per-app settings page, reachable only
                // via this specific intent. Without this, there's no way for the
                // user to find or grant it, which is exactly what was happening.
                "hasManageStoragePermission" ->
                    result.success(
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                        Environment.isExternalStorageManager()
                    )

                "requestManageStoragePermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !Environment.isExternalStorageManager()) {
                        try {
                            startActivity(Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            ))
                        } catch (e: Exception) {
                            // Some OEM skins don't resolve the per-app variant —
                            // fall back to the general all-files-access list page
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }

                "hasAudioPermission" ->
                    result.success(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                    )

                "requestAudioPermission" ->
                    requestAudioThenProjection(result)

                "checkAccessibilityEnabled" -> result.success(true)

                "isModelReady" -> checkWhisperReady { ready ->
                    runOnUiThread { result.success(ready) }
                }

                "getModelStatus" -> checkWhisperReady { ready ->
                    runOnUiThread {
                        result.success(if (ready) "ready" else "not_downloaded")
                    }
                }

                "startModelDownload" -> {
                    result.success(true)
                    checkAndNotifyWhisperReady()
                }

                // ── Overlay ──────────────────────────────────────────────────

                "startOverlay" -> {
                    val i = Intent(this, OverlayService::class.java)
                    startForegroundServiceCompat(i)
                    result.success(true)
                    // Request screen capture permission for GenderAnalyzer internal audio
                    mainHandler.post { requestProjectionForGender() }
                }

                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    GenderAnalyzer.stop()
                    // Also stop gender-only SCS if it was started
                    if (SpeechCaptureService.isRunning && !isSpeechCaptureUserStarted) {
                        stopService(Intent(this, SpeechCaptureService::class.java))
                    }
                    result.success(true)
                }

                // ── Background/hide mode ────────────────────────────────────────
                // Two SEPARATE toggles, because they control two different things
                // with a real technical limitation on one of them:
                //
                // 1) Caption Lens's own Hindi subtitle overlay — we fully control
                //    this window, so hiding it is straightforward: translation and
                //    TTS keep running exactly as before, only the on-screen text
                //    rendering is suppressed.
                //
                // 2) Android's own Live Caption bubble — this belongs to the
                //    system (com.google.android.as), not this app. There is no
                //    public API for a third-party app to hide another app's
                //    overlay window. "setLiveCaptionHidden" below does NOT hide
                //    it — it can only open system settings as a shortcut for the
                //    user to do it manually (tap-to-minimize, or turn it off).
                //    Our own accessibility reading of its transcript should keep
                //    working while it's minimized to the small pill (Live Caption's
                //    normal minimized state) — but this needs to be verified on a
                //    real device, since Android's behavior here isn't something we
                //    can confirm from code alone. If Live Caption is fully turned
                //    off (not just minimized), there is no transcript to read at
                //    all — that's a hard requirement Caption Lens can't work around.

                "setHindiSubtitleHidden" -> {
                    val hidden = call.argument<Boolean>("hidden") ?: false
                    OverlayService.applySubtitleHidden(hidden)
                    result.success(true)
                }

                "isHindiSubtitleHidden" ->
                    result.success(OverlayService.subtitleHidden)

                "openLiveCaptionSettings" -> {
                    // Best-effort shortcut only — see comment block above.
                    // Accessibility settings is where Live Caption's toggle
                    // lives on stock Android; this may vary on OEM skins.
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(true)
                }

                // ── Speech capture ────────────────────────────────────────────

                "startSpeechCapture" -> {
                    stopIdlePoll()
                    isSpeechCaptureUserStarted = true
                    requestAudioThenProjection(result)
                }

                "stopSpeechCapture" -> {
                    isSpeechCaptureUserStarted = false
                    stopService(Intent(this, SpeechCaptureService::class.java))
                    result.success(true)
                    startIdlePoll()
                }

                "isSpeechCaptureRunning" ->
                    result.success(SpeechCaptureService.isRunning)

                "setTargetLanguage" -> {
                    SpeechCaptureService.targetLanguage = "hindi"
                    result.success(true)
                }

                "setSubtitleSpeed" -> {
                    val seconds = (call.argument<Double>("seconds") ?: 6.0)
                    OverlayService.setHoldMs((seconds * 1000).toLong())
                    result.success(true)
                }

                "setTtsEnabled" -> {
                    val on = call.argument<Boolean>("enabled") ?: false
                    HindiTtsService.setEnabled(on)
                    result.success(true)
                }

                "setTtsGender" -> {
                    val gender = call.argument<String>("gender") ?: "auto"
                    val g = when (gender) {
                        "male"   -> HindiTtsService.Gender.MALE
                        "female" -> HindiTtsService.Gender.FEMALE
                        else     -> HindiTtsService.Gender.AUTO
                    }
                    HindiTtsService.setGender(g)
                    result.success(true)
                }

                "setTtsSpeed" -> {
                    val speed = (call.argument<Double>("speed") ?: 1.5).toFloat()
                    HindiTtsService.setSpeedMultiplier(speed)
                    result.success(true)
                }

                "getLatestTranslation" ->
                    result.success(mapOf(
                        "original" to SpeechCaptureService.latestOriginal,
                        "english"  to SpeechCaptureService.latestEnglish,
                        "hindi"    to SpeechCaptureService.latestHindi
                    ))

                // ── Log viewer ────────────────────────────────────────────────

                "setLockedLang" -> {
                    val lang = call.arguments as? String ?: ""
                    LiveCaptionReader.instance?.lockedLang = lang
                    CaptionLogger.log("MainActivity", "LANG-LOCK → '${lang.ifEmpty { "auto" }}'")
                    result.success(null)
                }
                "getLockedLang" -> result.success(LiveCaptionReader.instance?.lockedLang ?: "")

                "getLogs" -> {
                    val n = (call.arguments as? Int) ?: 300
                    result.success(CaptionLogger.getRecentLines(n))
                }
                "getLogStats"  -> result.success(CaptionLogger.getStats())
                "downloadLogs" -> result.success(CaptionLogger.downloadLogs(this))
                "clearLogs"    -> { CaptionLogger.clearLines(); result.success(null) }

                                "getGenderStatus" -> {
                    result.success(mapOf(
                        "detected"  to if (HindiTtsService.detectedGender == HindiTtsService.Gender.FEMALE) "female" else "male",
                        "selected"  to when (HindiTtsService.selectedGender) {
                            HindiTtsService.Gender.FEMALE -> "female"
                            HindiTtsService.Gender.MALE   -> "male"
                            else                          -> "auto"
                        },
                        "enabled"   to GenderAnalyzer.enabled,
                        "speaking"  to HindiTtsService.isSpeaking,
                        "status"    to GenderAnalyzer.lastStatus
                    ))
                }

                else -> result.notImplemented()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HindiTtsService.init(this)
        checkAndNotifyWhisperReady()
    }

    override fun onResume() {
        super.onResume()
        instance = this
        if (!SpeechCaptureService.isRunning) {
            checkAndNotifyWhisperReady()
        }
        // Restart GenderAnalyzer if it stopped (e.g. app was backgrounded)
        if (!GenderAnalyzer.enabled) {
            GenderAnalyzer.start()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        pendingProjectionResult?.success(false)
        pendingProjectionResult = null
        stopIdlePoll()
        healthExecutor.shutdownNow()
        HindiTtsService.destroy()
        instance = null
        super.onDestroy()
    }

    // ── Idle health polling ───────────────────────────────────────────────────

    private fun startIdlePoll() {
        stopIdlePoll()
        val runnable = object : Runnable {
            override fun run() {
                if (!SpeechCaptureService.isRunning) {
                    checkAndNotifyWhisperReady()
                    mainHandler.postDelayed(this, IDLE_HEALTH_POLL_MS)
                }
            }
        }
        idlePollRunnable = runnable
        mainHandler.postDelayed(runnable, IDLE_HEALTH_POLL_MS)
    }

    private fun stopIdlePoll() {
        idlePollRunnable?.let { mainHandler.removeCallbacks(it) }
        idlePollRunnable = null
    }

    // ── Whisper server health ─────────────────────────────────────────────────

    private fun checkWhisperReady(onResult: (Boolean) -> Unit) {
        healthExecutor.submit {
            val ready = try {
                val conn = URL(WHISPER_HEALTH_URL).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) { false }
            onResult(ready)
        }
    }

    private fun checkAndNotifyWhisperReady() {
        checkWhisperReady { ready ->
            runOnUiThread {
                if (ready) {
                    methodChannel?.invokeMethod("onModelReady", null)
                } else {
                    methodChannel?.invokeMethod(
                        "onModelError",
                        mapOf("message" to
                            "Whisper server not running.\n" +
                            "Start it with:\n  python3 whisper_server.py\n" +
                            "Then tap RETRY.")
                    )
                }
            }
        }
    }

    // ── Flutter callbacks from Kotlin services ────────────────────────────────

    fun onLiveCaptionReaderConnected() {
        mainHandler.post {
            methodChannel?.invokeMethod("onLiveCaptionReaderConnected", null)
            if (!GenderAnalyzer.enabled) GenderAnalyzer.start()

            if (!HindiTtsService.enabled) {
                HindiTtsService.setEnabled(true)
                android.util.Log.d("MainActivity", "TTS auto-enabled on LC connect")
            }

            // FIX BUG 2: Start BackgroundMusicRecorder for BG music capture + mixing
            // Captures USAGE_MEDIA at 44100Hz stereo → indexed chunks → mixed into TTS audio
            if (!BackgroundMusicRecorder.enabled) {
                BackgroundMusicRecorder.start(lcProjection)
                android.util.Log.d("MainActivity", "BackgroundMusicRecorder started on LC connect")
            }

            if (OverlayService.instance == null && Settings.canDrawOverlays(this@MainActivity)) {
                startForegroundServiceCompat(Intent(this@MainActivity, OverlayService::class.java))
                android.util.Log.d("MainActivity", "OverlayService auto-started on LC connect")
            }
        }
    }

    fun notifyWhisperDisconnected() {
        runOnUiThread {
            methodChannel?.invokeMethod(
                "onModelError",
                mapOf("message" to
                    "Whisper server disconnected.\nReconnecting automatically…\nTap RETRY if this persists.")
            )
        }
    }

    fun notifyWhisperReconnected() {
        runOnUiThread { methodChannel?.invokeMethod("onModelReady", null) }
    }

    fun onTranslation(original: String, english: String, hindi: String) {
        runOnUiThread {
            methodChannel?.invokeMethod("onTranslation", mapOf(
                "original" to original,
                "english"  to english,
                "hindi"    to hindi
            ))
        }
    }

    // ── Permission + projection flow ──────────────────────────────────────────

    // ── Gender-only projection ────────────────────────────────────────────────

    private fun requestProjectionForGender() {
        if (SpeechCaptureService.isRunning) {
            CaptionLogger.log("MainActivity", "SCS already running — GenderAnalyzer has projection")
            return
        }
        CaptionLogger.log("MainActivity", "requestProjectionForGender: overlay=${Settings.canDrawOverlays(this)} audio=${ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}")
        pendingGenderOnly = true
        // Reuse full audio+projection request flow — handles RECORD_AUDIO permission automatically
        val dummy = object : MethodChannel.Result {
            override fun success(r: Any?) {
                CaptionLogger.log("MainActivity", "gender projection dummy result: $r")
            }
            override fun error(c: String, m: String?, d: Any?) {
                CaptionLogger.log("MainActivity", "gender projection error: $c $m")
            }
            override fun notImplemented() {}
        }
        requestAudioThenProjection(dummy)
    }

    private fun requestAudioThenProjection(result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(this)) { result.success(false); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            requestMediaProjection(result)
        } else {
            deliverPendingFailure()
            pendingProjectionResult = result
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION
            )
        }
    }

    private fun requestMediaProjection(result: MethodChannel.Result) {
        deliverPendingFailure()
        pendingProjectionResult = result
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Exception) {
            pendingProjectionResult = null
            result.success(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            val pending = pendingProjectionResult
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pending != null) { pendingProjectionResult = null; requestMediaProjection(pending) }
            } else {
                pendingProjectionResult = null; pending?.success(false)
            }
        }
    }

    @Deprecated("Required for API compatibility below 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // REQ_GENDER_PROJECTION removed — gender projection now uses SCS gender-only mode


        if (requestCode == REQ_MEDIA_PROJECTION) {
            val pending = pendingProjectionResult
            pendingProjectionResult = null
            val genderOnly = pendingGenderOnly
            pendingGenderOnly = false

            if (resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, SpeechCaptureService::class.java).apply {
                    putExtra(SpeechCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(SpeechCaptureService.EXTRA_RESULT_DATA, data)
                    if (genderOnly) putExtra(SpeechCaptureService.EXTRA_GENDER_ONLY, true)
                }
                startForegroundServiceCompat(i)
                if (!genderOnly) pending?.success(true)
                else CaptionLogger.log("MainActivity", "Gender-only SCS started")
            } else {
                pending?.success(false)
                if (genderOnly) GenderAnalyzer.lastStatus = "permission denied"
            }
        }
    }

    // requestProjectionForGender() removed — GenderAnalyzer uses Visualizer API, no projection needed

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun deliverPendingFailure() {
        val stale = pendingProjectionResult
        if (stale != null) { pendingProjectionResult = null; try { stale.success(false) } catch (_: Exception) {} }
    }
}
