"""Export launcher sizes from the original project-owned branding artwork."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/branding/app-icon.png"
RES = ROOT / "android/app/src/main/res"

with Image.open(SOURCE) as source:
    assert source.width == source.height, "Launcher artwork must be square"
    image = source.convert("RGB")
    for density, pixels in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        destination = RES / f"mipmap-{density}/ic_launcher.png"
        destination.parent.mkdir(parents=True, exist_ok=True)
        image.resize((pixels, pixels), Image.Resampling.LANCZOS).save(destination)
    destination = RES / "drawable-nodpi/app_icon.png"
    destination.parent.mkdir(parents=True, exist_ok=True)
    image.resize((1024, 1024), Image.Resampling.LANCZOS).save(destination)
    image.resize((512, 512), Image.Resampling.LANCZOS).save(ROOT / "assets/branding/app-icon-512.png")

print("Exported five launcher densities, adaptive artwork and 512px artwork.")
