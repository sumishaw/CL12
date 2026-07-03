import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const CaptionLensApp());
}

class CaptionLensApp extends StatelessWidget {
  const CaptionLensApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Caption Lens',
        debugShowCheckedModeBanner: false,
        theme: ThemeData.dark().copyWith(
          scaffoldBackgroundColor: Colors.black,
          colorScheme: const ColorScheme.dark(primary: Color(0xFFFF3B3B)),
        ),
        home: const HomePage(),
      );
}

enum ModelState { checking, notReady, ready, error }
enum CaptionMode { liveCaptions, audioCapture }

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const _ch = MethodChannel('overlay_channel');

  // State
  String  originalText     = '';
  String  displayText      = '';
  bool    isRunning        = false;
  bool    hasOverlay       = false;
  bool    hasAccessibility = false;
  String  targetLang       = 'hindi';
  double  subtitleSpeed    = 6.0;
  bool    ttsEnabled       = false;
  String  ttsGender        = 'auto';
  double  ttsSpeed         = 1.5;   // default 1.5x

  Future<void> _setTtsEnabled(bool on) async {
    await _ch.invokeMethod('setTtsEnabled', {'enabled': on});
    if (mounted) setState(() => ttsEnabled = on);
  }

  Future<void> _setTtsGender(String gender) async {
    await _ch.invokeMethod('setTtsGender', {'gender': gender});
    if (mounted) setState(() => ttsGender = gender);
  }

  Future<void> _setTtsSpeed(double speed) async {
    await _ch.invokeMethod('setTtsSpeed', {'speed': speed});
    if (mounted) setState(() => ttsSpeed = speed);
  }
  String  statusMsg        = '';
  int     translationCount = 0;
  bool    _pulse           = false;
  Timer?  _pulseTimer;
  Timer?  _pollTimer;
  String  _lastSeenHindi   = '';

  // ── Log tab state ─────────────────────────────────────────────────────────
  final List<String> _logLines     = [];
  final ScrollController _logScroll = ScrollController();
  Timer?  _logTimer;
  int     _logTab           = 0;   // 0=main, 1=log
  String  _logFilter        = '';
  String  _logCategory      = 'ALL';
  String  _logStats         = '';
  bool    _logAutoScroll    = true;
  String  _downloadStatus   = '';
  String  _lockedLang       = '';   // '' = auto
  String  _genderDetected   = 'male';
  String  _genderSelected   = 'auto';
  bool    _genderAnalyzerOn = false;
  String  _genderStatus     = 'never started';
  int     _skippedCount     = 0;
  int     _translatedCount  = 0;

  ModelState  modelState    = ModelState.checking;
  String      modelErrorMsg = '';

  // Live Captions mode is now the primary mode
  CaptionMode captionMode = CaptionMode.liveCaptions;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _ch.setMethodCallHandler((call) async {
      if (!mounted) return;
      switch (call.method) {
        case 'onTranslation':
          final a = call.arguments as Map? ?? {};
          _applyTranslation(
            a['original']?.toString() ?? '',
            a['english']?.toString()  ?? '',
            a['hindi']?.toString()    ?? '',
          );
          break;
        case 'onModelReady':
          if (mounted) setState(() => modelState = ModelState.ready);
          break;
        case 'onModelError':
          final a   = call.arguments as Map? ?? {};
          final msg = a['message']?.toString() ?? 'CT2 translation server not reachable';
          if (mounted) setState(() { modelState = ModelState.error; modelErrorMsg = msg; });
          break;
        case 'onLiveCaptionReaderConnected':
          if (mounted) setState(() => hasAccessibility = true);
          break;
      }
    });

    _checkPermissions();
    _checkModelStatus();
    _startLogPolling();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
      _checkModelStatus();
    }
  }

  Future<void> _checkPermissions() async {
    try {
      final overlay = await _ch.invokeMethod<bool>('hasOverlayPermission') ?? false;
      if (mounted) setState(() => hasOverlay = overlay);
    } catch (_) {}
  }

  Future<void> _checkModelStatus() async {
    try {
      if (mounted) setState(() => modelState = ModelState.checking);
      final ready = await _ch.invokeMethod<bool>('isModelReady') ?? false;
      if (mounted) setState(() => modelState = ready ? ModelState.ready : ModelState.notReady);
    } catch (_) {
      if (mounted) setState(() => modelState = ModelState.notReady);
    }
  }

  void _applyTranslation(String orig, String en, String hi) {
    if (!mounted) return;
    final show = hi.isNotEmpty ? hi : en;
    if (show.isEmpty || show == _lastSeenHindi) return;
    _lastSeenHindi = show;
    setState(() {
      originalText     = orig;
      displayText      = show;
      translationCount++;
    });
  }

  void _startPolling() {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(milliseconds: 200), (_) async {
      if (!isRunning || !mounted) return;
      try {
        final result = await _ch.invokeMethod<Map>('getLatestTranslation');
        if (result == null || !mounted) return;
        _applyTranslation(
          result['original']?.toString() ?? '',
          result['english']?.toString()  ?? '',
          result['hindi']?.toString()    ?? '',
        );
      } catch (_) {}
    });
  }

  void _stopPolling() { _pollTimer?.cancel(); _pollTimer = null; }

  Future<void> _start() async {
    if (modelState != ModelState.ready) {
      setState(() => statusMsg = '⚠ Start whisper_server.py in Termux first, then tap CHECK');
      return;
    }
    if (!hasOverlay) {
      await _ch.invokeMethod('requestOverlayPermission');
      if (mounted) setState(() =>
          statusMsg = '⚠ Allow "Display over other apps" → come back → tap START');
      return;
    }

    if (captionMode == CaptionMode.liveCaptions && !hasAccessibility) {
      if (mounted) setState(() =>
          statusMsg = '⚠ Enable LiveCaptionReader in Settings → Accessibility → Caption Lens');
      return;
    }

    await _ch.invokeMethod('startOverlay');

    if (captionMode == CaptionMode.audioCapture) {
      // Legacy audio capture mode
      if (mounted) setState(() => statusMsg = '⏳ Approve "Start recording" dialog…');
      final ok = await _ch.invokeMethod<bool>('startSpeechCapture') ?? false;
      if (!ok) {
        if (mounted) setState(() => statusMsg = '⚠ Screen capture not approved');
        return;
      }
    }

    _pulseTimer?.cancel();
    _pulseTimer = Timer.periodic(const Duration(milliseconds: 700),
        (_) { if (mounted) setState(() => _pulse = !_pulse); });

    _lastSeenHindi = '';
    _startPolling();

    if (mounted) setState(() {
      isRunning        = true;
      translationCount = 0;
      statusMsg        = '';
      displayText      = captionMode == CaptionMode.liveCaptions
          ? 'Reading Live Captions…'
          : 'Listening to video audio…';
      originalText     = '';
    });
  }

  Future<void> _stop() async {
    _pulseTimer?.cancel();
    _pulse = false;
    _stopPolling();
    if (captionMode == CaptionMode.audioCapture) {
      await _ch.invokeMethod('stopSpeechCapture');
    }
    await _ch.invokeMethod('stopOverlay');
    if (mounted) setState(() {
      isRunning      = false;
      statusMsg      = '';
      displayText    = '';
      originalText   = '';
      _lastSeenHindi = '';
    });
  }

  Future<void> _setLanguage(String lang) async {
    await _ch.invokeMethod('setTargetLanguage', {'language': lang});
    if (mounted) setState(() => targetLang = lang);
  }

  Future<void> _setSubtitleSpeed(double seconds) async {
    await _ch.invokeMethod('setSubtitleSpeed', {'seconds': seconds});
    if (mounted) setState(() => subtitleSpeed = seconds);
  }

  @override
  // ── Log polling ──────────────────────────────────────────────────────────

  void _startLogPolling() {
    _logTimer?.cancel();
    _logTimer = Timer.periodic(const Duration(seconds: 2), (_) async {
      if (!mounted) return;
      try {
        final raw   = await _ch.invokeMethod<String>('getLogs', 2000);
        final stats = await _ch.invokeMethod<String>('getLogStats') ?? '';
        if (raw == null || !mounted) return;
        final lines = raw.split('\n').where((l) => l.trim().isNotEmpty).toList();
        // Count skipped/translated from logs
        int skip = 0, trans = 0;
        for (final l in lines) {
          if (l.contains('[LCReader] SKIP')) skip++;
          if (l.contains('[HindiTTS]') || l.contains('[tts]')) trans++;
        }
        final gst = await _ch.invokeMethod<Map>('getGenderStatus');
        if (!mounted) return;
        setState(() {
          _logLines
            ..clear()
            ..addAll(lines.length > 150 ? lines.sublist(lines.length - 150) : lines);
          _skippedCount  = skip;
          _translatedCount = trans;
          if (gst != null) {
            _genderDetected   = gst['detected']?.toString()  ?? 'male';
            _genderSelected   = gst['selected']?.toString()  ?? 'auto';
            _genderAnalyzerOn = gst['enabled'] as bool? ?? false;
            _genderStatus     = gst['status']?.toString()    ?? 'unknown';
          }
        });
        if (mounted) setState(() => _logStats = stats);
        if (_logTab == 1 && _logScroll.hasClients) {
          _logScroll.animateTo(
            _logScroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeOut,
          );
        }
      } catch (_) {}
    });
  }

  void dispose() {
    _pulseTimer?.cancel();
    _pollTimer?.cancel();
    _logTimer?.cancel();
    _logScroll.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // ── Build ─────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Column(
          children: [
            // ── Tab bar ────────────────────────────────────────────────────
            Row(children: [
              _tabButton('CAPTIONS', 0),
              _tabButton('LOGS${_logLines.isNotEmpty ? " (${_logLines.length})" : ""}', 1),
            ]),
            const Divider(height: 1, color: Colors.white12),
            Expanded(
              child: IndexedStack(
                index: _logTab,
                children: [
              // ── Tab 0: Main captions UI ─────────────────────────────────
              SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildHeader(),
                    const SizedBox(height: 16),
                    _buildInfoBanner(),
                    const SizedBox(height: 16),
                    _buildModelCard(),
                    const SizedBox(height: 12),
                    _buildOverlayPermRow(),
                    const SizedBox(height: 12),
                    _buildAccessibilityRow(),
                    const SizedBox(height: 16),
                    _buildModeSelector(),
                    const SizedBox(height: 16),
                    _buildLanguageChips(),
                    const SizedBox(height: 16),
                    _buildLangSelector(),
                    _buildSubtitleSpeedSlider(),
                    _buildTtsControls(),
                    const SizedBox(height: 16),
                    if (originalText.isNotEmpty) ...[
                      _buildDetectedText(),
                      const SizedBox(height: 10),
                    ],
                    if (displayText.isNotEmpty)
                      _buildTranslationOutput(),
                    if (statusMsg.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      _buildStatusBanner(),
                    ],
                    const SizedBox(height: 20),
                    _buildStartStopButton(),
                    const SizedBox(height: 8),
                    Center(
                      child: Text(
                        captionMode == CaptionMode.liveCaptions
                            ? 'Using Android Live Captions — no audio captured by this app'
                            : 'Captures internal phone audio — microphone stays off',
                        style: const TextStyle(color: Colors.white24, fontSize: 11),
                        textAlign: TextAlign.center,
                      ),
                    ),
                    const SizedBox(height: 20),
                  ],
                ),
              ),
              // ── Tab 1: Log viewer ────────────────────────────────────────
              _buildLogTab(),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _tabButton(String label, int idx) {
    final active = _logTab == idx;
    return Expanded(
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => setState(() => _logTab = idx),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 12),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(
                color: active ? Colors.redAccent : Colors.transparent,
                width: 2,
              ),
            ),
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: active ? Colors.redAccent : Colors.white38,
              fontSize: 13,
              fontWeight: active ? FontWeight.bold : FontWeight.normal,
            ),
          ),
        ),
      ),
    );
  }

  // ── Log tab ───────────────────────────────────────────────────────────────

  Widget _buildLogTab() {
    final femaleColor = Colors.pinkAccent;
    final maleColor   = Colors.lightBlueAccent;
    final gColor      = _genderDetected == 'female' ? femaleColor : maleColor;
    final filtered    = _filteredLogs();
    const cats        = ['ALL','LCReader','HindiTTS','Overlay','GenderAnalyzer','BGMusic','ERRORS'];
    const catColors   = {
      'LCReader': Color(0xFF4FC3F7), 'HindiTTS': Color(0xFF81C784),
      'Overlay': Color(0xFFFFB74D), 'GenderAnalyzer': Color(0xFFCE93D8),
      'BGMusic': Color(0xFF4DB6AC), 'ERRORS': Color(0xFFEF9A9A),
    };

    return Column(children: [
      // ── Status + action bar ──────────────────────────────────────────────
      Container(
        color: Colors.white.withValues(alpha: 0.05),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Row(children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: gColor.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: gColor.withValues(alpha: 0.5)),
            ),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Icon(Icons.mic, size: 12, color: gColor),
              const SizedBox(width: 3),
              Text('Voice: ${_genderDetected.toUpperCase()} (${_genderSelected == "auto" ? "AUTO" : _genderSelected.toUpperCase()})',
                  style: TextStyle(color: gColor, fontSize: 10, fontWeight: FontWeight.bold)),
              const SizedBox(width: 4),
              Container(width: 6, height: 6,
                  decoration: BoxDecoration(shape: BoxShape.circle,
                      color: _genderAnalyzerOn ? Colors.greenAccent : Colors.redAccent)),
            ]),
          ),
          const SizedBox(width: 6),
          Text('T:$_translatedCount S:$_skippedCount',
              style: const TextStyle(color: Colors.white38, fontSize: 10)),
          const Spacer(),
          // Auto-scroll toggle
          GestureDetector(
            onTap: () => setState(() => _logAutoScroll = !_logAutoScroll),
            child: Icon(_logAutoScroll ? Icons.vertical_align_bottom : Icons.pause,
                color: _logAutoScroll ? Colors.greenAccent : Colors.white38, size: 18),
          ),
          const SizedBox(width: 8),
          // Download button
          GestureDetector(
            onTap: _downloadLogs,
            child: const Icon(Icons.download, color: Colors.lightBlueAccent, size: 18),
          ),
          const SizedBox(width: 8),
          // Reset button
          GestureDetector(
            onTap: _resetLogs,
            child: const Icon(Icons.delete_sweep, color: Colors.redAccent, size: 18),
          ),
        ]),
      ),

      // ── Stats bar ────────────────────────────────────────────────────────
      if (_logStats.isNotEmpty)
        Container(
          width: double.infinity,
          color: const Color(0xFF1E1E3F),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          child: Text(_logStats,
              style: const TextStyle(color: Colors.white54, fontSize: 9, fontFamily: 'monospace'),
              maxLines: 2, overflow: TextOverflow.ellipsis),
        ),

      // ── Download status ──────────────────────────────────────────────────
      if (_downloadStatus.isNotEmpty)
        Container(
          width: double.infinity,
          color: _downloadStatus.startsWith('✅') ? const Color(0xFF1B3A1B) : const Color(0xFF3A1B1B),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          child: Text(_downloadStatus,
              style: const TextStyle(color: Colors.white70, fontSize: 10, fontFamily: 'monospace')),
        ),

      // ── Search bar ───────────────────────────────────────────────────────
      Padding(
        padding: const EdgeInsets.fromLTRB(8, 6, 8, 0),
        child: SizedBox(
          height: 32,
          child: TextField(
            style: const TextStyle(color: Colors.white, fontSize: 11),
            decoration: InputDecoration(
              hintText: 'Search logs...',
              hintStyle: const TextStyle(color: Colors.white30, fontSize: 11),
              prefixIcon: const Icon(Icons.search, color: Colors.white30, size: 16),
              filled: true, fillColor: const Color(0xFF1E1E3F),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: BorderSide.none),
              contentPadding: const EdgeInsets.symmetric(vertical: 0, horizontal: 8),
            ),
            onChanged: (v) => setState(() => _logFilter = v),
          ),
        ),
      ),

      // ── Category filter chips ────────────────────────────────────────────
      SizedBox(
        height: 30,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
          itemCount: cats.length,
          separatorBuilder: (_, __) => const SizedBox(width: 4),
          itemBuilder: (_, i) {
            final cat    = cats[i];
            final active = _logCategory == cat;
            final col    = catColors[cat] ?? Colors.white54;
            return GestureDetector(
              onTap: () => setState(() => _logCategory = cat),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: active ? col.withValues(alpha: 0.2) : const Color(0xFF1E1E3F),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: active ? col : Colors.white24, width: 0.8),
                ),
                child: Text(cat, style: TextStyle(color: active ? col : Colors.white38,
                    fontSize: 10, fontWeight: active ? FontWeight.bold : FontWeight.normal)),
              ),
            );
          },
        ),
      ),

      // ── Line count ───────────────────────────────────────────────────────
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 1),
        child: Align(alignment: Alignment.centerLeft,
            child: Text('${filtered.length} / ${_logLines.length} lines',
                style: const TextStyle(color: Colors.white24, fontSize: 9))),
      ),

      // ── Log lines ────────────────────────────────────────────────────────
      Expanded(
        child: filtered.isEmpty
            ? const Center(child: Text('No logs', style: TextStyle(color: Colors.white24)))
            : ListView.builder(
                controller: _logAutoScroll ? _logScroll : null,
                padding: const EdgeInsets.fromLTRB(8, 2, 8, 8),
                itemCount: filtered.length,
                itemBuilder: (_, i) {
                  final line = filtered[i];
                  Color c = Colors.white54;
                  if (line.contains('[E]') || line.contains('SUBTITLE-GONE'))   c = Colors.redAccent.withValues(alpha: 0.9);
                  else if (line.contains('[W]') || line.contains('WARN'))       c = Colors.amber.withValues(alpha: 0.9);
                  else if (line.contains('[Overlay]'))    c = const Color(0xFFFFB74D);
                  else if (line.contains('[HindiTTS]'))   c = const Color(0xFF81C784);
                  else if (line.contains('[LCReader]'))   c = const Color(0xFF4FC3F7);
                  else if (line.contains('GenderAnalyzer')) c = const Color(0xFFCE93D8);
                  else if (line.contains('BGMusic'))      c = const Color(0xFF4DB6AC);
                  else if (line.contains('female') || line.contains('FEMALE')) c = femaleColor.withValues(alpha: 0.9);
                  else if (line.contains('male') || line.contains('MALE'))     c = maleColor.withValues(alpha: 0.7);
                  else if (line.contains('ENQ') || line.contains('OK['))       c = Colors.greenAccent.withValues(alpha: 0.8);
                  else if (line.contains('ERR') || line.contains('error'))     c = Colors.redAccent.withValues(alpha: 0.8);

                  return Container(
                    color: (line.contains('SUBTITLE-GONE') || line.contains('[E]'))
                        ? Colors.red.withValues(alpha: 0.08) : Colors.transparent,
                    child: Text(line, style: TextStyle(color: c, fontSize: 10,
                        fontFamily: 'monospace', height: 1.35)),
                  );
                }),
      ),
    ]);
  }



  Future<void> _setLockedLang(String lang) async {
    await _ch.invokeMethod('setLockedLang', lang);
    if (mounted) setState(() => _lockedLang = lang);
  }

  Future<void> _loadLockedLang() async {
    final lang = await _ch.invokeMethod<String>('getLockedLang') ?? '';
    if (mounted) setState(() => _lockedLang = lang);
  }

  Future<void> _downloadLogs() async {
    try {
      final path = await _ch.invokeMethod<String>('downloadLogs') ?? '';
      if (mounted) setState(() => _downloadStatus = path.isNotEmpty ? '✅ Saved: ${path.split('/').last}' : '❌ failed');
      Future.delayed(const Duration(seconds: 5), () {
        if (mounted) setState(() => _downloadStatus = '');
      });
    } catch (e) {
      if (mounted) setState(() => _downloadStatus = '❌ $e');
    }
  }

  Future<void> _resetLogs() async {
    await _ch.invokeMethod('clearLogs');
    if (mounted) setState(() {
      _logLines.clear(); _logStats = ''; _downloadStatus = '';
      _skippedCount = 0; _translatedCount = 0;
    });
  }

  List<String> _filteredLogs() {
    return _logLines.where((l) {
      final catMatch = _logCategory == 'ALL'
          || (_logCategory == 'ERRORS' && (l.contains('ERR') || l.contains('SUBTITLE-GONE') || l.contains('[E]')))
          || l.contains('[' + _logCategory + ']')
          || l.contains(_logCategory);
      final textMatch = _logFilter.isEmpty || l.toLowerCase().contains(_logFilter.toLowerCase());
      return catMatch && textMatch;
    }).toList();
  }

  Widget _langChip(String label, String code) {
    final isSelected = _lockedLang == code;
    return GestureDetector(
      onTap: () => _setLockedLang(code),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        margin: const EdgeInsets.only(right: 6),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: isSelected ? Colors.blueAccent.withValues(alpha: 0.25) : Colors.white.withValues(alpha: 0.07),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: isSelected ? Colors.blueAccent : Colors.white24,
            width: isSelected ? 1.5 : 0.8,
          ),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Text(label, style: TextStyle(
            color: isSelected ? Colors.blueAccent : Colors.white70,
            fontSize: 11,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
          )),
          if (isSelected) ...[const SizedBox(width: 4), const Icon(Icons.lock, size: 10, color: Colors.blueAccent)],
        ]),
      ),
    );
  }

  Widget _buildHeader() => Row(children: [
    AnimatedContainer(
      duration: const Duration(milliseconds: 400),
      width: 36, height: 36,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: isRunning
            ? (_pulse ? Colors.red : Colors.red.withValues(alpha: 0.5))
            : Colors.white12,
      ),
      child: const Icon(Icons.subtitles, color: Colors.white, size: 20),
    ),
    const SizedBox(width: 12),
    const Expanded(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Caption Lens',
            style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold)),
        Text('Real-time Hindi subtitles via Live Captions',
            style: TextStyle(color: Colors.white38, fontSize: 11)),
      ]),
    ),
    if (isRunning)
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(color: Colors.red, borderRadius: BorderRadius.circular(12)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.fiber_manual_record, color: Colors.white, size: 8),
          const SizedBox(width: 4),
          Text(translationCount.toString(),
              style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold)),
        ]),
      ),
  ]);

  Widget _buildInfoBanner() {
    final isLC = captionMode == CaptionMode.liveCaptions;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.blue.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.blue.withValues(alpha: 0.2)),
      ),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Icon(Icons.info_outline, color: Colors.blue, size: 16),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            isLC
                ? 'Uses Android Live Captions for perfect speech recognition. '
                  'Enable Live Captions + this accessibility service, then tap START.'
                : 'Captures audio playing on the tablet internally — works with YouTube, VLC, '
                  'Chrome, offline videos. Approve the screen capture dialog when you tap START.',
            style: const TextStyle(color: Colors.white60, fontSize: 12, height: 1.5),
          ),
        ),
      ]),
    );
  }

  Widget _buildModelCard() {
    switch (modelState) {
      case ModelState.checking:
        return _cardShell(
          icon: Icons.hourglass_top, iconColor: Colors.white38,
          borderColor: Colors.white12, bgColor: const Color(0xFF111111),
          title: 'Checking CT2 translation server…',
          subtitle: 'Connecting to whisper_server.py on port 8765',
          trailing: const SizedBox(width: 18, height: 18,
              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white38)),
        );
      case ModelState.notReady:
        return _cardShell(
          icon: Icons.cloud_off, iconColor: Colors.orangeAccent,
          borderColor: Colors.orange.withValues(alpha: 0.4),
          bgColor: Colors.orange.withValues(alpha: 0.06),
          title: 'Translation Server Not Running',
          subtitle: 'Run in Termux:\npython3 whisper_server.py',
          trailing: ElevatedButton(
            onPressed: _checkModelStatus,
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.orangeAccent, foregroundColor: Colors.black,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('CHECK', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        );
      case ModelState.ready:
        return _cardShell(
          icon: Icons.check_circle, iconColor: Colors.greenAccent,
          borderColor: Colors.greenAccent.withValues(alpha: 0.3),
          bgColor: Colors.green.withValues(alpha: 0.06),
          title: 'Translation Server Ready',
          subtitle: 'CT2 opus-mt-en-hi · Hindi output · No censoring',
          trailing: const Icon(Icons.check, color: Colors.greenAccent, size: 20),
        );
      case ModelState.error:
        return _cardShell(
          icon: Icons.error_outline, iconColor: Colors.redAccent,
          borderColor: Colors.red.withValues(alpha: 0.4),
          bgColor: Colors.red.withValues(alpha: 0.06),
          title: 'Translation Server Unreachable',
          subtitle: modelErrorMsg.isNotEmpty ? modelErrorMsg : 'Start whisper_server.py then tap RETRY',
          trailing: ElevatedButton(
            onPressed: _checkModelStatus,
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.redAccent, foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('RETRY', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        );
    }
  }

  Widget _buildOverlayPermRow() => _permRow(
    icon: hasOverlay ? Icons.check_circle : Icons.radio_button_unchecked,
    iconColor: hasOverlay ? Colors.greenAccent : Colors.white30,
    title: 'Overlay Permission',
    subtitle: 'Required to show floating subtitles over other apps',
    granted: hasOverlay,
    onAllow: () async {
      await _ch.invokeMethod('requestOverlayPermission');
      await _checkPermissions();
    },
  );

  Widget _buildAccessibilityRow() => _permRow(
    icon: hasAccessibility ? Icons.check_circle : Icons.accessibility_new,
    iconColor: hasAccessibility ? Colors.greenAccent : Colors.white30,
    title: 'Accessibility Service',
    subtitle: hasAccessibility
        ? 'LiveCaptionReader active — reading Live Captions'
        : 'Settings → Accessibility → Caption Lens → LiveCaptionReader → ON',
    granted: hasAccessibility,
    onAllow: () async {
      await _ch.invokeMethod('openAccessibilitySettings');
    },
    allowLabel: 'Enable',
  );

  Widget _permRow({
    required IconData icon,
    required Color iconColor,
    required String title,
    required String subtitle,
    required bool granted,
    required VoidCallback onAllow,
    String allowLabel = 'Allow',
  }) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
    decoration: BoxDecoration(
      color: const Color(0xFF111111),
      borderRadius: BorderRadius.circular(10),
      border: Border.all(color: granted ? Colors.greenAccent.withValues(alpha: 0.3) : Colors.white12),
    ),
    child: Row(children: [
      Icon(icon, color: iconColor, size: 20),
      const SizedBox(width: 10),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500)),
        Text(subtitle, style: const TextStyle(color: Colors.white38, fontSize: 11, height: 1.4)),
      ])),
      if (!granted)
        GestureDetector(
          onTap: onAllow,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(color: const Color(0xFFFF3B3B), borderRadius: BorderRadius.circular(6)),
            child: Text(allowLabel,
                style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold)),
          ),
        ),
      if (granted) const Icon(Icons.check, color: Colors.greenAccent, size: 16),
    ]),
  );

  Widget _buildModeSelector() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Text('Caption Source',
          style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(height: 8),
      Row(children: [
        _modeBtn(
          icon: Icons.closed_caption,
          label: 'Live Captions',
          sublabel: 'Recommended',
          mode: CaptionMode.liveCaptions,
        ),
        const SizedBox(width: 10),
        _modeBtn(
          icon: Icons.mic_none,
          label: 'Audio Capture',
          sublabel: 'Legacy',
          mode: CaptionMode.audioCapture,
        ),
      ]),
    ],
  );

  Widget _modeBtn({
    required IconData icon,
    required String label,
    required String sublabel,
    required CaptionMode mode,
  }) {
    final sel = captionMode == mode;
    return Expanded(
      child: GestureDetector(
        onTap: isRunning ? null : () => setState(() => captionMode = mode),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 10),
          decoration: BoxDecoration(
            color: sel ? const Color(0xFFFF3B3B).withValues(alpha: 0.15) : const Color(0xFF1E1E1E),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: sel ? const Color(0xFFFF3B3B) : Colors.white12),
          ),
          child: Column(children: [
            Icon(icon, color: sel ? const Color(0xFFFF3B3B) : Colors.white38, size: 22),
            const SizedBox(height: 4),
            Text(label, style: TextStyle(
              color: sel ? Colors.white : Colors.white54,
              fontSize: 12, fontWeight: sel ? FontWeight.bold : FontWeight.normal,
            )),
            Text(sublabel, style: TextStyle(
              color: sel ? Colors.redAccent.withValues(alpha: 0.7) : Colors.white24,
              fontSize: 10,
            )),
          ]),
        ),
      ),
    );
  }

  Widget _buildLanguageChips() => Container(
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFF0a1628),
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: Colors.blue.withValues(alpha: 0.2)),
    ),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Text('Detects & Translates',
          style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(height: 10),
      Wrap(spacing: 8, runSpacing: 8, children: [
        _chip('🇯🇵 Japanese'), _chip('🇨🇳 Chinese'),    _chip('🇰🇷 Korean'),
        _chip('🇫🇷 French'),   _chip('🇩🇪 German'),    _chip('🇪🇸 Spanish'),
        _chip('🇹🇷 Turkish'),  _chip('🇸🇦 Arabic'),    _chip('🇧🇷 Portuguese'),
        _chip('🇷🇺 Russian'),  _chip('🇮🇩 Indonesian'), _chip('🇬🇧 English'),
      ]),
      const SizedBox(height: 10),
      const Text('Works with',
          style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 8, children: [
        _chip('📺 YouTube'), _chip('🌐 Chrome'), _chip('🦊 Firefox'),
        _chip('🎬 VLC'),     _chip('📁 Offline'), _chip('+ Any app'),
      ]),
    ]),
  );

  Widget _buildLangSelector() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      // ── LC Language Lock ──────────────────────────────────────────────
      Padding(
        padding: const EdgeInsets.fromLTRB(0, 0, 0, 8),
        child: Row(children: [
          const Icon(Icons.lock_outline, color: Colors.white38, size: 13),
          const SizedBox(width: 5),
          const Text('Lock Caption Language',
              style: TextStyle(color: Colors.white54, fontSize: 12)),
          const Spacer(),
          if (_lockedLang.isNotEmpty)
            GestureDetector(
              onTap: () => _setLockedLang(''),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.orange.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: Colors.orange.withValues(alpha: 0.5)),
                ),
                child: const Text('Unlock Auto',
                    style: TextStyle(color: Colors.orange, fontSize: 10)),
              ),
            ),
        ]),
      ),
      SizedBox(
        height: 34,
        child: ListView(
          scrollDirection: Axis.horizontal,
          children: [
            _langChip('AUTO', ''),
            _langChip('English', 'latin_en'),
            _langChip('Chinese', 'zh'),
            _langChip('Japanese', 'ja'),
            _langChip('Korean', 'ko'),
            _langChip('French', 'latin_foreign'),
            _langChip('Arabic', 'ar'),
            _langChip('Russian', 'ru'),
            _langChip('Hindi', 'hi'),
            _langChip('Spanish', 'latin_foreign'),
            _langChip('German', 'latin_foreign'),
            _langChip('Turkish', 'latin_foreign'),
            _langChip('Portuguese', 'latin_foreign'),
            _langChip('Indonesian', 'latin_foreign'),
          ],
        ),
      ),
      const SizedBox(height: 10),
      const Text('Translate to',
          style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(height: 8),
      Row(children: [
        _langBtn('🇬🇧 English', 'english'),
        const SizedBox(width: 10),
        _langBtn('🇮🇳 Hindi', 'hindi'),
      ]),
    ],
  );

  Widget _buildSubtitleSpeedSlider() {
    final presets = [
      {'label': 'Live',    'seconds': 0.0},
      {'label': 'Fastest', 'seconds': 2.0},
      {'label': 'Fast',    'seconds': 4.0},
      {'label': 'Average', 'seconds': 6.0},
      {'label': 'Slow',    'seconds': 8.0},
      {'label': 'Slowest', 'seconds': 10.0},
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 16),
        const Text('Subtitle display time',
            style: TextStyle(color: Colors.white54, fontSize: 12,
                fontWeight: FontWeight.bold)),
        const SizedBox(height: 10),
        Row(
          children: presets.map((p) {
            final secs    = p['seconds'] as double;
            final label   = p['label']   as String;
            final selected = subtitleSpeed == secs;
            return Expanded(
              child: GestureDetector(
                onTap: () => _setSubtitleSpeed(secs),
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 3),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  decoration: BoxDecoration(
                    color: selected
                        ? const Color(0xFFFF3B3B)
                        : Colors.white10,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                      color: selected
                          ? const Color(0xFFFF3B3B)
                          : Colors.white12,
                      width: 1,
                    ),
                  ),
                  child: Column(
                    children: [
                      Text(label,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: selected ? Colors.white : Colors.white54,
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                          )),
                      const SizedBox(height: 2),
                      Text('${secs == 0.0 ? "⚡" : "${secs.toInt()}s"}',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: selected ? Colors.white70 : Colors.white24,
                            fontSize: 10,
                          )),
                    ],
                  ),
                ),
              ),
            );
          }).toList(),
        ),
      ],
    );
  }

  Widget _buildTtsControls() {
    const accent = Color(0xFFFF3B3B);
    final genders = [
      {'label': '🔍 Auto', 'value': 'auto'},
      {'label': '👨 Male',  'value': 'male'},
      {'label': '👩 Female','value': 'female'},
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 20),
        Row(children: [
          const Text('Hindi Voice (TTS)',
              style: TextStyle(color: Colors.white54, fontSize: 12,
                  fontWeight: FontWeight.bold)),
          const Spacer(),
          GestureDetector(
            onTap: () => _setTtsEnabled(!ttsEnabled),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              width: 48, height: 26,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(13),
                color: ttsEnabled ? accent : Colors.white12,
              ),
              child: AnimatedAlign(
                duration: const Duration(milliseconds: 200),
                alignment: ttsEnabled
                    ? Alignment.centerRight : Alignment.centerLeft,
                child: Container(
                  margin: const EdgeInsets.all(3),
                  width: 20, height: 20,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: ttsEnabled ? Colors.white : Colors.white38,
                  ),
                ),
              ),
            ),
          ),
        ]),
        if (ttsEnabled) ...[
          const SizedBox(height: 10),
          const Text('Voice Gender',
              style: TextStyle(color: Colors.white38, fontSize: 11)),
          const SizedBox(height: 8),
          Row(children: genders.map((g) {
            final val      = g['value']!;
            final selected = ttsGender == val;
            return Expanded(
              child: GestureDetector(
                onTap: () => _setTtsGender(val),
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 3),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  decoration: BoxDecoration(
                    color: selected ? accent : Colors.white10,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                      color: selected ? accent : Colors.white12),
                  ),
                  child: Text(g['label']!,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12, fontWeight: FontWeight.bold,
                        color: selected ? Colors.white : Colors.white54,
                      )),
                ),
              ),
            );
          }).toList()),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: Colors.white.withAlpha(13),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              ttsGender == 'auto'
                  ? '🔍 Auto-detecting speaker gender from audio pitch'
                  : ttsGender == 'male'
                      ? '👨 Using male Hindi voice'
                      : '👩 Using female Hindi voice',
              style: const TextStyle(color: Colors.white38, fontSize: 11),
            ),
          ),
          const SizedBox(height: 12),
          const Text('Voice speed',
              style: TextStyle(color: Colors.white38, fontSize: 11)),
          const SizedBox(height: 6),
          Row(children: [1.0, 1.5, 2.0, 3.0, 4.0].map((spd) {
            final selected = ttsSpeed == spd;
            final label = spd == 1.0 ? '1×' : spd == 1.5 ? '1.5×'
                        : spd == 2.0 ? '2×' : spd == 3.0 ? '3×' : '4×';
            return Expanded(
              child: GestureDetector(
                onTap: () => _setTtsSpeed(spd),
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 2),
                  padding: const EdgeInsets.symmetric(vertical: 7),
                  decoration: BoxDecoration(
                    color: selected ? accent : Colors.white10,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: selected ? accent : Colors.white12),
                  ),
                  child: Text(label,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12, fontWeight: FontWeight.bold,
                        color: selected ? Colors.white : Colors.white54,
                      )),
                ),
              ),
            );
          }).toList()),
        ],
      ],
    );
  }

  Widget _buildDetectedText() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        captionMode == CaptionMode.liveCaptions ? 'Live Caption' : 'Detected audio',
        style: const TextStyle(color: Colors.white38, fontSize: 12),
      ),
      const SizedBox(height: 6),
      Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
            color: Colors.white10, borderRadius: BorderRadius.circular(10)),
        child: Text(originalText,
            style: const TextStyle(color: Colors.white60, fontSize: 15, letterSpacing: 0.3)),
      ),
    ],
  );

  Widget _buildTranslationOutput() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        targetLang == 'hindi' ? '🇮🇳 Hindi Translation' : '🇬🇧 English Translation',
        style: const TextStyle(color: Colors.greenAccent, fontSize: 12, fontWeight: FontWeight.bold),
      ),
      const SizedBox(height: 6),
      AnimatedSwitcher(
        duration: const Duration(milliseconds: 80),
        child: Container(
          key: ValueKey(displayText),
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.green.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.greenAccent.withValues(alpha: 0.3)),
          ),
          child: Text(displayText,
              style: const TextStyle(
                  color: Colors.greenAccent, fontSize: 22,
                  fontWeight: FontWeight.bold, height: 1.4)),
        ),
      ),
    ],
  );

  Widget _buildStatusBanner() => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Colors.orange.withValues(alpha: 0.1),
      borderRadius: BorderRadius.circular(8),
      border: Border.all(color: Colors.orange.withValues(alpha: 0.3)),
    ),
    child: Row(children: [
      const Icon(Icons.info_outline, color: Colors.orange, size: 16),
      const SizedBox(width: 8),
      Expanded(child: Text(statusMsg,
          style: const TextStyle(color: Colors.orange, fontSize: 13))),
    ]),
  );

  Widget _buildStartStopButton() {
    final busy = modelState == ModelState.checking;
    final readyToStart = modelState == ModelState.ready && hasOverlay &&
        (captionMode == CaptionMode.audioCapture || hasAccessibility);

    return SizedBox(
      width: double.infinity,
      child: ElevatedButton.icon(
        onPressed: busy ? null : (isRunning ? _stop : (readyToStart ? _start : _start)),
        icon: Icon(
          isRunning ? Icons.stop_circle_outlined : Icons.play_circle_outline,
          size: 26,
        ),
        label: Text(
          isRunning ? 'STOP' : (captionMode == CaptionMode.liveCaptions
              ? 'START — LIVE CAPTIONS MODE'
              : 'START — CAPTURE VIDEO AUDIO'),
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, letterSpacing: 0.5),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: isRunning ? const Color(0xFF333333) : const Color(0xFFFF3B3B),
          foregroundColor: Colors.white,
          disabledBackgroundColor: Colors.grey.shade800,
          disabledForegroundColor: Colors.white38,
          padding: const EdgeInsets.symmetric(vertical: 18),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          elevation: 4,
        ),
      ),
    );
  }

  Widget _chip(String label) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: 0.06),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: Colors.white12),
    ),
    child: Text(label, style: const TextStyle(color: Colors.white60, fontSize: 12)),
  );

  Widget _langBtn(String label, String value) {
    final selected = targetLang == value;
    return GestureDetector(
      onTap: () => _setLanguage(value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
        decoration: BoxDecoration(
          color: selected ? const Color(0xFFFF3B3B) : const Color(0xFF1E1E1E),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: selected ? const Color(0xFFFF3B3B) : Colors.white12),
        ),
        child: Text(label,
            style: TextStyle(
              color: selected ? Colors.white : Colors.white54,
              fontWeight: selected ? FontWeight.bold : FontWeight.normal,
              fontSize: 14,
            )),
      ),
    );
  }

  Widget _cardShell({
    required IconData icon, required Color iconColor,
    required Color borderColor, required Color bgColor,
    required String title, required String subtitle, Widget? trailing,
  }) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
    decoration: BoxDecoration(
      color: bgColor,
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: borderColor),
    ),
    child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
      Icon(icon, color: iconColor, size: 22),
      const SizedBox(width: 12),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
        const SizedBox(height: 3),
        Text(subtitle, style: const TextStyle(color: Colors.white54, fontSize: 11, height: 1.4)),
      ])),
      if (trailing != null) ...[const SizedBox(width: 10), trailing],
    ]),
  );
}
