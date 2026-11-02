#!/usr/bin/env bash
# Sprint 1 end-to-end check against a running local stack (docker compose up): sign in with an
# email code, pass the 18+ step, ask for a song, wait for it over the live event stream, and fetch
# both takes. Goes through the web app's forwarder, as a browser would.
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
echo "First song end to end: OK"
