import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tradutor_webtoon/main.dart';

void main() {
  testWidgets(
    'requires online consent and a tested key before allowing capture',
    (tester) async {
      var mode = 'fast';
      final changes = <MethodCall>[];
      const channel = MethodChannel('tradutor/commands');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'setTranslationMode') {
          changes.add(call);
          mode = (call.arguments as Map)['mode'] as String;
          return null;
        }
        if (call.method == 'status') {
          return <String, Object?>{
            'overlayPermission': true,
            'accessibility': true,
            'readerInstalled': true,
            'runtimeAvailable': true,
            'modelReady': mode != 'online',
            'translationMode': mode,
            'onlineConsent': mode == 'online',
            'onlineKeyConfigured': false,
            'onlineModel': 'gemini-3.8-flash',
          };
        }
        return null;
      });
      messenger.setMockMethodCallHandler(
        const MethodChannel('tradutor/events'),
        (_) async => null,
      );
      await tester.binding.setSurfaceSize(const Size(430, 2400));
      await tester.pumpWidget(const TranslatorApp());
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Online'));
      await tester.tap(find.text('Online'));
      await tester.pumpAndSettle();
      expect(find.text('Ativar tradução online'), findsOneWidget);
      expect(changes, isEmpty);
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();
      expect(changes, isEmpty);
      await tester.tap(find.text('Online'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ativar online'));
      await tester.pumpAndSettle();
      expect(changes, hasLength(1));
      expect((changes.single.arguments as Map)['consent'], isTrue);
      final key = tester
          .widgetList<TextField>(find.byType(TextField))
          .singleWhere(
            (field) => field.decoration?.labelText == 'Chave do Gemini',
          );
      expect(key.obscureText, isTrue);
      expect(key.enableSuggestions, isFalse);
      final start = find.widgetWithText(
        FilledButton,
        'Iniciar tradução online',
      );
      await tester.ensureVisible(start);
      expect(tester.widget<FilledButton>(start).onPressed, isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.binding.setSurfaceSize(null);
    },
  );
}
