import 'dart:io';
import 'dart:convert';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tradutor_webtoon/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const commands = MethodChannel('tradutor/commands');
  setUpAll(() async {
    final packageFile = File('.dart_tool/package_config.json');
    final packages =
        (jsonDecode(await packageFile.readAsString()) as Map)['packages']
            as List;
    final flutter = packages.cast<Map>().singleWhere(
      (p) => p['name'] == 'flutter',
    );
    final sdk = Directory.fromUri(
      packageFile.uri.resolve(flutter['rootUri'] as String),
    ).parent.parent;
    final fonts = '${sdk.path}/bin/cache/artifacts/material_fonts';
    Future<void> load(String family, String name) async {
      final loader = FontLoader(
        family,
      )..addFont(File('$fonts/$name').readAsBytes().then(ByteData.sublistView));
      await loader.load();
    }

    await load('sans-serif', 'roboto-regular.ttf');
    await load('MaterialIcons', 'materialicons-regular.otf');
  });
  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          commands,
          (call) async => call.method == 'status'
              ? <String, Object?>{
                  'overlayPermission': false,
                  'accessibility': false,
                  'modelReady': false,
                  'runtimeAvailable': true,
                  'readerInstalled': true,
                  'topCrop': .1,
                  'bottomCrop': .1,
                }
              : null,
        );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('tradutor/events'),
          (_) async => null,
        );
  });
  testWidgets('requires permissions and explains offline model setup', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(430, 1800));
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(key: boundary, child: const TranslatorApp()),
    );
    await tester.pumpAndSettle();
    expect(find.text('Sua história.\nNo seu idioma.'), findsOneWidget);
    expect(find.text('Sobreposição'), findsOneWidget);
    expect(find.text('Acessibilidade'), findsOneWidget);
    final start = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Traduzir com Google (offline)'),
    );
    expect(start.onPressed, isNull);
    final render =
        boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    await tester.runAsync(() async {
      final image = await render.toImage(pixelRatio: 1);
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      await Directory('build').create(recursive: true);
      await File(
        'build/ui-preview.png',
      ).writeAsBytes(bytes!.buffer.asUint8List());
      image.dispose();
    });
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.binding.setSurfaceSize(null);
  });
}
