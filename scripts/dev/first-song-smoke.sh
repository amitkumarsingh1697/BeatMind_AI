#!/usr/bin/env bash
# End-to-end check against a running local stack (docker compose up): sign in with an email code,
# pass the 18+ step, ask for a song, wait for it over the live event stream, fetch both takes
# (Sprint 1), then find it in the library, download the labelled MP3 through a signed link and
# check its provenance chain (Sprint 2). Goes through the web app's forwarder, as a browser would.
#
#   scripts/dev/first-song-smoke.sh [base-url]     # default http://localhost:3000
set -euo pipefail

BASE="${1:-http://localhost:3000}"
EMAIL="smoke-$(date +%s)@example.com"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT
json() { curl -fsS -b "$JAR" -c "$JAR" -H 'content-type: application/json' "$@"; }

echo "1. Sign in as $EMAIL"
code=$(json -d "{\"email\":\"$EMAIL\"}" "$BASE/v1/auth/email/start" | sed -n 's/.*"devCode":"\([0-9]\{6\}\)".*/\1/p')
[ -n "$code" ] || { echo "no dev code returned; is BEATMIND_AUTH_DEV_CODES on?"; exit 1; }
json -d "{\"email\":\"$EMAIL\",\"code\":\"$code\"}" "$BASE/v1/auth/email/verify" >/dev/null

echo "2. Complete the 18+ profile step"
json -d '{"birthDate":"1995-04-12","acceptTerms":true,"termsVersion":"2026-11-beta"}' "$BASE/v1/me/profile" >/dev/null

echo "3. Ask for a song"
id=$(json -d '{"kind":"song","prompt":"smoke test: monsoon lofi with flute","language":"hi"}' \
  "$BASE/v1/generations" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "   job $id"

echo "4. Follow the live status"
events=$(curl -fsSN -b "$JAR" --max-time 90 "$BASE/v1/generations/$id/events")
grep -o '"status":"[a-z]*"' <<<"$events" | sed 's/^/   /'
grep -q '"status":"succeeded"' <<<"$events"

echo "5. Fetch both takes"
for n in 1 2; do
  bytes=$(curl -fsS -b "$JAR" -o /dev/null -w '%{size_download}' "$BASE/v1/generations/$id/takes/$n/audio")
  echo "   take $n: $bytes bytes"
  [ "$bytes" -gt 1000 ]
done

echo "6. Find the song in the library"
song=$(json "$BASE/v1/generations/$id" | sed -n 's/.*"songId":"\([^"]*\)".*/\1/p')
json "$BASE/v1/songs?q=monsoon" | grep -q "\"id\":\"$song\""
echo "   song $song"

echo "7. Download the labelled MP3 through a signed link"
url=$(json -d '{"format":"mp3"}' "$BASE/v1/songs/$song/takes/1/downloads" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
file="$(mktemp)"
curl -fsS -o "$file" "$BASE$url"
head -c 3 "$file" | grep -q ID3 || { echo "download is not an MP3 with ID3 tags"; exit 1; }
grep -aq "Made with BeatMind AI" "$file" || { echo "AI label missing from the file"; exit 1; }
grep -aq "c2pa" "$file" || { echo "C2PA manifest missing from the file"; exit 1; }
echo "   $(wc -c <"$file") bytes, AI label and C2PA manifest present"
rm -f "$file"

echo "8. Check the provenance chain"
json "$BASE/v1/songs/$song/provenance" | grep -q '"valid":true'
echo "   chain verifies"
echo "First song end to end: OK"
