import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../core/translation_coordinator.dart';

class OverlayView extends StatelessWidget {
  const OverlayView({
    super.key,
    required this.coordinator,
    this.googleAttribution = false,
    this.onlineAttribution = false,
  });
  final TranslationCoordinator coordinator;
  final bool googleAttribution;
  final bool onlineAttribution;
  @override
  Widget build(BuildContext context) => Directionality(
    textDirection: TextDirection.ltr,
    child: ListenableBuilder(
      listenable: coordinator,
      builder: (context, _) => Stack(
        fit: StackFit.expand,
        children: [
          CustomPaint(
            painter: BalloonPainter(coordinator.snapshot),
            size: Size.infinite,
          ),
          if (googleAttribution &&
              coordinator.snapshot?.balloons.isNotEmpty == true)
            Positioned(
              right: 8,
              bottom:
                  View.of(context).padding.bottom /
                      View.of(context).devicePixelRatio +
                  6,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(6),
                  child: Image.asset(
                    'assets/attribution/google-translate.png',
                    height: 14,
                  ),
                ),
              ),
            ),
          if (onlineAttribution &&
              coordinator.snapshot?.balloons.isNotEmpty == true)
            const Positioned(
              right: 8,
              bottom: 24,
              child: DecoratedBox(
                decoration: BoxDecoration(color: Colors.white),
                child: Padding(
                  padding: EdgeInsets.all(6),
                  child: Text(
                    'Gemini · online',
                    style: TextStyle(fontSize: 11, color: Colors.black),
                  ),
                ),
              ),
            ),
        ],
      ),
    ),
  );
}

class BalloonPainter extends CustomPainter {
  BalloonPainter(this.snapshot);
  final OverlaySnapshot? snapshot;
  @override
  void paint(Canvas canvas, Size size) {
    final data = snapshot;
    if (data == null) return;
    final screen = Offset.zero & size;
    final originals = data.balloons
        .map(
          (b) => data.frame
              .toOverlay(b.paragraph.bounds, size)
              .inflate(3)
              .intersect(screen),
        )
        .toList();
    final placed = <Rect>[];
    for (var index = 0; index < data.balloons.length; index++) {
      final balloon = data.balloons[index];
      var box = originals[index];
      if (box.isEmpty) continue;
      TextPainter measure(double font, Rect bounds) => TextPainter(
        text: TextSpan(
          text: balloon.translation,
          style: TextStyle(
            fontSize: font,
            height: 1.1,
            color: const Color(0xff101820),
            fontWeight: FontWeight.w600,
          ),
        ),
        textDirection: TextDirection.ltr,
        textAlign: TextAlign.center,
      )..layout(maxWidth: math.max(1, bounds.width - 8));
      var fontSize = 18.0;
      var text = measure(fontSize, box);
      // Expand only into free space; reserve all other OCR regions first.
      for (var step = 0; text.height > box.height - 8 && step < 6; step++) {
        final expanded = box.inflate(4).intersect(screen.deflate(2));
        final collides =
            placed.any((r) => r.overlaps(expanded)) ||
            originals.asMap().entries.any(
              (e) => e.key != index && e.value.overlaps(expanded),
            );
        if (collides) break;
        box = expanded;
        text = measure(fontSize, box);
      }
      while (text.height > box.height - 6 && fontSize > 7) {
        fontSize -= .5;
        text = measure(fontSize, box);
      }
      placed.add(box);
      canvas.save();
      canvas.clipRect(box);
      canvas.drawRRect(
        RRect.fromRectAndRadius(box, const Radius.circular(7)),
        Paint()..color = Colors.white,
      );
      final scale = math.min(1.0, math.max(1, box.height - 4) / text.height);
      canvas.translate(box.center.dx, box.center.dy);
      canvas.scale(scale);
      text.paint(canvas, Offset(-text.width / 2, -text.height / 2));
      text.dispose();
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(BalloonPainter oldDelegate) =>
      oldDelegate.snapshot != snapshot;
}
