import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:tradutor_webtoon/core/translation_cache.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });
  test(
    'persists translations across database reopen and ignores OCR line wraps',
    () async {
      final temp = await Directory.systemTemp.createTemp('webtoon-cache-test-');
      final file = '${temp.path}/test.db';
      var cache = SqliteTranslationCache(databasePath: file);
      try {
        expect(
          await cache.find("I'm going to be the king of the pirates!"),
          isNull,
        );
        await cache.save(
          "I'm going to be the king of the pirates!",
          'Eu vou ser o rei dos piratas!',
        );
        await cache.close();
        cache = SqliteTranslationCache(databasePath: file);
        expect(
          await cache.find("I'm going to be\n the king of the pirates!"),
          'Eu vou ser o rei dos piratas!',
        );
        expect(
          await cache.find("I'm going to be the king of the pirates?"),
          isNull,
        );
      } finally {
        await cache.close();
        await temp.delete(recursive: true);
      }
    },
  );
  test('keeps fast-engine results separate from LLM translations', () async {
    final temp = await Directory.systemTemp.createTemp('webtoon-engine-cache-');
    final file = '${temp.path}/test.db';
    var cache = SqliteTranslationCache(databasePath: file);
    try {
      await cache.save('Like I said before.', 'Resultado antigo do LLM');
      await cache.close();
      cache = SqliteTranslationCache(
        databasePath: file,
        namespace: 'MLKit-translate-17.0.3-en-pt-v1',
      );
      expect(await cache.find('Like I said before.'), isNull);
      await cache.save('Like I said before.', 'Como eu disse antes.');
      await cache.close();
      cache = SqliteTranslationCache(databasePath: file);
      expect(
        await cache.find('Like I said before.'),
        'Resultado antigo do LLM',
      );
    } finally {
      await cache.close();
      await temp.delete(recursive: true);
    }
  });
}
