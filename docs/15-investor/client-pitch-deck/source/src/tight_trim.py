"""Crops generated device renders to their visible body (alpha > 200) plus a margin,
then feathers the margin so any remaining glow fades out instead of ending in a hard edge.
Run after render_assets.js (it works on the freshly rendered PNGs)."""
import sys, glob
from PIL import Image, ImageChops, ImageDraw, ImageFilter
PAD = 26
for f in sorted(glob.glob(sys.argv[1] + '/*.png')):
    name = f.split('/')[-1]
    if not name.startswith(('dev_', 'card_', 'ph_')):
        continue
    im = Image.open(f).convert('RGBA')
    box = im.getchannel('A').point(lambda a: 255 if a > 200 else 0).getbbox()
    if not box:
        continue
    l, t, r, b = box
    im = im.crop((max(0, l - PAD), max(0, t - PAD), min(im.width, r + PAD), min(im.height, b + PAD)))
    mask = Image.new('L', im.size, 0)
    ImageDraw.Draw(mask).rectangle((PAD // 2, PAD // 2, im.width - PAD // 2, im.height - PAD // 2), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(PAD / 3))
    im.putalpha(ImageChops.multiply(im.getchannel('A'), mask))
    im.save(f, optimize=True)
    print(name, im.size)
