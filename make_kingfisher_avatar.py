from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter


BIRD_PATH = Path("/Users/wangpengjing/sbyProject/logs_tool/output/kingfisher_cutout_trimmed.png")
OUTPUT_DIR = Path("/Users/wangpengjing/sbyProject/logs_tool/output")
CANVAS_OUT = OUTPUT_DIR / "assistant_kingfisher_avatar.png"
BADGE_OUT = OUTPUT_DIR / "assistant_kingfisher_badge.png"
TRANSPARENT_OUT = OUTPUT_DIR / "assistant_kingfisher_transparent.png"


def add_shadow(base, bbox, radius=12, offset=(0, 7), opacity=60):
    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    shifted = (
        bbox[0] + offset[0],
        bbox[1] + offset[1],
        bbox[2] + offset[0],
        bbox[3] + offset[1],
    )
    draw.ellipse(shifted, fill=(0, 0, 0, opacity))
    shadow = Image.alpha_composite(shadow, layer.filter(ImageFilter.GaussianBlur(radius)))
    return Image.alpha_composite(base, shadow)


def _prepare_bird(target_size):
    bird = Image.open(BIRD_PATH).convert("RGBA")
    bird = bird.resize(target_size, Image.Resampling.LANCZOS).rotate(
        -2, resample=Image.Resampling.BICUBIC, expand=True
    )
    return bird


def build_badge(size):
    badge = Image.new("RGBA", size, (0, 0, 0, 0))
    circle_box = (8, 8, size[0] - 8, size[1] - 8)

    badge = add_shadow(badge, circle_box, radius=14, offset=(0, 8), opacity=62)

    draw = ImageDraw.Draw(badge)
    draw.ellipse(circle_box, fill=(246, 249, 252, 208), outline=(171, 184, 197, 150), width=2)

    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((18, 18, size[0] - 18, size[1] - 18), fill=(255, 255, 255, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(10))
    badge = Image.alpha_composite(badge, glow)

    bird = _prepare_bird((190, 150))

    bird_shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    shadow_asset = bird.copy()
    alpha = shadow_asset.getchannel("A").point(lambda p: int(p * 0.22))
    shadow_asset = Image.new("RGBA", shadow_asset.size, (28, 46, 58, 0))
    shadow_asset.putalpha(alpha)
    bird_shadow.alpha_composite(shadow_asset, (16, 50))
    bird_shadow = bird_shadow.filter(ImageFilter.GaussianBlur(6))

    badge = Image.alpha_composite(badge, bird_shadow)
    badge.alpha_composite(bird, (10, 36))

    clip_mask = Image.new("L", size, 0)
    clip_draw = ImageDraw.Draw(clip_mask)
    clip_draw.ellipse((9, 9, size[0] - 9, size[1] - 9), fill=255)
    clipped = Image.new("RGBA", size, (0, 0, 0, 0))
    clipped.alpha_composite(badge)
    clipped.putalpha(ImageChops.multiply(clipped.getchannel("A"), clip_mask))
    return clipped


def build_transparent_icon(size):
    return build_badge(size)


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    badge = build_badge((220, 220))
    transparent_icon = build_transparent_icon((220, 220))

    canvas = Image.new("RGBA", (320, 320), (245, 245, 245, 255))
    canvas.alpha_composite(badge, ((canvas.width - badge.width) // 2, (canvas.height - badge.height) // 2))

    canvas.save(CANVAS_OUT)
    badge.save(BADGE_OUT)
    transparent_icon.save(TRANSPARENT_OUT)

    print(CANVAS_OUT)
    print(BADGE_OUT)
    print(TRANSPARENT_OUT)


if __name__ == "__main__":
    main()
