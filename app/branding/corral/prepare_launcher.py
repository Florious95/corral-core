"""Package the generated silhouette into Android's 108dp adaptive safe area.

Requires Pillow. Run from any directory; preserves both original generated PNGs.
"""
from collections import deque
from pathlib import Path
from PIL import Image, ImageFilter

HERE = Path(__file__).resolve().parent
source = Image.open(HERE / "generated-refined.png").convert("RGBA")
# Imagegen left low-alpha speckles. Keep only the two substantial generated
# components (enclosure and chevron), without tracing or redrawing their shape.
mask = source.getchannel("A").point(lambda a: 255 if a >= 128 else 0)
mask = mask.filter(ImageFilter.MedianFilter(3))
width, height = mask.size
remaining = {y * width + x for y in range(height) for x in range(width)
             if mask.getpixel((x, y))}
components = []
while remaining:
    first = remaining.pop()
    component, queue = [first], deque([first])
    while queue:
        pixel = queue.popleft()
        x, y = pixel % width, pixel // width
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            neighbor = ny * width + nx
            if 0 <= nx < width and 0 <= ny < height and neighbor in remaining:
                remaining.remove(neighbor)
                component.append(neighbor)
                queue.append(neighbor)
    components.append(component)
components.sort(key=len, reverse=True)
assert len(components) >= 2 and len(components[1]) > 10000
assert all(len(c) < 1000 for c in components[2:])
clean = Image.new("L", source.size)
for component in components[:2]:
    for pixel in component:
        clean.putpixel((pixel % width, pixel // width), 255)
clean = clean.crop(clean.getbbox())
cx, cy = clean.width / 2, clean.height / 2
radius = max(((x - cx) ** 2 + (y - cy) ** 2) ** .5
             for y in range(clean.height) for x in range(clean.width)
             if clean.getpixel((x, y)))
# 432px = 108dp at xxxhdpi; 29% radius leaves margin inside 66dp safe circle.
scale = 432 * .29 / radius
clean = clean.resize((round(clean.width * scale), round(clean.height * scale)),
                     Image.Resampling.LANCZOS)
alpha = Image.new("L", (432, 432))
alpha.paste(clean, ((432 - clean.width) // 2, (432 - clean.height) // 2))
icon = Image.new("RGBA", alpha.size, (38, 246, 146, 255))
icon.putalpha(alpha)
destination = HERE.parents[1] / "app/src/main/res/drawable-nodpi/corral_launcher_foreground.png"
icon.save(destination, optimize=True)
print(f"Saved {destination}; retained components {[len(c) for c in components[:2]]}")
