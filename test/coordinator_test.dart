import 'dart:async';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tradutor_webtoon/core/ocr_reader.dart';
import 'package:tradutor_webtoon/core/text_pipeline.dart';
import 'package:tradutor_webtoon/core/translation_cache.dart';
import 'package:tradutor_webtoon/core/translation_coordinator.dart';
import 'package:tradutor_webtoon/platform/native_bridge.dart';

class TestBridge extends NativeBridge {
  final controller = StreamController<Map<Object?, Object?>>.broadcast(
    sync: true,
  );
  final commands = <String>[];
  final shownGenerations = <int>[];
  final translation = Completer<String>();
  int translations = 0;
  @override
  Stream<Map<Object?, Object?>> get events => controller.stream;
  @override
  Future<void> command(String method, [Map<String, Object?>? arguments]) async {
    commands.add(method);
    if (method == 'showOverlay') {
      shownGenerations.add(arguments!['generation']! as int);
    }
  }

  @override
  Future<Map<Object?, Object?>> capture(int generation) async => {
    'path': 'build/nonexistent-test-frame.png',
    'width': 1080,
    'height': 2400,
    'cropTop': 240,
    'cropHeight': 1920,
    'generation': generation,
  };
  @override
  Future<String> translate(
    String system,
    String input,
    int generation, {
    List<String> context = const [],
  }) {
    translations++;
    return translation.future;
  }

  void emit(String type, int generation) =>
      controller.add({'type': type, 'generation': generation});
}

class TestCache implements TranslationCache {
  final values = <String, String>{};
  @override
  Future<String?> find(String source) async => values[normalizeText(source)];
  @override
  Future<void> save(String source, String translation) async {
    values[normalizeText(source)] = translation;
  }

  @override
  Future<void> close() async {}
}

class TestReader implements OcrReader {
  @override
  Future<List<OcrLine>> read(String _) async => const [
    OcrLine('Hello!', Rect.fromLTWH(100, 100, 120, 40), 0),
  ];
  @override
  Future<void> close() async {}
}

class TwoBalloonReader extends TestReader {
  @override
  Future<List<OcrLine>> read(String _) async => const [
    OcrLine('Hello!', Rect.fromLTWH(100, 100, 120, 40), 0),
    OcrLine('Bye!', Rect.fromLTWH(100, 400, 120, 40), 1),
  ];
}

class SequentialBridge extends TestBridge {
  final replies = [Completer<String>(), Completer<String>()];
  @override
  Future<String> translate(
    String system,
    String input,
    int generation, {
    List<String> context = const [],
  }) {
    return replies[translations++].future;
  }
}

void main() {
  Future<void> waitFor(WidgetTester tester, bool Function() condition) async {
    for (var i = 0; i < 30 && !condition(); i++) {
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 5)),
      );
      await tester.pump();
    }
  }

  testWidgets('shows the first offline balloon before the second finishes', (
    tester,
  ) async {
    final bridge = SequentialBridge();
    final coordinator = TranslationCoordinator(
      bridge,
      TestCache(),
      reader: TwoBalloonReader(),
      maxBatchSize: 1,
    );
    await coordinator.start();
    bridge.emit('stable', 1);
    await waitFor(tester, () => bridge.translations == 1);
    bridge.replies[0].complete('{"b0":"Olá!"}');
    await waitFor(tester, () => bridge.translations == 2);
    expect(coordinator.snapshot!.balloons.single.translation, 'Olá!');
    expect(bridge.shownGenerations, [1]);
    bridge.replies[1].complete('{"b1":"Tchau!"}');
    await waitFor(tester, () => coordinator.snapshot?.balloons.length == 2);
    expect(coordinator.snapshot!.balloons.map((b) => b.translation), [
      'Olá!',
      'Tchau!',
    ]);
    coordinator.dispose();
    await bridge.controller.close();
  });

  testWidgets(
    'drops late inference after scrolling and never caches stale output',
    (tester) async {
      final bridge = TestBridge();
      final cache = TestCache();
      final coordinator = TranslationCoordinator(
        bridge,
        cache,
        reader: TestReader(),
      );
      await coordinator.start();
      bridge.emit('stable', 1);
      await waitFor(tester, () => bridge.translations == 1);
      expect(bridge.translations, 1);
      bridge.emit('invalidate', 2);
      bridge.translation.complete('{"b0":"Olá!"}');
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 10)),
      );
      await tester.pump();
      expect(coordinator.snapshot, isNull);
      expect(cache.values, isEmpty);
      expect(bridge.commands, isNot(contains('showOverlay')));
      coordinator.dispose();
      await bridge.controller.close();
    },
  );
  testWidgets('uses cached translation without invoking inference', (
    tester,
  ) async {
    final bridge = TestBridge();
    final cache = TestCache()..values['Hello!'] = 'Olá!';
    final coordinator = TranslationCoordinator(
      bridge,
      cache,
      reader: TestReader(),
    );
    await coordinator.start();
    bridge.emit('stable', 1);
    await waitFor(tester, () => bridge.commands.contains('showOverlay'));
    expect(bridge.translations, 0);
    expect(coordinator.snapshot!.balloons.single.translation, 'Olá!');
    expect(bridge.commands, contains('showOverlay'));
    bridge.emit('invalidate', 2);
    expect(coordinator.snapshot, isNull);
    coordinator.dispose();
    await bridge.controller.close();
  });
  testWidgets('resumes after pausing during an in-flight translation', (
    tester,
  ) async {
    final bridge = TestBridge();
    final cache = TestCache();
    final coordinator = TranslationCoordinator(
      bridge,
      cache,
      reader: TestReader(),
    );
    await coordinator.start();
    bridge.emit('stable', 1);
    await waitFor(tester, () => bridge.translations == 1);
    bridge.emit('invalidate', 2);
    bridge.emit('stable', 3);
    bridge.translation.complete('{"b0":"Olá!"}');
    await waitFor(tester, () => bridge.shownGenerations.contains(3));
    expect(bridge.shownGenerations, [3]);
    expect(coordinator.snapshot!.balloons.single.translation, 'Olá!');
    expect(cache.values['Hello!'], 'Olá!');
    coordinator.dispose();
    await bridge.controller.close();
  });
}
