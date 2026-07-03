package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * LiveCaptionReader — Live Captions → Hindi translation → Overlay
 *
 * CRITICAL FIX v2: Near-realtime translation
 * - READ_TIMEOUT reduced to 6s (was 35s) — cuts worst-case delay 6x
 * - SKIP-AHEAD: if queue has >1 item, always process the LATEST (skip old ones)
 * - Translation cache: identical/prefix-match sentences return instantly
 * - Stale threshold: 6s (was 15s) — drops sentences speaker has already passed
 * - Queue cap: 3 (was 8) — never build up more than 1 sentence of backlog
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG              = "LCReader"
        private const val TRANSLATE_URL      = "http://127.0.0.1:8765/translate_text"
        private const val IS_COMPLETE_URL    = "http://127.0.0.1:8765/is_complete"
        private const val CONNECT_TIMEOUT  = 3_000
        private const val READ_TIMEOUT     = 6_000
        private const val DEBOUNCE_MS      = 400L
        private const val WATCHDOG_MS      = 2_000L
        private const val STARTUP_GRACE_MS = 1_000L
        // Grace period before treating LC as gone.
        // Covers: navigating to CL main screen, notification bar, app switcher.
        // 3s: enough for user to dismiss and return to video.
        // If LC stays gone > 3s → video genuinely ended/paused → confirm gone.
        private const val LC_GONE_GRACE_MS  = 5_000L  // 5s: covers brief app switches
        // Live Caption's accessibility node exposes the FULL running transcript,
        // not just the visible line, so it grows continuously through a long
        // conversation. This must stay far above any realistic transcript length —
        // previously 500 chars, which any conversation exceeds within under a
        // minute, silently freezing the reader forever (see validCaption()).
        private const val MAX_CAPTION_LEN   = 20_000
        private const val LANG_CONFIRM        = 5   // 5 consecutive: prevents single-word flicker
        private const val LANG_CONFIRM_LOCKED  = 999  // never switches when locked
        private const val QUEUE_CAP        = 500  // never flush while running — FIFO backlog
        private const val STALE_MS         = Long.MAX_VALUE  // backlogs NEVER expire while running

        // ── SENTENCE COMPLETION SILENCE GAP ──────────────────────────────────
        // How long LC must be SILENT (no new text) before we treat current buffer as complete sentence.
        // 900ms: catches natural speaker pauses between sentences without cutting mid-sentence.
        // Previously 600ms was too short — partial clauses got translated mid-thought.
        private const val SENTENCE_SILENCE_MS_PRIMARY = 900L   // after hard punctuation or long sentence
        private const val SENTENCE_SILENCE_MS_SOFT    = 1_000L // reduced for continuous dialogue flow

        // ── MULTILINGUAL HARD SENTENCE-END MARKERS ───────────────────────────
        // These characters definitively end a sentence in their respective languages.
        // Translation is triggered IMMEDIATELY (100ms debounce) when text ends with one of these.
        //
        // Language coverage:
        //   English/European:   . ! ?
        //   Hindi/Devanagari:   । ॥ ! ? (danda = sentence end)
        //   Chinese/Japanese:   。！？ (fullwidth)
        //   Korean:             。 ! ?  (uses CJK period + ASCII punct)
        //   Arabic:             ؟ ۔ (Arabic question mark, Urdu full stop)
        //   Thai:               ๆ ฯ (though Thai rarely uses sentence-final punct)
        //   Hebrew:             . ! ?
        //   Russian/Cyrillic:   . ! ?
        //   Greek:              . ! ; (Greek semicolon = question mark)
        //   Spanish/French:     . ! ? … ¡ ¿ (inverted punct = sentence START but we check end)
        //   German:             . ! ?
        //   Portuguese:         . ! ?
        //   Indonesian/Malay:   . ! ?
        //   Turkish:            . ! ?
        //   All languages:      … (ellipsis = sentence end with trailing thought)
        val HARD_END_CHARS = setOf(
            // ── ASCII (en, es, fr, de, pt, ru, tr, id, ko) ──────────────────
            '.', '!', '?',

            // ── Fullwidth CJK (zh, ja, ko) ───────────────────────────────────
            '。', '！', '？',

            // ── Ellipsis variants ─────────────────────────────────────────────
            '…',       // U+2026 horizontal ellipsis (all languages)
            '⋯',       // U+22EF midline ellipsis (ja, zh variant)
            '‼', '⁇', '⁈', '⁉',   // double/combined punct

            // ── Hindi / Devanagari (hi, mr, ne, sa) ──────────────────────────
            '।', '॥',             // danda, double danda
            '\u0964', '\u0965',   // same by Unicode codepoint

            // ── Arabic script (ar, ur, fa, ps) ───────────────────────────────
            '؟',       // U+061F Arabic question mark
            '۔',       // U+06D4 Urdu full stop

            // ── Ethiopic (am — Amharic, not in our list but safe to add) ─────
            '\u1362', '\u1367',

            // ── Sundanese / Balinese (id regional scripts) ───────────────────
            '᪨', '᭞',
        )

        val SOFT_END_CHARS = setOf(
            // ── ASCII clause separators (all Latin-script languages) ──────────
            ',', ';', ':', '-',

            // ── Arabic clause separators ──────────────────────────────────────
            '،',       // U+060C Arabic comma
            '؛',       // U+061B Arabic semicolon

            // ── CJK clause separators (zh, ja, ko) ───────────────────────────
            '、',       // U+3001 ideographic comma (ja, zh)
            '，',       // U+FF0C fullwidth comma (zh)
            '；',       // U+FF1B fullwidth semicolon
            '：',       // U+FF1A fullwidth colon (zh — ends a clause, not sentence)
            '・',       // U+30FB katakana middle dot (ja)

            // ── Dashes (ru, de, fr, es — em/en dash as clause separator) ─────
            '—', '–',

            // ── Newline (LC sometimes uses for clause separation) ─────────────
            '\n',
        )

        // ── MINIMUM WORDS FOR TRANSLATION ────────────────────────────────────
        private const val MIN_WORDS_HARD    = 1   // FIX: numbers/single words must translate   // after hard punctuation (lowered: "Do you?" = 2 words, must translate)
        private const val MIN_WORDS_SOFT    = 7   // after soft punctuation
        private const val MIN_WORDS_SILENCE = 7   // avoid tiny fragments < 7 words

        // ── FORCE / COOLDOWN THRESHOLDS ───────────────────────────────────────
        // MAX_WORDS_BEFORE_FORCE: raised 15→20. At 15, a 60-word paragraph triggered
        // FORCE every 6 words = 10 submissions of the same sentence → CT2 flood.
        // Match server chunk size: 12 words per CT2 call → ~1.2s per chunk
        // Previously 20 words → 2.5s per chunk → 5-chunk paragraph = 12.5s backlog
        private const val MAX_WORDS_BEFORE_FORCE = 10  // match 10-word server chunk

        // FORCE_MIN_NEW_WORDS: raised 6→12. Previously wordsSinceSubmit=7 bypassed
        // cooldown, causing same text to be submitted every 7 words (3s = 1+ per second).
        // After FORCE, need 6 new words before next FORCE (was 12)
        // With 12-word chunks: 6 new words = speaker said half a chunk = safe to re-submit
        private const val FORCE_MIN_NEW_WORDS = 5   // half of 10-word chunk

        // FORCE_COOLDOWN_MS: hard time-based lock after any FORCE submission.
        // Even if 12 new words arrive in 1 second, don't force-submit again.
        // 3s cooldown: 12-word chunk takes ~1.2s to translate → 3s gives 2.5× margin
        private const val FORCE_COOLDOWN_MS  = 1_500L  // 1.5s: handles fast speakers  // 0.8s translate + 1.7s margin

        private val LC_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        // ── INTERJECTIONS / FILLERS ───────────────────────────────────────────
        // Short non-lexical utterances that carry tone, not meaning. Sending
        // these through CT2 translation produces unrelated/garbled Hindi phrases
        // (translation models treat them as real words needing a real gloss).
        // Instead we map each one directly to a natural Devanagari rendering so
        // the existing hi-IN TTS voice pronounces it correctly, while the
        // speaker-matched pitch/rate/emotion pipeline (GenderAnalyzer contour)
        // still applies exactly as it does for normal sentences — same voice,
        // just the original interjection instead of a mistranslated one.
        // Multiple English spellings can map to the same/similar rendering;
        // that's intentional — they're the same sound, spelled differently.
        val INTERJECTIONS: Map<String, String> = mapOf(
            // Affirmation & Agreement
            "yeah"    to "याह",
            "yah"     to "याह",
            "yep"     to "येप",
            "yup"     to "यप",
            "uh-huh"  to "अ-हा",
            "uhhuh"   to "अ-हा",
            "aha"     to "आहा",
            "yo"      to "यो",

            // Negation & Disagreement
            "nope"    to "नोप",
            "nah"     to "ना",
            "uh-uh"   to "अ-अ",
            "uhuh"    to "अ-अ",
            "nix"     to "निक्स",

            // Hesitation Fillers
            "um"      to "अम्म",
            "umm"     to "अम्म",
            "uh"      to "अह",
            "er"      to "अर",
            "hmm"     to "हम्म्",
            "hmmm"    to "हम्म्",
            "mm"      to "म्म्",
            "mmm"     to "म्म्",
            "eh"      to "एह",

            // Realisation & Surprise
            "oh"      to "ओह",
            "ah"      to "आह",
            "aah"     to "आआह",
            "oho"     to "ओहो",
            "wow"     to "वाओ",
            "ooh"     to "ऊह",
            "whoa"    to "व्होआ",

            // Pain, Disgust & Disappointment
            "ow"      to "आउ",
            "ouch"    to "आउच",
            "ugh"     to "अघ्ह",
            "yuck"    to "यक",
            "ew"      to "इव",
            "eww"     to "इव",
            "oof"     to "ऊफ",
            "alas"    to "अलास",

            // Greetings & Attention Seekers
            "hey"     to "हे",
            "hi"      to "हाय",
            "oi"      to "ओइ",
            "psst"    to "प्स्स्त्",
        )

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:   Job? = null
    private var translateJob: Job? = null
    private var watchdogJob:  Job? = null

    // FIFO + tokens
    private data class QItem(
        val seq: Long,
        val text: String,
        val enqMs: Long = System.currentTimeMillis(),
        // Set for interjections only: worker delivers this directly instead of
        // calling the CT2 translation server. Null for normal sentences.
        val presetHindi: String? = null,
    )
    private val queue      = LinkedBlockingQueue<QItem>()
    private val seqCounter = AtomicLong(0)
    // Thread-safe set: tracks texts currently being translated by any worker
    // ConcurrentHashMap.newKeySet() is thread-safe unlike mutableSetOf() (LinkedHashSet)
    // synchronized{} with LinkedHashSet can still throw ConcurrentModificationException
    // across coroutine dispatch threads → workerLoop dies silently → no translations
    private val activeTranslations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var expectedSeq = 0L

    // Translation LRU cache — avoid re-translating same sentence
    // Key: normalized English, Value: Hindi result
    private val translationCache = LinkedHashMap<String, String>(32, 0.75f, true)
    private val CACHE_MAX = 50

    // Simple dedup — only exact match
    private var lastEnqueued  = ""
    private var lastHindiOut  = ""
    private var lastHindiTime = 0L
    private val HINDI_DEDUP_MS = 4_000L

    // Called by GenderAnalyzer on gender switch — new speaker may say
    // similar words, so we reset dedup to ensure their sentence always shows
    fun resetHindiDedup() {
        lastHindiOut  = ""
        lastHindiTime = 0L
        CaptionLogger.log(TAG, "Hindi dedup reset (gender switch)")
    }

    // Language tracking
    private var confirmedLang = ""
    private var pendingLang   = ""
    private var pendingCount  = 0

    // ── Language Lock ─────────────────────────────────────────────────────────
    // When set, ignores LC's language detection entirely and forces this lang.
    // Prevents mid-video flicker when LC briefly misdetects a word as another language.
    // "" = auto (use LC detection with LANG_CONFIRM debounce)
    // "latin_en" | "ja" | "zh" | "ko" | "ar" | "ru" | "hi" | "latin_foreign" = locked
    @Volatile var lockedLang: String = ""

    // When locked, bypass detection entirely (if (lockedLang.isNotEmpty()) LANG_CONFIRM_LOCKED else LANG_CONFIRM not needed)

    // Window state
    private var lastRawFull           = ""
    private var lastSentText          = ""
    private var lastEnqueuedSents     = mutableSetOf<String>()
    private var lcVisible             = false
    private var lcGoneMs              = 0L   // timestamp when LC first disappeared
    private var afterMusicGap         = false // true = just resumed after music/silence gap
    private var startupTime           = 0L

    // Stats
    private val evtCount = AtomicLong(0)
    private val enqCount = AtomicLong(0)
    private val okCount  = AtomicLong(0)
    private val errCount = AtomicLong(0)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance    = this
        isRunning   = true
        startupTime = System.currentTimeMillis()

        serviceInfo = serviceInfo?.also {
            it.eventTypes = (AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            it.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            it.notificationTimeout = 100
            it.flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
            it.packageNames = null
        }

        resetAll()
        startWorker()
        startWatchdog()
        startStats()
        CaptionLogger.log(TAG, "=== Connected (READ_TIMEOUT=${READ_TIMEOUT}ms QUEUE_CAP=$QUEUE_CAP STALE=${STALE_MS}ms) ===")
        GenderAnalyzer.start(MainActivity.lcProjection)
        scope.launch(Dispatchers.Main) { MainActivity.instance?.onLiveCaptionReaderConnected() }
    }

    override fun onInterrupt() { CaptionLogger.log(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        GenderAnalyzer.stop()
        pendingJob?.cancel()
        watchdogJob?.cancel(); translateJob?.cancel(); translateJob2?.cancel()
        queue.clear(); scope.cancel()
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
        OverlayService.updateText("", "")
        CaptionLogger.stop()
        super.onDestroy()
    }

    // ── Events ────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) return
        evtCount.incrementAndGet()
        val text = readWindow() ?: return
        schedule(text)
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            var tick = 0L
            while (isActive && isRunning) {
                delay(WATCHDOG_MS)
                if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) continue
                tick++
                val text = withContext(Dispatchers.Main) {
                    try { readWindow() } catch (_: Exception) { null }
                } ?: run {
                    if (tick % 20L == 0L)
                        CaptionLogger.log(TAG, "WD null tick=$tick vis=$lcVisible")
                    return@run null
                } ?: continue
                schedule(text)
            }
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun startStats() {
        scope.launch {
            while (isActive && isRunning) {
                delay(30_000L)
                CaptionLogger.log(TAG, "STATS evt=${evtCount.get()} enq=${enqCount.get()} " +
                    "ok=${okCount.get()} err=${errCount.get()} q=${queue.size} " +
                    "vis=$lcVisible lang=$confirmedLang seq=$expectedSeq cache=${translationCache.size}")
            }
        }
    }

    // ── Window reader ─────────────────────────────────────────────────────────

    private fun readWindow(): String? {
        // CRITICAL FIX: `windows` can throw transiently (display/config changes,
        // IME opening/closing, binder hiccups). PREVIOUSLY this early-returned null
        // WITHOUT going through the LC-gone grace/confirm state machine below —
        // meaning lcGoneMs/lcVisible never advanced while it kept throwing, and the
        // reader silently froze forever with zero log output (no error, no confirm).
        // NOW: an exception here is treated exactly like "LC window not found" so
        // grace/confirm still progresses, and it's logged (throttled) for visibility.
        val wins = try { windows } catch (e: Exception) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastWindowsExcLogMs > 5_000L) {
                lastWindowsExcLogMs = nowMs
                CaptionLogger.log(TAG, "windows() threw: ${e.javaClass.simpleName}: ${e.message}",
                    CaptionLogger.LEVEL_WARN)
            }
            return handleLcGoneOrAbsent()
        }

        var root: AccessibilityNodeInfo? = null
        wins?.forEach { w ->
            if (root != null) return@forEach
            val r = try { w.root } catch (_: Exception) { null } ?: return@forEach
            if (r.packageName?.toString() in LC_PACKAGES) root = r else r.recycle()
        }

        if (root == null) {
            return handleLcGoneOrAbsent()
        }
        return readWindowFound(root)
    }

    private var lastWindowsExcLogMs = 0L

    // Shared LC-gone grace/confirm state machine — reached whether the LC window
    // is simply absent from windows[] OR windows() itself threw an exception.
    private fun handleLcGoneOrAbsent(): String? {
        if (lcVisible) {
            val nowMs = System.currentTimeMillis()
            if (lcGoneMs == 0L) {
                // First null read — start grace timer, don't reset yet
                lcGoneMs = nowMs
                CaptionLogger.log(TAG, "LC gone (grace period started)")
                return null
            }
            val goneForMs = nowMs - lcGoneMs
            if (goneForMs < LC_GONE_GRACE_MS) {
                // Still within grace period — LC may reappear (app UI, notif bar etc.)
                // Keep translating and speaking whatever is in the queues
                return null
            }
            // Grace period expired — LC genuinely gone (video ended / paused)
            lcVisible = false
            lcGoneMs  = 0L
            lastRawFull = ""; lastEnqueued = ""; lastSentText = ""
            lastEnqueuedSents.clear()
            pendingJob?.cancel(); pendingJob = null
            sentenceTimerJob?.cancel(); sentenceTimerJob = null
            sentenceBuffer = ""; lastBufferEnqueued = ""
            lastEnqueuedWordCount = 0; lastEnqueuedText = ""
            lastSubmitTotalWords = 0; lastSubmitMs = 0L
            CaptionLogger.log(TAG, "LC gone (grace ${goneForMs}ms — confirmed)")
            // NOTE: queue and TTS NOT cleared — FIFO backlog finishes playing
            // Only explicit stop() / onDestroy clears queues
        } else {
            lcGoneMs = 0L  // already not visible, reset grace timer
        }
        return null
    }

    // LC window found — cancel any pending gone timer, process the caption text
    private fun readWindowFound(root: AccessibilityNodeInfo): String? {
        lcGoneMs = 0L

        val nodes = mutableListOf<String>()
        collectText(root, nodes)
        root.recycle()

        val full = nodes.filter { validCaption(it) && !uiLabel(it) }
            .maxByOrNull { it.length }?.trim() ?: return null

        if (!lcVisible) {
            lcVisible   = true
            lastRawFull = ""
            lastEnqueuedSents.clear()
            resetHindiDedup()  // new scene — don't skip new speaker's first sentence
            CaptionLogger.log(TAG, "LC appeared '${full.take(60)}'")
        }

        // No change — nothing to process
        if (full == lastRawFull) return null

        lastRawFull = full

        // KEY FIX: Return the FULL window text to schedule(), not just the delta.
        //
        // PREVIOUS (broken): returned only newText = full.substring(prev.length)
        // This caused schedule() to see tiny fragments: "a big break", ", wait about"
        // Each fragment triggered a 900ms silence timer → 5+ separate translations
        // for what should have been ONE complete sentence.
        //
        // NOW: return the FULL current LC text. schedule() accumulates it in
        // sentenceBuffer and the sentence-completion detector (punctuation/silence)
        // decides when to translate. The FULL text means the translation gets the
        // complete grammatical sentence — accurate Hindi output.
        //
        // LC text that doesn't start where we left off = new sentence block started
        // (LC scrolled, new speaker, etc.) — in that case still return the full new text.
        return if (full.length >= 4) full else null
    }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val parts  = text.split(Regex("""(?<=[。！？\n.!?])\s*"""))
        for (part in parts) {
            val t = part.trim()
            if (t.length >= 4) result.add(t)
        }
        if (result.isEmpty() && text.trim().length >= 4) result.add(text.trim())
        return result
    }

    private fun directEnqueue(text: String) {
        if (text.isBlank() || text.length < 4) return
        val n = norm(text)
        if (n == lastEnqueued) return
        lastEnqueued = n
        val seq = seqCounter.incrementAndGet()
        if (queue.size >= QUEUE_CAP) {
            val oldest = queue.peek()
            val age = if (oldest != null) System.currentTimeMillis() - oldest.enqMs else 9999L
            if (age > STALE_MS) queue.poll()
        }
        queue.offer(QItem(seq, text))
        enqCount.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ-S seq=$seq q=${queue.size} '${text.take(60)}'")
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun norm(t: String) = t.trim().replace(Regex("\\s+"), " ")
    private fun wc(t: String)   = t.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

    // State
    private var sentenceBuffer      = ""
    private var lastBufferEnqueued  = ""
    private var lastLCChangeMs      = 0L
    private var sentenceTimerJob: Job? = null
    private var lastEnqueuedWordCount = 0
    private var lastEnqueuedText      = ""
    // Cooldown: track total LC word count and time at last submission
    private var lastSubmitTotalWords  = 0
    private var lastSubmitMs          = 0L
    // Separate FORCE cooldown — tracks last time RULE 3 (FORCE) fired
    private var lastForcedMs          = 0L

    private fun schedule(text: String) {
        // ── Language detection ────────────────────────────────────────────────
        val script = if (lockedLang.isNotEmpty()) lockedLang else detectScript(text)

        if (lockedLang.isNotEmpty() && confirmedLang != lockedLang) {
            // Language was just locked — force immediate switch
            confirmedLang = lockedLang; pendingLang = ""; pendingCount = 0
            CaptionLogger.log(TAG, "LANG-LOCKED → $lockedLang")
        } else if (script != confirmedLang) {
            if (script == pendingLang) {
                if (++pendingCount >= LANG_CONFIRM) {
                    CaptionLogger.log(TAG, "LANG $confirmedLang->$script")
                    confirmedLang = script; pendingLang = ""; pendingCount = 0
                    // INTERFERENCE FIX: Do NOT clear queue on lang switch.
                    // Sentences already queued were translated correctly — don't drop them.
                    // Only reset text-tracking state so next sentence starts fresh.
                    lastEnqueued = ""; lastRawFull = ""; lastEnqueuedSents.clear()
                    sentenceBuffer = ""; lastBufferEnqueued = ""
                    lastEnqueuedWordCount = 0; lastEnqueuedText = ""
                    lastSubmitTotalWords = 0; lastSubmitMs = 0L
                    // Don't reset lastForcedMs — prevents immediate FORCE on lang switch
                }
            } else { pendingLang = script; pendingCount = 1 }
        } else { pendingLang = ""; pendingCount = 0 }

        val fullText   = text.trim()
        val totalWords = wc(fullText)
        lastLCChangeMs = System.currentTimeMillis()

        // ── Extract UNTRANSLATED TAIL (cumulative word-count tracking) ────────
        // PREVIOUS APPROACH (broken): match last 6 words of lastEnqueuedText in fullText.
        // Problem: 'is go' appears in multiple places in a 100-word paragraph → wrong match
        // → same sentence re-submitted 3 times as seq=33, 34, 36.
        //
        // NEW APPROACH: track how many total LC words existed when we last submitted.
        // untranslatedStart = lastSubmitTotalWords (total words at last submit time)
        // untranslated      = words from position lastSubmitTotalWords onward
        // This is O(1) and immune to word-content collisions.
        val untranslated: String = run {
            if (lastSubmitTotalWords <= 0) return@run fullText
            val fullWords = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }
            val startIdx  = lastSubmitTotalWords.coerceAtMost(fullWords.size)
            if (startIdx >= fullWords.size) return@run ""
            fullWords.subList(startIdx, fullWords.size).joinToString(" ")
        }

        val newWords = wc(untranslated)

        // ── RULE 0: INTERJECTION — immediate fire, bypasses every gate below ──
        // Single fillers/exclamations ("Um", "Wow", "Nope"...) are complete on
        // their own. The normal rules below all wait for either punctuation or
        // 3+ new words before firing — a lone filler with no trailing punctuation
        // (very common: LC often shows "Um" or "Hmm" with nothing after it) would
        // otherwise sit in sentenceBuffer and NEVER get submitted. And even when
        // it does reach translation, CT2 tends to mangle single interjections into
        // unrelated Hindi phrases. So: detect them here, skip CT2 entirely, and
        // speak a natural Hindi-script rendering directly — same speaker pitch/
        // rate/emotion pipeline (GenderAnalyzer contour) applies exactly as usual,
        // just without the translation round-trip.
        val interjectionHindi = matchInterjection(untranslated)
        if (interjectionHindi != null) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            sentenceBuffer = untranslated
            val fireWords  = totalWords
            pendingJob = scope.launch {
                delay(80)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText) {
                    CaptionLogger.log(TAG, "INTERJECTION '$t' → '$interjectionHindi'")
                    lastSubmitTotalWords = fireWords
                    lastSubmitMs         = System.currentTimeMillis()
                    lastEnqueuedText     = t
                    sentenceBuffer       = ""
                    GenderAnalyzer.flushContour()
                    enqueueInterjection(t, interjectionHindi)
                }
            }
            return
        }

        // ── COOLDOWN: Prevent re-submitting same text repeatedly ──────────────
        // CT2 TIMEOUT cascade: without this, same sentence submitted 10x → 50s stuck
        // Gate: only submit when 4+ NEW total words have arrived since last submission
        // OR more than 3s has passed (allows retry after silence)
        val wordsSinceSubmit = totalWords - lastSubmitTotalWords
        val timeSinceSubmit  = System.currentTimeMillis() - lastSubmitMs
        if (lastSubmitMs > 0L && wordsSinceSubmit < 4 && timeSinceSubmit < 3_000L) {
            sentenceBuffer = untranslated
            // Still run silence timer so we catch end-of-speech
            sentenceTimerJob?.cancel()
            if (newWords >= 5) {
                sentenceTimerJob = scope.launch {
                    delay(SENTENCE_SILENCE_MS_PRIMARY)
                    val t = sentenceBuffer.trim()
                    if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= 5) {
                        CaptionLogger.log(TAG, "SILENCE-COOLDOWN wc=${wc(t)}")
                        doSubmit(t, totalWords)
                    }
                }
            }
            return
        }

        if (untranslated.isBlank() || newWords < 2) {
            sentenceBuffer = untranslated; return
        }

        sentenceBuffer = untranslated
        val lastChar = untranslated.lastOrNull() ?: return

        // ── RULE 1: HARD sentence-end ─────────────────────────────────────────
        if (lastChar in HARD_END_CHARS && newWords >= MIN_WORDS_HARD) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(80)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= MIN_WORDS_HARD) {
                    CaptionLogger.log(TAG, "HARD-END '$lastChar' wc=${wc(t)}")
                    doSubmit(t, totalWords)
                }
            }
            return
        }

        // ── RULE 1b: CAPITALIZATION SENTENCE-START (Latin-script only) ────────
        // Some languages (English, French, German, Spanish, etc.) conventionally
        // capitalize the first word of a new sentence. If a NEW capitalized
        // word appears partway through the currently-buffered untranslated
        // tail, that's a strong signal the text BEFORE it was actually a
        // complete sentence — even without terminal punctuation (LC
        // sometimes drops punctuation, or runs two sentences together).
        // Deliberately conservative: only applies to Latin-script text
        // (most languages have no capitalization convention at all — Hindi,
        // Chinese, Arabic, etc. — where this signal would be meaningless or
        // actively wrong), skips "I"/"I'm" (always capitalized regardless
        // of position), skips ALL-CAPS words (likely acronyms, not sentence
        // starts), and only fires once the pre-capital portion is already a
        // reasonable length (avoids splitting on short fragments where a
        // capitalized proper noun could appear early in a genuine sentence).
        if (isLatinScriptText(untranslated)) {
            val tailWords = untranslated.split(" ")
            for (idx in 1 until tailWords.size) {
                val w = tailWords[idx]
                if (looksLikeSentenceStart(w)) {
                    val before = tailWords.subList(0, idx).joinToString(" ").trim()
                    if (wc(before) >= 4 && before != lastEnqueuedText) {
                        sentenceTimerJob?.cancel(); pendingJob?.cancel()
                        val beforeTotalWords = lastSubmitTotalWords + wc(before)
                        pendingJob = scope.launch {
                            delay(80)
                            CaptionLogger.log(TAG, "CAP-START before='...${before.takeLast(20)}' next='$w' wc=${wc(before)}")
                            doSubmit(before, beforeTotalWords)
                        }
                        return
                    }
                }
            }
        }

        // ── RULE 2: SOFT clause-end — wait for more ───────────────────────────
        if (lastChar in SOFT_END_CHARS && newWords >= 8) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            sentenceTimerJob = scope.launch {
                delay(SENTENCE_SILENCE_MS_SOFT)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= 6) {
                    CaptionLogger.log(TAG, "SOFT-END '$lastChar' wc=${wc(t)}")
                    doSubmit(t, totalWords)
                }
            }
            return
        }

        // ── RULE 3: FORCE — run-on sentence, no punctuation ──────────────────
        // Only fires when BOTH conditions met:
        //   a) 20+ new untranslated words (was 15 — too low for 60-word paragraphs)
        //   b) 12+ new total words since last FORCE AND 5s since last FORCE
        // This dual gate prevents the "same sentence submitted 10 times" cascade.
        val timeSinceForce = System.currentTimeMillis() - lastForcedMs
        if (newWords >= MAX_WORDS_BEFORE_FORCE &&
            wordsSinceSubmit >= FORCE_MIN_NEW_WORDS &&
            timeSinceForce  >= FORCE_COOLDOWN_MS) {
            sentenceTimerJob?.cancel(); pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(150)
                val t = sentenceBuffer.trim()
                if (t.isNotBlank() && t != lastEnqueuedText && wc(t) >= MIN_WORDS_SILENCE) {
                    CaptionLogger.log(TAG, "FORCE wc=${wc(t)} new=$wordsSinceSubmit")
                    lastForcedMs = System.currentTimeMillis()
                    doSubmit(t, totalWords)
                }
            }
            return
        }

        // ── RULE 4: SMART SILENCE GAP ────────────────────────────────────────
        // Uses isCompleteSentence() (local dangling-word check + /is_complete
        // network check, see that function) to decide silence duration:
        //   Complete sentence (e.g. "Funny, I'm so sorry.") → 500ms wait
        //   Incomplete fragment (e.g. "But the end of") → 1600ms wait
        // This prevents flooding CT2 with tiny mid-sentence fragments from
        // rapid dialogue, AND — this is the important part for translation
        // grammar — gives a genuinely incomplete fragment real extra time
        // for the rest of the clause to arrive before firing anyway, rather
        // than translating "I am going" and "to the store" as two separate,
        // un-reorderable CT2 calls. Still bounded: if the speaker really did
        // stop (trailed off, interrupted), it fires after the wait either way
        // — this delays incomplete fragments, it doesn't block them forever.
        sentenceTimerJob?.cancel(); pendingJob?.cancel()
        if (newWords >= 3) {
            sentenceTimerJob = scope.launch {
                val t = sentenceBuffer.trim()
                // FIX: previously skipped the completeness check entirely
                // for 10+ word text, assuming length alone meant it was safe
                // to fire fast — but a long fragment can still end mid-clause
                // ("...was because of the fact that the weather conditions
                // were extremely") just as easily as a short one.
                val complete = withContext(Dispatchers.IO) { isCompleteSentence(t) }
                val silenceMs = if (complete) {
                    CaptionLogger.log(TAG, "SMART-COMPLETE wc=${wc(t)} — fast silence 500ms")
                    500L
                } else {
                    CaptionLogger.log(TAG, "SMART-INCOMPLETE wc=${wc(t)} — silence 1600ms (was 700ms — too little buffer for the rest of the clause to arrive)")
                    1600L
                }
                delay(silenceMs)
                val t2 = sentenceBuffer.trim()
                if (t2.isNotBlank() && t2 != lastEnqueuedText && wc(t2) >= 3) {
                    CaptionLogger.log(TAG, "SMART-SILENCE ${silenceMs}ms wc=${wc(t2)}")
                    doSubmit(t2, totalWords)
                }
            }
        }
    }

    private fun doSubmit(text: String, currentTotalWords: Int) {
        lastEnqueuedText      = text
        lastEnqueuedWordCount = wc(text)
        lastSubmitTotalWords  = currentTotalWords
        lastSubmitMs          = System.currentTimeMillis()
        sentenceBuffer        = ""
        // Flush the F0 contour captured during this sentence window to
        // HindiTtsService — used to build SSML that mirrors intonation shape
        GenderAnalyzer.flushContour()
        enqueue(text)
    }

    private fun enqueue(text: String) {
        if (text.isBlank() || text.length < 4) return

        val devanagariCount = text.count { it.code in 0x0900..0x097F }
        if (devanagariCount > 0) {
            CaptionLogger.log(TAG, "SKIP: Devanagari detected (TTS loop guard)")
            return
        }

        // HARD QUEUE CAP: If queue already has QUEUE_CAP items, drop ALL existing items
        // and only keep the newest (current) text. This prevents CT2 from being asked to
        // translate 10+ stale sentences — the speaker has moved on.
        val currentSize = queue.size
        if (currentSize >= QUEUE_CAP) {
            // FIFO: don't flush — log backlog size only
            CaptionLogger.log(TAG, "QUEUE-BACKLOG: $currentSize items pending — all will be spoken")
        }

        val wordCount = text.trim().split(Regex("\\s+")).size
        if (wordCount < 2) {
            CaptionLogger.log(TAG, "SKIP: too short ($wordCount words)")
            return
        }
        val lower = text.lowercase()
        val romanizedHindi = listOf(
            "sunkar","muskura","heto","hain","nahin","theek","aapko",
            "tumhe","kijiye","karein","chahiye","matlab","lekin",
            "parantu","isliye","kyunki","waise","raha","rahi","rahe",
            "bolta","bolti","kehta","kehti","sunta","sunti"
        )
        if (romanizedHindi.any { lower.contains(it) } && text.split(" ").size < 10) {
            CaptionLogger.log(TAG, "SKIP: romanized Hindi detected '${text.take(30)}'")
            return
        }

        val n = norm(text)
        if (n == lastEnqueued || n == norm(lastSentText)) {
            CaptionLogger.log(TAG, "SKIP dup")
            return
        }

        if (n.startsWith(lastEnqueued) && lastEnqueued.length > 10) {
            queue.removeIf { norm(it.text) == lastEnqueued }
            CaptionLogger.log(TAG, "SUPERSEDE: removed shorter prefix")
        }

        lastEnqueued = n
        val seq = seqCounter.incrementAndGet()

        // CRITICAL FIX: Skip-ahead — if queue already has items older than 3s,
        // drop them all and only keep this latest sentence
        // This prevents accumulating a backlog of sentences that will never be timely
        if (queue.size >= QUEUE_CAP) {
            val oldest = queue.peek()
            val oldestAge = if (oldest != null) System.currentTimeMillis() - oldest.enqMs else 0L
            if (oldestAge > STALE_MS) {
                val cleared = queue.size
                queue.clear()
                CaptionLogger.log(TAG, "SKIP-AHEAD: cleared $cleared stale items, going to latest")
            } else {
                // Queue not stale yet — just block new item if full
                // (3-item cap means this is only 1-2 sentences max)
                CaptionLogger.log(TAG, "CAP: queue full q=${queue.size}")
                return
            }
        }

        queue.offer(QItem(seq, text))
        enqCount.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ seq=$seq q=${queue.size} '${text.take(60)}'")
    }

    // Extra common fillers/interjections beyond the curated list — recognized
    // as "don't translate this" but with no fabricated Hindi transliteration.
    // Per requirement: if we don't have a confident Hindi rendering, speak the
    // original English word as-is rather than guessing or sending to CT2.
    private val INTERJECTION_VERBATIM = setOf(
        "huh", "meh", "duh", "argh", "grr", "grrr", "phew", "shh", "shhh",
        "tsk", "gosh", "geez", "jeez", "darn", "oops", "yay", "yikes",
        "bravo", "boo", "aw", "aww", "awww", "blah", "ta", "cheers", "bingo",
    )

    // Matches text against the interjection table, handling:
    //  1. Single tokens ("Wow!", "wow.", "WOW") → curated Hindi rendering
    //  2. Single tokens with no curated mapping but recognized as a filler
    //     ("Huh?", "Meh") → spoken verbatim in the original English
    //  3. Two-word compounds ("uh huh", "uh, huh", "uh uh") → ASR almost never
    //     hyphenates these the way they're written ("uh-huh"/"uh-uh"), it comes
    //     back as separate words, so this must be checked as a joined form too
    // Returns the exact text that should be handed to TTS, or null if this
    // isn't a recognized interjection at all (falls through to normal handling).
    private fun matchInterjection(text: String): String? {
        val cleaned = text.trim().trim('!', '?', '.', ',', ';', ':', '"', '\'', '…').trim()
        if (cleaned.isEmpty()) return null
        val lower = cleaned.lowercase()

        if (!lower.contains(' ')) {
            INTERJECTIONS[lower]?.let { return it }
            if (lower in INTERJECTION_VERBATIM) return cleaned
            return null
        }

        // Multi-word: strip internal punctuation/spaces and retry as one
        // token — covers "uh huh", "uh, huh", "uh-huh" all landing on the
        // same lookup key regardless of how ASR happened to punctuate it.
        val joined = lower.replace(Regex("[^a-z]"), "")
        INTERJECTIONS[joined]?.let { return it }
        if (joined in INTERJECTION_VERBATIM) return cleaned
        return null
    }

    // Lightweight sibling of enqueue() for single-word interjections/fillers.
    // Skips the min-length (4 char) and min-word-count (2 word) checks in
    // enqueue() — both of which exist to filter out fragment noise, but would
    // reject every legitimate interjection ("um", "hi", "oh" are all under
    // 4 chars or a single word). Skips the romanized-Hindi guard too — it's
    // irrelevant here since we already know exactly what this word is.
    private fun enqueueInterjection(text: String, hindiPhonetic: String) {
        val n = norm(text)
        if (n == lastEnqueued || n == norm(lastSentText)) {
            CaptionLogger.log(TAG, "SKIP dup interjection")
            return
        }
        lastEnqueued = n
        val seq = seqCounter.incrementAndGet()

        if (queue.size >= QUEUE_CAP) {
            val oldest = queue.peek()
            val oldestAge = if (oldest != null) System.currentTimeMillis() - oldest.enqMs else 0L
            if (oldestAge > STALE_MS) {
                val cleared = queue.size
                queue.clear()
                CaptionLogger.log(TAG, "SKIP-AHEAD: cleared $cleared stale items, going to latest")
            } else {
                CaptionLogger.log(TAG, "CAP: queue full q=${queue.size}")
                return
            }
        }

        queue.offer(QItem(seq, text, presetHindi = hindiPhonetic))
        enqCount.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ-INTERJECTION seq=$seq q=${queue.size} '$text' → '$hindiPhonetic'")
    }

    // ── Translation worker ────────────────────────────────────────────────────

    private var translateJob2: Job? = null

    private fun startWorker() {
        // 2 parallel workers — doubles throughput
        translateJob  = scope.launch { workerLoop("W1") }
        translateJob2 = scope.launch { workerLoop("W2") }
    }

    // Words that strongly signal the clause is NOT grammatically complete —
    // a sentence ending on any of these is almost always mid-thought,
    // regardless of word count. Checked locally (no network round-trip),
    // so it can't be defeated by server latency the way isCompleteSentence()
    // below can. This is what makes RULE 4's fast-chunking "smarter" rather
    // than just word-count-based: a chunk isn't considered safe to
    // translate early just because it hit 3+ words, if it ends on one of
    // these — that's exactly the case that breaks grammar when English and
    // Hindi have different word order (SVO vs SOV, adjective placement, etc.)
    private val DANGLING_WORDS = setOf(
        // Prepositions
        "to", "in", "on", "at", "of", "for", "with", "by", "from", "into",
        "onto", "about", "over", "under", "through", "during", "before",
        "after", "between", "among", "against", "without", "within",
        // Articles
        "a", "an", "the",
        // Coordinating conjunctions
        "and", "but", "or", "nor", "so", "yet",
        // Subordinating conjunctions / relative words — these specifically
        // introduce a clause that hasn't been completed yet
        "because", "since", "although", "though", "while", "if", "when",
        "unless", "that", "which", "who", "whom", "whose", "where", "as",
        // Bare auxiliary/helping verbs — need a main verb to complete
        "is", "am", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "will", "would", "shall", "should",
        "can", "could", "may", "might", "must", "do", "does", "did",
    )

    private fun endsWithDanglingWord(text: String): Boolean {
        val lastWord = text.trim().trimEnd('.', '!', '?', ',', ';', ':')
            .substringAfterLast(' ').lowercase()
        return lastWord in DANGLING_WORDS
    }

    // Used by RULE 1b (capitalization sentence-start signal). Checks
    // whether text is predominantly Latin-script (English, French, German,
    // Spanish, etc.) — languages without case distinction (Hindi, Chinese,
    // Arabic, Japanese, ...) don't have a capitalization convention for
    // sentence starts at all, so this signal must not apply to them.
    private fun isLatinScriptText(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val latinCount = letters.count { it.code in 0x0041..0x005A || it.code in 0x0061..0x007A }
        return latinCount.toFloat() / letters.length > 0.7f
    }

    // Conservative check for "this word looks like it starts a new
    // sentence." Excludes "I"/"I'm"/"I've" etc. (always capitalized
    // regardless of sentence position) and ALL-CAPS words (likely
    // acronyms like "NASA"/"USA", not sentence starts) — both are common
    // false-positive sources for a naive "starts with capital" check.
    private fun looksLikeSentenceStart(word: String): Boolean {
        val clean = word.trim().trimStart('"', '\'', '(', '[')
        if (clean.length < 2) return false
        if (!clean[0].isUpperCase()) return false
        if (clean == "I" || clean.startsWith("I'")) return false
        val lettersOnly = clean.filter { it.isLetter() }
        if (lettersOnly.length > 1 && lettersOnly.all { it.isUpperCase() }) return false
        return true
    }

    private fun isCompleteSentence(text: String): Boolean {
        // Local check first — reliable, instant, can't be broken by server
        // latency. If the text ends on a dangling word, it's not complete,
        // full stop — no need to even ask the server.
        if (endsWithDanglingWord(text)) return false

        return try {
            val enc = java.net.URLEncoder.encode(text.trim(), "UTF-8")
            val conn = java.net.URL("$IS_COMPLETE_URL?text=$enc")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout    = 300
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                resp.contains("\"complete\": true") || resp.contains("\"complete\":true")
            } else { conn.disconnect(); false }
        } catch (e: Exception) {
            // FIX: was `true` — defaulting to "complete" on ANY network
            // failure (timeout, error, unreachable) meant this check was
            // silently disabled whenever the server was under load, which
            // is common in this app given everything else running
            // concurrently (Whisper, CT2, TTS). Defaulting to `false`
            // (not complete) means a failed check waits longer instead of
            // firing early on an unverified fragment — the safer direction
            // for this specific tradeoff.
            false
        }
    }

    private suspend fun workerLoop(name: String) {
        while (currentCoroutineContext().isActive) {
            val item = withContext(Dispatchers.IO) {
                try { queue.poll(2, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { null }
            } ?: continue

            val seq   = item.seq
            val text  = item.text
            val ageMs = System.currentTimeMillis() - item.enqMs

            // Interjection fast-path: already know the Hindi rendering, no CT2 call needed
            if (item.presetHindi != null) {
                deliverHindi(seq, text, item.presetHindi, name, 0L)
                continue
            }

            // FIFO: removed seq < expectedSeq check — all queued sentences translate in order
            // CRITICAL FIX: Drop sentences >5s old (was 15s)
            // FIFO: no expiry — every sentence translates, even if delayed
            // Backlog only clears when Caption Lens is explicitly stopped

            // CRITICAL FIX: Check cache FIRST before any HTTP call
            val nText = norm(text)
            val cached = synchronized(translationCache) { translationCache[nText] }
            if (cached != null) {
                CaptionLogger.log(TAG, "CACHE-HIT[$name] $seq '${cached.take(40)}'")
                deliverHindi(seq, text, cached, name, 0L)
                continue
            }

            val t0     = System.currentTimeMillis()
            lastSentText = text

            // Dedup: if the same text is already being translated by another worker,
            // skip this item — the other worker's result will be cached and reused.
            // Prevents W1 and W2 both spending 5s on the same sentence.
            val alreadyRunning = !activeTranslations.add(nText)  // atomic add; returns false if already present
            if (alreadyRunning) {
                CaptionLogger.log(TAG, "DEDUP[$name] $seq — waiting for other worker's result")
                // Wait up to 9s for the other worker to populate cache
                // If W1 hits CT2 timeout (8s), cache stays empty → W2 SKIPS (not retries)
                // Previously: W2 would fall through to callServer → double CT2 timeout = 16s blocked
                var waited = 0
                var gotResult = false
                while (waited < 9_000) {
                    delay(200); waited += 200
                    val cached2 = synchronized(translationCache) { translationCache[nText] }
                    if (cached2 != null) {
                        CaptionLogger.log(TAG, "DEDUP-HIT[$name] ${waited}ms '${cached2.take(30)}'")
                        deliverHindi(seq, text, cached2, name, waited.toLong())
                        gotResult = true
                        break
                    }
                    // If other worker finished (no longer active) and cache still empty → it failed
                    if (!activeTranslations.contains(nText)) {
                        CaptionLogger.log(TAG, "DEDUP-SKIP[$name] other worker failed, skipping")
                        break
                    }
                }
                if (!gotResult)
                    CaptionLogger.log(TAG, "DEDUP-SKIP[$name] ${waited}ms no result, moving on")
                continue  // always skip — never retry with callServer
            }

            val result = callServer(text)
            activeTranslations.remove(nText)
            val ms     = System.currentTimeMillis() - t0

            // FIFO: never discard translated sentences — play in order regardless of seq
            // Previously: seq < expectedSeq caused mass discards when LC gone/appeared
            // Now: every successfully translated sentence reaches TTS

            if (result == null) {
                val ec = errCount.incrementAndGet()
                CaptionLogger.log(TAG, "ERR ${ms}ms '${text.take(40)}'")
                // After 3 consecutive errors: brief pause to let server recover
                // This prevents a stalled server from blocking the queue indefinitely
                if (ec % 3 == 0L) {
                    CaptionLogger.log(TAG, "ERR-RESET: 3 consecutive errors — pausing 1s")
                    kotlinx.coroutines.delay(1_000L)
                }
                continue
            }

            val (hindi, serverLang) = result

            // Cache the result
            synchronized(translationCache) {
                if (translationCache.size >= CACHE_MAX) {
                    translationCache.remove(translationCache.keys.first())
                }
                translationCache[nText] = hindi
            }

            deliverHindi(seq, text, hindi, name, ms)
        }
    }

    private fun deliverHindi(seq: Long, text: String, hindi: String, workerName: String, ms: Long) {
        val now   = System.currentTimeMillis()
        val hNorm = norm(hindi)
        if (hNorm == norm(lastHindiOut) && (now - lastHindiTime) < HINDI_DEDUP_MS) {
            CaptionLogger.log(TAG, "SKIP dup Hindi")
            lastEnqueued = ""; lastRawFull = ""
            return
        }
        lastHindiOut  = hindi
        lastHindiTime = now

        okCount.incrementAndGet()
        CaptionLogger.log(TAG, "OK[$workerName] $seq ${ms}ms '${hindi.take(50)}'")
        SpeechCaptureService.latestHindi   = hindi
        SpeechCaptureService.latestEnglish = text

        // DECOUPLED SUBTITLE + AUDIO — the correct sync architecture:
        // SUBTITLE: shown IMMEDIATELY when CT2 translation arrives (~0.8s after sentence).
        //   No waiting for TTS. User sees Hindi text right away. Zero gap.
        // AUDIO: queued independently, synthesized in ~0.2s, plays after subtitle appears.
        //   Natural feel: read subtitle first, hear audio 0.2s later.
        // Previously: subtitle waited for audio → 1s blank screen pause every sentence.
        // Now: subtitle at t=0ms, audio at t=200ms → always in sync, no blank gaps.

        // Music markers: show subtitle only, don't speak
        val isMusicMarker = hindi.contains('♪') || hindi == "👏 तालियाँ" || hindi == "😄 हँसी"

        scope.launch(Dispatchers.Main) {
            OverlayService.updateText(text, hindi)
            OverlayService.showTtsText(hindi)
            MainActivity.instance?.onTranslation(text, hindi, hindi)
        }

        if (!isMusicMarker) {
            // Audio plays independently — does NOT control subtitle anymore
            HindiTtsService.speak(hindi, text)
        }
    }


    private fun callServer(text: String): Pair<String, String>? {
        if (text.trim().length < 4) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            // CRITICAL FIX: 6s read timeout (was 35s)
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"auto","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                CaptionLogger.log(TAG, "HTTP ${conn.responseCode}"); return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val hindi = json.optString("text", "").trim()
            val lang  = json.optString("detected_lang", confirmedLang)
            if (hindi.isBlank()) null else Pair(hindi, lang)
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "server ex: ${e.javaClass.simpleName}: ${e.message}"); null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetAll() {
        lastEnqueued = ""; lastRawFull = ""; lastSentText = ""
        lastEnqueuedSents.clear()
        confirmedLang = ""; pendingLang = ""; pendingCount = 0
        lcVisible = false; lcGoneMs = 0L; expectedSeq = 0L
        lastHindiOut = ""; lastHindiTime = 0L
        translationCache.clear()
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && it != out.lastOrNull() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    private fun uiLabel(t: String): Boolean {
        val l = t.lowercase()
        if (l == "live caption" || l == "live captions") return true
        if (l.startsWith("live caption") && t.length < 30) return true
        if (l.contains("united states") || l.contains("united kingdom")) return true
        if (l.contains("simplified") || l.contains("traditional")) return true
        if (t == "Hide" || t == "Settings" || t == "Feedback") return true
        return false
    }

    // ── DIAGNOSTIC ONLY: test what LC's own "Hide" button actually does ──────
    // NOT wired to run automatically. This is a manual, one-time,
    // user-triggered test — see the risk explanation below.
    //
    // uiLabel() above already confirms Live Caption's own accessibility tree
    // exposes a "Hide" node. Since this app's AccessibilityService already
    // has legitimate read access to that tree, clicking it via
    // performAction(ACTION_CLICK) is a real, permitted assistive-technology
    // capability — not a workaround of any Android security boundary. BUT:
    // it is NOT confirmed what "Hide" actually does on any given device —
    // it's assumed to minimize Live Caption to its small pill (typical
    // behavior), but if it instead fully turns Live Caption off, clicking it
    // would destroy this app's only data source with no obvious cause. This
    // must be tested manually and observed once before ever being made
    // automatic.
    //
    // Returns a human-readable result string for the UI to display, so the
    // person testing can see exactly what happened.
    fun testLiveCaptionHideButton(): String {
        val wins = try { windows } catch (e: Exception) {
            return "FAILED: couldn't read windows() — ${e.message}"
        }
        var root: AccessibilityNodeInfo? = null
        wins?.forEach { w ->
            if (root != null) return@forEach
            val r = try { w.root } catch (_: Exception) { null } ?: return@forEach
            if (r.packageName?.toString() in LC_PACKAGES) root = r else r.recycle()
        }
        val lcRoot = root ?: return "FAILED: Live Caption window not currently visible — open it first"

        val hideNode = findNodeByText(lcRoot, "Hide")
        if (hideNode == null) {
            lcRoot.recycle()
            return "FAILED: no 'Hide' node found in Live Caption's current UI state"
        }
        if (!hideNode.isClickable) {
            lcRoot.recycle()
            return "FOUND 'Hide' node but it is NOT marked clickable — cannot safely trigger it"
        }

        val clicked = hideNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        lcRoot.recycle()
        return if (clicked)
            "CLICKED 'Hide' — now check manually: is Live Caption minimized to a small pill (good), or fully OFF (bad — check if captions/translation stopped working)?"
        else
            "FAILED: performAction(ACTION_CLICK) returned false — click did not register"
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == target || node.contentDescription?.toString() == target) return node
        for (i in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(i), target)
            if (found != null) return found
        }
        return null
    }

    private fun validCaption(t: String): Boolean {
        // CRITICAL FIX: Google Live Caption's accessibility node text is the FULL
        // running transcript (not just the visible on-screen line) — it keeps
        // growing as the conversation continues. The old cap of 500 chars meant
        // that after ~30-60s of continuous dialogue, this node was silently
        // rejected FOREVER (no log line existed for this rejection), nodes.filter
        // came back empty, maxByOrNull returned null, and readWindow() returned
        // null on every single poll from then on — a permanent, silent freeze
        // with zero error output. Raised drastically; our own word-count-based
        // "untranslated tail" tracking downstream already handles arbitrarily
        // long transcripts safely, so there's no reason to reject long text here.
        if (t.length < 2) return false
        if (t.length > MAX_CAPTION_LEN) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastLenRejectLogMs > 5_000L) {
                lastLenRejectLogMs = nowMs
                CaptionLogger.log(TAG, "REJECT: caption text len=${t.length} exceeds MAX_CAPTION_LEN=$MAX_CAPTION_LEN",
                    CaptionLogger.LEVEL_WARN)
            }
            return false
        }
        if (t.count { it.isLetter() } < 2) return false
        if (t.contains("com.android") || t.contains("com.google")) return false
        if (t.contains("http") || t.contains("www.")) return false
        return true
    }

    private var lastLenRejectLogMs = 0L

    private fun detectScript(text: String): String {
        var ja = 0; var zh = 0; var ko = 0; var ar = 0; var ru = 0; var hi = 0
        for (c in text) when (c.code) {
            in 0x3040..0x30FF -> ja++
            in 0x4E00..0x9FFF -> zh++
            in 0xAC00..0xD7AF -> ko++
            in 0x0600..0x06FF -> ar++
            in 0x0400..0x04FF -> ru++
            in 0x0900..0x097F -> hi++
        }
        val nonLatin = maxOf(ja, zh, ko, ar, ru, hi)
        if (nonLatin > 0) return when (nonLatin) {
            ja -> "ja"; ko -> "ko"; hi -> "hi"; ar -> "ar"; ru -> "ru"; else -> "zh"
        }
        return if (text.any { it.isLetter() && it.code in 0x00C0..0x024F })
            "latin_foreign" else "latin_en"
    }
}
