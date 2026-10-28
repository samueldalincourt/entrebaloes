"""Download/resume the app's pinned GGUF for desktop validation; verify SHA-256."""
from pathlib import Path
import argparse
import hashlib
import json
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--manifest", type=Path, default=ROOT / "android/app/src/main/assets/model-manifest.json")
args = parser.parse_args()
manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
spec = manifest["files"][0]
destination = ROOT / ".tools/test-models" / spec["path"]
destination.parent.mkdir(parents=True, exist_ok=True)


def valid(path: Path) -> bool:
    if not path.exists() or path.stat().st_size != spec["size"]:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest() == spec["hash"]


if not valid(destination):
    partial = destination.with_suffix(".gguf.part")
    offset = partial.stat().st_size if partial.exists() else 0
    if offset >= spec["size"]:
        if valid(partial):
            partial.replace(destination)
            print(destination)
            raise SystemExit(0)
        partial.unlink()
        offset = 0
    url = f"https://huggingface.co/{manifest['repository']}/resolve/{manifest['revision']}/{spec['path']}?download=true"
    request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-", "Accept-Encoding": "identity"})
    with urllib.request.urlopen(request, timeout=60) as response:
        if response.status != 206:
            offset = 0
        elif not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
            raise SystemExit("Invalid resume response")
        last = 0.0
        with partial.open("ab" if offset else "wb") as stream:
            while chunk := response.read(1024 * 1024):
                stream.write(chunk)
                offset += len(chunk)
                if offset > spec["size"]:
                    raise SystemExit("Unexpected model size")
                if time.monotonic() - last > 5:
                    print(f"Model download: {offset/spec['size']:.0%}", flush=True)
                    last = time.monotonic()
    if not valid(partial):
        raise SystemExit("Model checksum mismatch; file was not installed")
    partial.replace(destination)
print(f"Verified GGUF SHA-256: {spec['hash']}\n{destination}", flush=True)
