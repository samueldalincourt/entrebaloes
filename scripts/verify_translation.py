"""Run a real CPU/GGUF translation using an official llama.cpp desktop binary.

This validates the model, prompt and output grammar; it does not measure POCO performance.
"""
from pathlib import Path
import ast
import argparse
import hashlib
import json
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
work = ROOT / ".tools/translation-smoke"
work.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--manifest", type=Path, default=ROOT / "android/app/src/main/assets/model-manifest.json")
parser.add_argument("--report", type=Path, default=ROOT / "dist/translation-smoke.json")
args = parser.parse_args()
manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
model = ROOT / ".tools/test-models" / manifest["filename"]
assert model.stat().st_size == manifest["files"][0]["size"]
with model.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
assert digest == manifest["files"][0]["hash"], "Unverified model"
if manifest["repository"].startswith("tencent/Hy-MT"):
    native = (ROOT / "android/app/src/main/cpp/local_engine.cpp").read_text(encoding="utf-8")
    spec = native.split("const std::string translation_input =", 1)[1].split("+ input;", 1)[0]
    instruction = "".join(ast.literal_eval(p) for p in re.findall(r'"(?:\\.|[^"\\])*"', spec))
    sources = {"b0": "I'm going to be the king of the pirates!", "b1": "I never said I trusted him.",
               "b2": "Keep an eye on the entrance.", "b3": "Luffy, don't let your guard down."}
    outputs = {}
    elapsed = {}
    for key, text in sources.items():
        prompt = f"<｜hy_begin▁of▁sentence｜><｜hy_User｜>{instruction}{text}<｜hy_Assistant｜>"
        prompt_file = work / f"prompt-{key}.txt"
        prompt_file.write_text(prompt, encoding="utf-8")
        command = [str(ROOT / ".tools/llama-desktop/llama-completion.exe"), "-m", str(model),
                   "-f", str(prompt_file), "-c", "2048", "-b", "128", "-ub", "128", "-t", "2",
                   "-ngl", "0", "-n", "256", "--temp", "0", "--no-conversation", "--no-display-prompt",
                   "--simple-io", "--no-warmup"]
        before = time.monotonic()
        run = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180,
                             encoding="utf-8", errors="replace")
        (work / f"stderr-{key}.txt").write_text(run.stderr, encoding="utf-8")
        assert run.returncode == 0, run.stderr[-1500:]
        outputs[key] = run.stdout.strip().removesuffix("[end of text]").rstrip()
        elapsed[key] = round(time.monotonic() - before, 3)
    assert "rei" in outputs["b0"].lower() and "piratas" in outputs["b0"].lower(), outputs
    assert "nunca" in outputs["b1"].lower() and "disse" in outputs["b1"].lower(), outputs
    assert "olho" in outputs["b2"].lower() and "entrada" in outputs["b2"].lower(), outputs
    assert "Luffy" in outputs["b3"] and "guarda" in outputs["b3"].lower(), outputs
    report = {"model": manifest["modelId"], "modelSha256": digest, "engine": "llama.cpp desktop CPU",
              "scope": "Desktop only; each invocation includes loading. See docs/LOCAL_PERFORMANCE.md for Android.",
              "source": sources, "translations": outputs, "elapsedSecondsIncludingLoad": elapsed, "passed": True}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=True))
    raise SystemExit(0)
source = (ROOT / "lib/core/text_pipeline.dart").read_text(encoding="utf-8-sig")
prompt_source = source.split("const translationPrompt =", 1)[1].split(";", 1)[0]
system = "".join(ast.literal_eval(p) for p in re.findall(r"'(?:\\.|[^'\\])*'", prompt_source))
inputs = {
    "b0": "I'm going to be the king of the pirates!",
    "b1": "Don't touch my friends!",
    "b2": "No!",
    "b3": "Luffy, we must leave now.",
}
prompt = f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{json.dumps(inputs)}<|im_end|>\n<|im_start|>assistant\n"
(work / "prompt.txt").write_text(prompt, encoding="utf-8")
native = (ROOT / "android/app/src/main/cpp/local_engine.cpp").read_text(encoding="utf-8")
grammar = native.split('R"GBNF(', 1)[1].split(')GBNF"', 1)[0]
(work / "output.gbnf").write_text(grammar, encoding="utf-8")
command = [str(ROOT / ".tools/llama-desktop/llama-completion.exe"), "-m", str(model),
    "-f", str(work / "prompt.txt"), "-c", "2048", "-b", "128", "-ub", "128", "-t", "3",
    "-ngl", "0", "-n", "640", "--temp", "0", "--no-conversation", "--no-display-prompt",
    "--simple-io", "--no-warmup", "--grammar-file", str(work / "output.gbnf")]
start = time.monotonic()
run = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180,
    encoding="utf-8", errors="replace")
(work / "stdout.txt").write_text(run.stdout, encoding="utf-8")
(work / "stderr.txt").write_text(run.stderr, encoding="utf-8")
if run.returncode:
    raise SystemExit(f"llama.cpp failed ({run.returncode}): {run.stderr[-2000:]}")
translated, _ = json.JSONDecoder().raw_decode(run.stdout.lstrip())
assert set(translated) == set(inputs), translated
assert all(isinstance(value, str) and value.strip() for value in translated.values())
assert "rei" in translated["b0"].lower() and "piratas" in translated["b0"].lower(), translated
assert "amigos" in translated["b1"].lower(), translated
assert "não" in translated["b2"].lower(), translated
assert "Luffy" in translated["b3"], translated
report = {"model": manifest["modelId"], "engine": "llama.cpp desktop CPU, official b11669",
    "scope": "real inference on desktop; Android/POCO timing remains unverified",
    "modelSha256": digest, "elapsedSecondsIncludingLoad": round(time.monotonic() - start, 3),
    "source": inputs, "translations": translated, "passed": True}
(ROOT / "dist").mkdir(exist_ok=True)
args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps(report, indent=2, ensure_ascii=False))
