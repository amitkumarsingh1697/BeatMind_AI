#!/usr/bin/env bash
# Rebuilds every BeatMind AI pitch deliverable from source.
# Needs: Node 18+, Python 3 with lxml and python-docx, ImageMagick, ffmpeg, Playwright + Chromium,
#        LibreOffice + poppler-utils (for the PDF and renders), fonts from ./fonts installed.
# Usage: bash build.sh [path/to/sprint-06-screens]      (crops are re-made only if a path is given)
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules ] || npm install
[ $# -ge 1 ] && bash src/make_crops.sh "$1"
if [ "${ASSETS:-0}" = "1" ]; then              # 3D renders and the cover video (slow, about 5 minutes)
  node src/render_assets.js --video
  python3 src/tight_trim.py assets/generated
  for o in assets/generated/orb_*.png; do convert "$o" -resize 420x420 "$o"; done
fi
mkdir -p out/r
node src/build_deck.js                                    # -> out/_raw.pptx + out/anim_plan.json
python3 src/postprocess.py out/_raw.pptx out/anim_plan.json out/BeatMind_AI_Client_Pitch_Deck.pptx
soffice --headless --convert-to pdf --outdir out out/BeatMind_AI_Client_Pitch_Deck.pptx
pdftoppm -jpeg -r 90 out/BeatMind_AI_Client_Pitch_Deck.pdf out/r/s
node -e "console.log(JSON.stringify(require('./src/script_content')))" > out/script.json
python3 src/build_script_docx.py out/script.json out/r out/BeatMind_AI_Presenter_Script.docx
node src/build_design_guide.js out/r out/BeatMind_AI_Presentation_Design_Guide.pdf
node src/build_storyboard.js > out/BeatMind_AI_Storyboard.md
echo "done: see ./out"
