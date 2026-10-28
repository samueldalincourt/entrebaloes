import 'package:path/path.dart' as path;
import 'package:sqflite/sqflite.dart';
import 'text_pipeline.dart';

abstract interface class TranslationCache {
  Future<String?> find(String source);
  Future<void> save(String source, String translation);
  Future<void> close();
}

class SqliteTranslationCache implements TranslationCache {
  SqliteTranslationCache({this.databasePath, this.namespace = modelId});
  final String? databasePath;
  final String namespace;
  Database? _database;
  Future<Database>? _opening;
  Future<Database> _open() => _opening ??= _create();
  Future<Database> _create() async {
    final db = await openDatabase(
      databasePath ?? path.join(await getDatabasesPath(), 'translations.db'),
      version: 1,
      onCreate: (db, _) => db.execute('''
        CREATE TABLE translations (cache_key TEXT PRIMARY KEY, source TEXT NOT NULL,
        translated TEXT NOT NULL, created_at INTEGER NOT NULL)
      '''),
    );
    _database = db;
    return db;
  }

  @override
  Future<String?> find(String source) async {
    final rows = await (await _open()).query(
      'translations',
      columns: ['source', 'translated'],
      where: 'cache_key = ?',
      whereArgs: [translationKey(source, model: namespace)],
      limit: 1,
    );
    if (rows.isEmpty || rows.single['source'] != normalizeText(source)) {
      return null;
    }
    return rows.single['translated'] as String;
  }

  @override
  Future<void> save(String source, String translation) async {
    await (await _open()).insert('translations', {
      'cache_key': translationKey(source, model: namespace),
      'source': normalizeText(source),
      'translated': translation,
      'created_at': DateTime.now().millisecondsSinceEpoch,
    }, conflictAlgorithm: ConflictAlgorithm.replace);
  }

  @override
  Future<void> close() async {
    await _database?.close();
  }
}
