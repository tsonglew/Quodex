#!/usr/bin/env python3
"""Generate Quodex SVGs and Android vectors from the same rounded-Q geometry.

Run from any directory with Python 3; no image or font dependencies are needed.
All visible shapes are filled paths, including the existing outlined wordmark.
"""

from math import cos, radians, sin, sqrt
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "docs/assets/brand"
DRAWABLE = ROOT / "app/src/main/res/drawable"
INK, TEAL, MINT = "#112B30", "#007C83", "#5CD8CD"

# Preserve the established uodex letterforms, spacing, and baseline.
LETTERS = [
    "M254 84H278Q280 84 280 86V170C280 190 289 200 307 200C326 200 338 186 338 165V86Q338 84 340 84H364Q366 84 366 86V222Q366 224 364 224H342Q340 224 340 222V208C331 220 318 226 301 226C269 226 252 207 252 174V86Q252 84 254 84Z",
    "M453 80C492 80 516 108 516 153C516 198 492 226 453 226C414 226 390 198 390 153C390 108 414 80 453 80ZM453 106C431 106 418 124 418 153C418 182 431 200 453 200C475 200 488 182 488 153C488 124 475 106 453 106Z",
    "M638 32H662Q664 32 664 34V222Q664 224 662 224H640Q638 224 638 222V205C628 219 614 226 595 226C558 226 534 198 534 154C534 110 558 80 595 80C613 80 626 90 636 105V34Q636 32 638 32ZM599 106C576 106 562 125 562 154C562 183 576 200 599 200C622 200 638 181 638 154C638 126 622 106 599 106Z",
    "M814 158H714C716 185 730 201 754 201C772 201 788 194 799 183L816 201C801 218 781 226 753 226C712 226 686 197 686 154C686 110 712 80 750 80C789 80 814 107 814 148ZM715 136H786C783 115 770 104 751 104C732 104 719 116 715 136Z",
    "M841.142 85.642Q840.000 84.000 842.000 84.000L871.000 84.000Q873.000 84.000 874.109 85.664L905.000 132.000L935.891 85.664Q937.000 84.000 939.000 84.000L968.000 84.000Q970.000 84.000 968.858 85.642L922.000 153.000L969.864 222.354Q971.000 224.000 969.000 224.000L940.000 224.000Q938.000 224.000 936.898 222.331L905.000 174.000L873.102 222.331Q872.000 224.000 870.000 224.000L840.000 224.000Q838.000 224.000 839.152 222.365L888.000 153.000Z",
]


def point(radius, angle):
    return f"{128 + radius * cos(radians(angle)):.3f},{128 + radius * sin(radians(angle)):.3f}"


def mark(small=False):
    radius, weight, angle = (79, 34, 6) if small else (80, 32, 10)
    outer, inner, cap = radius + weight / 2, radius - weight / 2, weight / 2
    # Expand a circular arc and its round caps into one closed fill path.
    ring = (
        f"M {point(outer, angle)} A {outer:g},{outer:g} 0 1 0 {point(outer, 90-angle)} "
        f"A {cap:g},{cap:g} 0 0 0 {point(inner, 90-angle)} "
        f"A {inner:g},{inner:g} 0 1 1 {point(inner, angle)} "
        f"A {cap:g},{cap:g} 0 0 0 {point(outer, angle)} Z"
    )
    start, end = (168, 205) if small else (166, 206)
    offset = cap / sqrt(2)
    tail = (
        f"M {start-offset:.3f},{start+offset:.3f} L {end-offset:.3f},{end+offset:.3f} "
        f"A {cap:g},{cap:g} 0 0 0 {end+offset:.3f},{end-offset:.3f} "
        f"L {start+offset:.3f},{start-offset:.3f} "
        f"A {cap:g},{cap:g} 0 0 0 {start-offset:.3f},{start+offset:.3f} Z"
    )
    return [ring, tail]


def paths(geometry, color, evenodd=False):
    rule = ' fill-rule="evenodd"' if evenodd else ""
    return "\n".join(f'  <path fill="{color}"{rule} d="{d}"/>' for d in geometry)


def svg(name, title, body, width=256):
    (BRAND / f"quodex-{name}.svg").write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} 256" role="img" aria-label="{escape(title)}">\n'
        f"  <title>{escape(title)}</title>\n{body}\n</svg>\n"
    )


def generate():
    regular, small = mark(), mark(small=True)
    for suffix, geometry in [("", regular), ("-small", small)]:
        for theme, color in [("", TEAL), ("-on-dark", MINT)]:
            svg(f"symbol{suffix}{theme}", f"Quodex rounded Q{suffix}", paths(geometry, color))
    for name, color, text in [("color", TEAL, INK), ("on-dark", MINT, "#FFFFFF")]:
        body = paths(regular, color)
        body += '\n  <g transform="translate(268 46) scale(.64)">\n'
        body += paths(regular + LETTERS, text, evenodd=True) + "\n  </g>"
        svg(f"horizontal-{name}", "Quodex horizontal logo", body, 922)
    for name, corner, scale, geometry in [
        ("app-icon", 64, .825, regular),
        ("favicon", 64, 1, small),
        ("maskable", 0, .76, regular),
    ]:
        inset = 128 * (1 - scale)
        body = f'  <rect width="256" height="256" rx="{corner}" fill="{INK}"/>\n'
        body += f'  <g transform="translate({inset:g} {inset:g}) scale({scale:g})">\n'
        body += paths(geometry, MINT) + "\n  </g>"
        svg(name, f"Quodex {name}", body)
    for name, adaptive, color in [
        ("ic_quodex", False, "@color/quodex_icon_foreground"),
        ("ic_quodex_foreground", True, "@color/quodex_icon_foreground"),
        ("ic_quodex_monochrome", True, INK),
    ]:
        size, viewport = (108, 108) if adaptive else (48, 256)
        scale, inset = (50 / 192, 54 - 128 * 50 / 192) if adaptive else (.825, 22.4)
        xml = '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        xml += f'    android:width="{size}dp" android:height="{size}dp"\n    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n'
        xml += '    <!-- Generated by scripts/generate_brand_assets.py. -->\n'
        if adaptive:
            xml += '    <!-- The entire Q, including the tail, fits within the central 66dp safe circle. -->\n'
        else:
            xml += '    <path android:fillColor="@color/quodex_icon_background"\n        android:pathData="M64,0H192A64,64 0 0 1 256,64V192A64,64 0 0 1 192,256H64A64,64 0 0 1 0,192V64A64,64 0 0 1 64,0Z" />\n'
        xml += f'    <group android:scaleX="{scale:.9f}" android:scaleY="{scale:.9f}"\n        android:translateX="{inset:.9f}" android:translateY="{inset:.9f}">\n'
        xml += "\n".join(f'        <path android:fillColor="{color}"\n            android:pathData="{d}" />' for d in regular)
        xml += "\n    </group>\n</vector>\n"
        (DRAWABLE / f"{name}.xml").write_text(xml)


if __name__ == "__main__":
    generate()
