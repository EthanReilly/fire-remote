"""Draws the launcher icon (a dark remote with an orange ring D-pad) at each density."""
import os, sys
from PIL import Image, ImageDraw

res = sys.argv[1]
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dens, px in sizes.items():
    S = px * 4  # supersample
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    m = S * 0.04
    d.rounded_rectangle([m, m, S - m, S - m], radius=S * 0.23, fill=(26, 31, 39, 255))
    cx, cy = S / 2, S * 0.47
    R = S * 0.27
    d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=(255, 159, 46, 255), width=int(S * 0.075))
    r = S * 0.09
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(233, 237, 242, 255))
    # small buttons below the pad
    by, br = S * 0.85, S * 0.035
    for bx in (S * 0.38, S * 0.5, S * 0.62):
        d.ellipse([bx - br, by - br, bx + br, by + br], fill=(140, 150, 165, 255))
    out = os.path.join(res, "drawable-" + dens)
    os.makedirs(out, exist_ok=True)
    im.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
print("icons written")
