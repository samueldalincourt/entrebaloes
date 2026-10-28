import 'dart:ui';
import 'package:flutter_test/flutter_test.dart';
import 'package:tradutor_webtoon/core/text_pipeline.dart';

void main() {
  test('reuses a phrase across whitespace and line-break OCR variations', () {
    expect(
      translationKey("I'm going to be\nthe king of the pirates!"),
      translationKey("  I'm  going to be the king of the pirates!  "),
    );
    expect(
      translationKey("Let's eat, Jack!"),
      isNot(translationKey("Let's eat Jack!")),
    );
    expect(translationKey('US'), isNot(translationKey('us')));
  });
  test('keeps short English dialogue and removes OCR symbols', () {
    expect(isUsefulText('I!'), isTrue);
    expect(isUsefulText('NO'), isTrue);
    expect(isUsefulText('OK!'), isTrue);
    expect(isUsefulText('x'), isFalse);
    expect(isUsefulText('||| 12'), isFalse);
    expect(isUsefulText('A webtoons.com/en/action'), isFalse);
    expect(isUsefulText('https://www.webtoons.com'), isFalse);
  });
  test('merges aligned nearby lines without joining neighboring balloons', () {
    final paragraphs = groupLines(const [
      OcrLine("I'm going to be", Rect.fromLTWH(10, 10, 100, 18), 0),
      OcrLine('the king of the pirates!', Rect.fromLTWH(5, 32, 120, 18), 0),
      OcrLine('Really?', Rect.fromLTWH(160, 33, 80, 18), 1),
      OcrLine('Another balloon', Rect.fromLTWH(10, 160, 100, 18), 0),
    ]);
    expect(paragraphs, hasLength(3));
    expect(paragraphs.first.text, "I'm going to be the king of the pirates!");
    expect(paragraphs.first.bounds, const Rect.fromLTRB(5, 10, 125, 50));
  });
  test(
    'restores crop offsets and maps physical pixels to Flutter coordinates',
    () {
      const frame = CaptureFrame(
        path: '',
        width: 1080,
        height: 2400,
        cropTop: 240,
        cropHeight: 1920,
        generation: 4,
      );
      expect(
        frame.toOverlay(
          const Rect.fromLTWH(108, 120, 216, 60),
          const Size(360, 800),
        ),
        const Rect.fromLTRB(36, 120, 108, 140),
      );
    },
  );
  test(
    'joins centered dialogue split across ML Kit blocks, preserving columns',
    () {
      final paragraphs = groupLines(const [
        OcrLine('The objective', Rect.fromLTWH(50, 100, 120, 24), 0),
        OcrLine('Watch out!', Rect.fromLTWH(260, 100, 100, 24), 2),
        OcrLine("of this mission isn't", Rect.fromLTWH(20, 130, 180, 24), 1),
        OcrLine('Behind you!', Rect.fromLTWH(250, 130, 120, 24), 3),
        OcrLine('to exterminate Nest.', Rect.fromLTWH(20, 160, 180, 24), 4),
      ]);
      expect(paragraphs, hasLength(2));
      expect(
        paragraphs.first.text,
        "The objective of this mission isn't to exterminate Nest.",
      );
      expect(paragraphs.last.text, 'Watch out! Behind you!');
      expect(paragraphs.first.bounds, const Rect.fromLTRB(20, 100, 200, 184));
    },
  );
  test(
    'does not join separate vertically stacked balloons or sound effects',
    () {
      final paragraphs = groupLines(const [
        OcrLine('Stop!', Rect.fromLTWH(30, 100, 100, 24), 0),
        OcrLine('Run!', Rect.fromLTWH(30, 160, 100, 24), 1),
        OcrLine('BOOM', Rect.fromLTWH(30, 192, 100, 50), 2),
      ]);
      expect(paragraphs, hasLength(3));
    },
  );
  final batch = [
    const Paragraph('b0', 'Hello', Rect.zero),
    const Paragraph('b1', 'Bye', Rect.zero),
  ];
  test('matches JSON results by ID instead of output order', () {
    expect(parseTranslations('{"b1":"Tchau","b0":"Olá"}', batch), {
      'b0': 'Olá',
      'b1': 'Tchau',
    });
    expect(
      parseTranslations('```json\n{"b0":"Olá","b1":"Tchau"}\n```', batch)['b0'],
      'Olá',
    );
    expect(
      () => parseTranslations('{"b0":"Olá"}', batch),
      throwsFormatException,
    );
    expect(
      () => parseTranslations('{"b0":"Olá","b2":"Tchau"}', batch),
      throwsFormatException,
    );
    expect(
      () => parseTranslations('{"b0":"","b1":"Tchau"}', batch),
      throwsFormatException,
    );
  });
  test('limits generation batches to four and bounds context input', () {
    final items = List.generate(9, (i) => Paragraph('b$i', 'Hello', Rect.zero));
    expect(makeBatches(items).map((b) => b.length), [4, 4, 1]);
    expect(
      makeBatches(items, maxItems: 1).map((b) => b.length),
      List.filled(9, 1),
    );
    expect(
      makeBatches([
        Paragraph('b0', 'a' * 700, Rect.zero),
        Paragraph('b1', 'b' * 700, Rect.zero),
      ]),
      hasLength(2),
    );
    expect(
      () => makeBatches([Paragraph('b0', 'x' * 1201, Rect.zero)]),
      throwsFormatException,
    );
  });
}
