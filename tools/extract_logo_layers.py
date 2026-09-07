from pathlib import Path
from PIL import Image, ImageChops


SOURCE = Path(r"C:\Users\wangk\Downloads\ChatGPT Image 2026年8月20日 16_13_27.png")
OUTPUT_DIR = Path(__file__).resolve().parents[1] / "assets" / "logo"


def make_alpha(gray: Image.Image) -> Image.Image:
    # The source is a black mark on a slightly noisy near-white backdrop.
    # Collapse near-white pixels to transparent and preserve useful antialiasing.
    return gray.point(lambda value: 0 if value >= 246 else 255 if value <= 24 else round((246 - value) * 255 / 222))


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE).convert("RGB")
    gray = source.convert("L")
    alpha = make_alpha(gray)

    black = Image.new("RGB", source.size, "black")
    foreground = Image.merge("RGBA", (*black.split(), alpha))

    full_path = OUTPUT_DIR / "logo-foreground-transparent.png"
    foreground.save(full_path, optimize=True, compress_level=9)

    bbox = alpha.getbbox()
    if bbox is None:
        raise RuntimeError("No foreground was detected")

    padding = 24
    left = max(0, bbox[0] - padding)
    top = max(0, bbox[1] - padding)
    right = min(source.width, bbox[2] + padding)
    bottom = min(source.height, bbox[3] + padding)
    cropped = foreground.crop((left, top, right, bottom))
    cropped_path = OUTPUT_DIR / "logo-foreground-transparent-cropped.png"
    cropped.save(cropped_path, optimize=True, compress_level=9)

    white = Image.new("RGB", (1, 1), "white")
    background_path = OUTPUT_DIR / "logo-background-white.png"
    white.save(background_path, optimize=True, compress_level=9)

    preview = Image.new("RGB", cropped.size, "white")
    preview.paste(cropped, mask=cropped.getchannel("A"))
    preview_path = OUTPUT_DIR / "logo-preview-on-white.png"
    preview.save(preview_path, optimize=True, compress_level=9)

    print(f"source={source.size[0]}x{source.size[1]} {SOURCE.stat().st_size} bytes")
    for path in (full_path, cropped_path, background_path, preview_path):
        with Image.open(path) as image:
            print(f"{path.name}={image.size[0]}x{image.size[1]} {path.stat().st_size} bytes {image.mode}")


if __name__ == "__main__":
    main()
