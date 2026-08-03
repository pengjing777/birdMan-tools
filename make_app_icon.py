from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter


ROOT = Path("/Users/wangpengjing/sbyProject/logs_tool")
SOURCE_BIRD = ROOT / "output" / "kingfisher_cutout_trimmed.png"
ASSETS_DIR = ROOT / "assets"
MASTER_ICON = ASSETS_DIR / "toolbox_kingfisher_icon_1024.png"
ICONSET_DIR = ASSETS_DIR / "ToolBox.iconset"
ICNS_PATH = ASSETS_DIR / "ToolBox.icns"

ICON_SIZES = {
    "icon_16x16.png": 16,
    "icon_16x16@2x.png": 32,
    "icon_32x32.png": 32,
    "icon_32x32@2x.png": 64,
    "icon_128x128.png": 128,
    "icon_128x128@2x.png": 256,
    "icon_256x256.png": 256,
    "icon_256x256@2x.png": 512,
    "icon_512x512.png": 512,
    "icon_512x512@2x.png": 1024,
}


def rounded_rect_mask(size, radius, inset=0):
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(
        (inset, inset, size[0] - inset - 1, size[1] - inset - 1),
        radius=radius,
        fill=255,
    )
    return mask


def vertical_gradient(size, top_rgb, bottom_rgb):
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    height = max(size[1] - 1, 1)
    for y in range(size[1]):
        t = y / height
        color = tuple(int(top_rgb[i] * (1 - t) + bottom_rgb[i] * t) for i in range(3)) + (255,)
        draw.line((0, y, size[0], y), fill=color)
    return image


def build_master_icon():
    size = (1024, 1024)
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))

    # Base rounded-square card shadow.
    shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.rounded_rectangle((112, 132, 912, 932), radius=190, fill=(15, 32, 48, 70))
    shadow = shadow.filter(ImageFilter.GaussianBlur(34))
    canvas.alpha_composite(shadow)

    card = vertical_gradient(size, (250, 253, 255), (218, 237, 234))
    card_mask = rounded_rect_mask(size, 188, inset=92)
    card.putalpha(card_mask)
    canvas.alpha_composite(card)

    # Subtle highlight and aqua accent to make it feel less flat.
    highlight = Image.new("RGBA", size, (0, 0, 0, 0))
    highlight_draw = ImageDraw.Draw(highlight)
    highlight_draw.ellipse((110, 70, 670, 520), fill=(255, 255, 255, 110))
    highlight = highlight.filter(ImageFilter.GaussianBlur(36))
    highlight.putalpha(ImageChops.multiply(highlight.getchannel("A"), card_mask))
    canvas.alpha_composite(highlight)

    accent = Image.new("RGBA", size, (0, 0, 0, 0))
    accent_draw = ImageDraw.Draw(accent)
    accent_draw.ellipse((470, 560, 980, 1030), fill=(113, 196, 194, 92))
    accent = accent.filter(ImageFilter.GaussianBlur(56))
    accent.putalpha(ImageChops.multiply(accent.getchannel("A"), card_mask))
    canvas.alpha_composite(accent)

    outline = Image.new("RGBA", size, (0, 0, 0, 0))
    outline_draw = ImageDraw.Draw(outline)
    outline_draw.rounded_rectangle((92, 92, 931, 931), radius=188, outline=(148, 170, 182, 156), width=4)
    canvas.alpha_composite(outline)

    bird = Image.open(SOURCE_BIRD).convert("RGBA")
    bird = bird.resize((760, 610), Image.Resampling.LANCZOS).rotate(-2, resample=Image.Resampling.BICUBIC, expand=True)

    bird_shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    alpha = bird.getchannel("A").point(lambda p: int(p * 0.23))
    shadow_asset = Image.new("RGBA", bird.size, (18, 42, 55, 0))
    shadow_asset.putalpha(alpha)
    bird_shadow.alpha_composite(shadow_asset, (120, 240))
    bird_shadow = bird_shadow.filter(ImageFilter.GaussianBlur(18))
    canvas.alpha_composite(bird_shadow)

    canvas.alpha_composite(bird, (92, 200))

    return canvas


def export_iconset(master):
    ICONSET_DIR.mkdir(parents=True, exist_ok=True)
    for filename, px in ICON_SIZES.items():
        icon = master.resize((px, px), Image.Resampling.LANCZOS)
        icon.save(ICONSET_DIR / filename)


if __name__ == "__main__":
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    master = build_master_icon()
    master.save(MASTER_ICON)
    export_iconset(master)
    print(MASTER_ICON)
    print(ICONSET_DIR)
