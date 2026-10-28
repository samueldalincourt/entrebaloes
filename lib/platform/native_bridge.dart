import 'package:flutter/services.dart';

class NativeBridge {
  const NativeBridge();
  static const _commands = MethodChannel('tradutor/commands');
  static const _events = EventChannel('tradutor/events');
  Stream<Map<Object?, Object?>> get events => _events
      .receiveBroadcastStream()
      .map((event) => Map<Object?, Object?>.from(event as Map));
  Future<Map<Object?, Object?>> status() async =>
      (await _commands.invokeMapMethod<Object?, Object?>('status'))!;
  Future<void> command(String method, [Map<String, Object?>? arguments]) =>
      _commands.invokeMethod<void>(method, arguments);
  Future<Map<Object?, Object?>> capture(int generation) async =>
      (await _commands.invokeMapMethod<Object?, Object?>('capture', {
        'generation': generation,
      }))!;
  Future<String> translate(
    String system,
    String input,
    int generation, {
    List<String> context = const [],
  }) async => (await _commands.invokeMethod<String>('translate', {
    'system': system,
    'input': input,
    'generation': generation,
    'context': context,
  }))!;
}
