#!/usr/bin/env bash
# Crops the Sprint 6 screenshots into the frames used by the deck.
# Usage: bash src/make_crops.sh /path/to/beatmind/sprint-06-screens
set -euo pipefail
S="$1"; O="$(cd "$(dirname "$0")/.." && pwd)/assets/crops"; mkdir -p "$O"; cd "$S"
c(){ convert "$1.png" -crop "$2" +repage "$O/$3.png"; }
c 03-create-vibe        1280x1000+0+0    create-vibe
c 07-song               1280x860+0+0     song-hi
c 07-song               760x320+262+2540 history
c 08b-licensed          1280x900+0+0     song-licensed
c 08c-certificate       1280x1100+0+0    certificate
c 08c-certificate       820x760+230+60   certificate-card
c 22-verify-certificate 1280x928+0+0     verify
c 23-stems              600x232+202+1528 stems
c 12-admin              1280x1000+0+0    admin
c 19-pricing            1280x961+0+0     pricing
c 05-library            1280x938+0+0     library
c 20-editor             760x880+262+630  editor
c 08f-report-form       1280x1100+0+0    report
c 03a-blocked           740x290+280+1050 blocked
c 08e-shared-page       1280x1014+0+0    shared
c 01-waitlist           1280x1100+0+0    waitlist
for f in 14-phone-create-hi 13-phone-song-hi 19c-phone-pricing-hi 22b-phone-verify-hi 15-phone-library-hi 18-phone-shared-hi 10-phone-create 11-phone-song; do
  convert "$f.png" -crop 780x1688+0+0 +repage "$O/ph-$f.png"
done
echo "crops written to $O"
