import 'dart:async';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'ocr_reader.dart';
import '../platform/native_bridge.dart';
import 'text_pipeline.dart';
import 'translation_cache.dart';

class OverlaySnapshot {
  const OverlaySnapshot(this.frame, this.balloons);
  final CaptureFrame frame;
  final List<TranslatedBalloon> balloons;
}

class TranslationCoordinator extends ChangeNotifier {
  TranslationCoordinator(
    this.bridge,
    this.cache, {
    OcrReader? reader,
    this.maxBatchSize = 4,
  }) : _reader = reader ?? MlkitOcrReader();
  final NativeBridge bridge;
  final TranslationCache cache;
  final OcrReader _reader;
  final int maxBatchSize;
  StreamSubscription<Map<Object?, Object?>>? _subscription;
  OverlaySnapshot? snapshot;
  int _generation = -1;
  int? _pending;
  bool _busy = false, _disposed = false;
  Future<void> start() async {
    _subscription = bridge.events.listen((event) {
      final generation = event['generation'] as int?;
      switch (event['type']) {
        case 'invalidate':
          if (generation != null) _generation = generation;
          _pending = null;
          snapshot = null;
          notifyListeners();
        case 'stable':
          if (generation == null || generation < _generation) return;
          _generation = generation;
          _pending = generation;
          unawaited(_drain());
      }
    });
    await bridge.command('overlayReady');
  }

  bool _current(int generation) => !_disposed && generation == _generation;
  Future<void> _drain() async {
    if (_busy || _disposed) return;
    _busy = true;
    try {
      while (_pending != null && !_disposed) {
        final generation = _pending!;
        _pending = null;
        try {
          await _process(generation);
        } on PlatformException catch (error) {
          if (_current(generation) && error.code != 'STALE') {
            await bridge.command('pipelineError', {
              'generation': generation,
              'message': error.message ?? error.code,
            });
          }
        } catch (error) {
          if (_current(generation)) {
            await bridge.command('pipelineError', {
              'generation': generation,
              'message': error.toString(),
            });
          }
        }
      }
    } finally {
      _busy = false;
    }
  }

  Future<void> _process(int generation) async {
    final timer = Stopwatch()..start();
    Future<void> stage(String message) => bridge.command('pipelineStage', {
      'generation': generation,
      'message': message,
    });
    final frame = CaptureFrame.fromMap(await bridge.capture(generation));
    List<Paragraph> paragraphs;
    try {
      if (!_current(generation)) return;
      await stage('Reconhecendo o texto da imagem.');
      final recognized = await _reader.read(frame.path);
      if (!_current(generation)) return;
      paragraphs = groupLines(recognized);
      await bridge.command('pipelineOcr', {
        'generation': generation,
        'lines': [
          for (final line in recognized.take(80))
            {
              'text': line.text,
              'block': line.block,
              'box': [
                line.bounds.left,
                line.bounds.top,
                line.bounds.right,
                line.bounds.bottom,
              ],
            },
        ],
        'paragraphs': [
          for (final paragraph in paragraphs.take(40))
            {
              'text': paragraph.text,
              'box': [
                paragraph.bounds.left,
                paragraph.bounds.top,
                paragraph.bounds.right,
                paragraph.bounds.bottom,
              ],
            },
        ],
      });
    } finally {
      final image = File(frame.path);
      if (await image.exists()) await image.delete();
    }
    if (!_current(generation)) return;
    await stage(
      paragraphs.isEmpty
          ? 'Nenhum texto legível encontrado nesta área. Confira as margens do OCR.'
          : '${paragraphs.length} regiões de texto encontradas. Consultando cache.',
    );
    final translated = <String, String>{};
    final unique = <String, Paragraph>{};
    var hits = 0;
    for (final p in paragraphs) {
      final cached = await cache.find(p.text);
      if (!_current(generation)) return;
      if (cached != null) {
        translated[normalizeText(p.text)] = cached;
        hits++;
      } else {
        unique.putIfAbsent(normalizeText(p.text), () => p);
      }
    }
    Future<void> publish() async {
      if (!_current(generation)) return;
      await stage('Desenhando as traduções.');
      if (!_current(generation)) return;
      snapshot = OverlaySnapshot(frame, [
        for (final p in paragraphs)
          if (translated.containsKey(normalizeText(p.text)))
            TranslatedBalloon(p, translated[normalizeText(p.text)]!),
      ]);
      notifyListeners();
      await WidgetsBinding.instance.endOfFrame;
      if (!_current(generation)) return;
      await bridge.command('showOverlay', {'generation': generation});
    }

    if (translated.isNotEmpty) await publish();
    for (final batch in makeBatches(
      unique.values.toList(),
      maxItems: maxBatchSize,
    )) {
      if (!_current(generation)) return;
      await stage('Traduzindo ${batch.length} regiões de texto.');
      Map<String, String>? result;
      for (var attempt = 0; attempt < 2; attempt++) {
        final output = await bridge.translate(
          translationPrompt,
          batchInput(batch),
          generation,
          context: paragraphs.take(8).map((p) => p.text).toList(),
        );
        if (!_current(generation)) return;
        try {
          result = parseTranslations(output, batch);
          break;
        } on FormatException {
          if (attempt == 1) rethrow;
        }
      }
      for (final p in batch) {
        final text = result![p.id]!;
        await cache.save(p.text, text);
        translated[normalizeText(p.text)] = text;
      }
      await publish();
    }
    if (!_current(generation)) return;
    await bridge.command('pipelineMetrics', {
      'generation': generation,
      'elapsedMs': timer.elapsedMilliseconds,
      'balloons': paragraphs.length,
      'cacheHits': hits,
      'newTranslations': unique.length,
    });
    await stage(
      paragraphs.isEmpty
          ? 'Nenhum texto legível encontrado nesta área. Confira as margens do OCR.'
          : '${translated.length} traduções prontas.',
    );
  }

  @override
  void dispose() {
    _disposed = true;
    unawaited(_subscription?.cancel());
    unawaited(_reader.close());
    unawaited(cache.close());
    super.dispose();
  }
}
