import 'dart:async';
import 'package:flutter/material.dart';
import 'core/translation_cache.dart';
import 'core/translation_coordinator.dart';
import 'platform/native_bridge.dart';
import 'ui/home_screen.dart';
import 'ui/overlay_view.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const TranslatorApp());
}

@pragma('vm:entry-point')
Future<void> overlayMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  const bridge = NativeBridge();
  final status = await bridge.status();
  final coordinator = TranslationCoordinator(
    bridge,
    SqliteTranslationCache(namespace: status['cacheNamespace']! as String),
    maxBatchSize: status['translationMode'] == 'llm' ? 1 : 4,
  );
  runApp(
    OverlayView(
      coordinator: coordinator,
      googleAttribution: status['translationMode'] == 'fast',
      onlineAttribution: status['translationMode'] == 'online',
    ),
  );
  unawaited(coordinator.start());
}

class TranslatorApp extends StatelessWidget {
  const TranslatorApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Entre Balões',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xffbff6dc),
        brightness: Brightness.dark,
      ),
      scaffoldBackgroundColor: const Color(0xff101826),
      fontFamily: 'sans-serif',
    ),
    home: const HomeScreen(),
  );
}
