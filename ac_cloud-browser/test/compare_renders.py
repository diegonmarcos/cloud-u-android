#!/usr/bin/env python3
"""compare_renders.py <browser.png> <reference.png> <out.txt>

Compares two emulator screenshots of the same page by the page area only (the status bar, each app's own toolbars
and the system navigation are cropped away) as a coarse luminance grid. Exit 0 when the mean absolute difference is
within MAX_DIFF (0..255), else 1; the number is written to out.txt either way."""
import sys
from PIL import Image

MAX_DIFF = 45.0
GRID = (24, 24)
TOP, BOTTOM = 0.16, 0.86   # fraction of the screen height that is page in both apps

def grid(path):
    im = Image.open(path).convert("L")
    w, h = im.size
    im = im.crop((0, int(h * TOP), w, int(h * BOTTOM))).resize(GRID, Image.BILINEAR)
    return list(im.getdata())

a, b = grid(sys.argv[1]), grid(sys.argv[2])
d = sum(abs(x - y) for x, y in zip(a, b)) / len(a)
open(sys.argv[3], "w").write("mean luminance difference %.1f of 255 (limit %.0f)" % (d, MAX_DIFF))
sys.exit(0 if d <= MAX_DIFF else 1)
