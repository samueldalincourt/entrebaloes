import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:tradutor_webtoon/core/text_pipeline.dart';

void main() {
  test(
    'cache namespace matches the exact GGUF pinned for native inference',
    () {
      final manifest =
          jsonDecode(
                File(
                  'android/app/src/main/assets/model-manifest.json',
                ).readAsStringSync(),
              )
              as Map;
      expect(manifest['modelId'], modelId);
      expect(manifest['engine'], 'llama.cpp');
      final file = (manifest['files'] as List).single as Map;
      expect(file['path'], manifest['filename']);
      expect(file['algorithm'], 'sha256');
      expect(file['hash'], matches(RegExp(r'^[a-f0-9]{64}$')));
      expect(file['size'], greaterThan(100000000));
      expect(manifest['revision'], matches(RegExp(r'^[a-f0-9]{40}$')));
    },
  );
}
