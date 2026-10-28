import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'text_pipeline.dart';

abstract interface class OcrReader {
  Future<List<OcrLine>> read(String imagePath);
  Future<void> close();
}

class MlkitOcrReader implements OcrReader {
  final _recognizer = TextRecognizer(script: TextRecognitionScript.latin);
  @override
  Future<List<OcrLine>> read(String imagePath) async {
    final text = await _recognizer.processImage(
      InputImage.fromFilePath(imagePath),
    );
    return [
      for (var i = 0; i < text.blocks.length; i++)
        for (final line in text.blocks[i].lines)
          OcrLine(line.text, line.boundingBox, i),
    ];
  }

  @override
  Future<void> close() => _recognizer.close();
}
