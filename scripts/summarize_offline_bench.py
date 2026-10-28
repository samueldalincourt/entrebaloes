"""Archive synthetic physical-device measurements without claiming OCR timing."""
from pathlib import Path
import json
import statistics

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "baseline": "offline-cpu-baseline-t3.jsonl",
    "qwen_cached": "offline-cpu-cached-t2.jsonl",
    "qwen_plain": "offline-cpu-plain-t2.jsonl",
    "qwen3_plain": "offline-qwen3-plain-t2.jsonl",
    "selected": "offline-hymt2-style-final.jsonl",
    "held_out": "offline-hymt2-style-heldout.jsonl",
}

def summary(rows):
    cases = [row for row in rows if row.get("type") == "case" and row.get("label") != "two-balloons"]
    times = [row["elapsedMs"] for row in cases]
    return {"n": len(times), "medianMs": statistics.median(times), "minMs": min(times),
            "maxMs": max(times), "under3000ms": sum(ms <= 3000 for ms in times)}

data = {key: [json.loads(line) for line in (ROOT / "dist" / file).read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        for key, file in FILES.items()}
summaries = {key: summary(rows) for key, rows in data.items()}
report = {
    "date": "2026-10-28", "device": "POCO M7 Pro 2409FPCC4G / MT6855 / PowerVR BXM-8-256",
    "scope": "Real offline inference on device using the app C++ engine; synthetic dialogue. Excludes screen capture, OCR, overlay and 550ms scroll debounce. Model loading and priming are reported separately.",
    "selectedModel": "tencent/Hy-MT2-1.8B-GGUF / Q4_K_M",
    "selectedRevision": "a0c709d9fac510f2c807aa3af52872340dc37a4a",
    "selectedSha256": "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
    "summaries": summaries,
    "medianSpeedup": summaries["baseline"]["medianMs"] / summaries["selected"]["medianMs"],
    "qualityNotes": [
        "Critical idioms improved: keep an eye -> Fique de olho; guard down -> nao baixe a guarda. Negation preserved in reviewed cases.",
        "The unprotected ambiguous name Nest was translated as a noun with the colloquial prompt. Production protects glossary names by reversible markers; the protected-name case preserved the marker.",
        "Small synthetic corpus, manual semantic review, not an automated benchmark of broad translation quality. Some phrases remain less natural or lose nuance; no parity with Gemini is claimed.",
    ], "measurements": data,
}
destination = ROOT / "docs/benchmarks/offline-2026-10-28.json"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"summaries": summaries, "medianSpeedup": report["medianSpeedup"]}, indent=2))
