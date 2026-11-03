// US-042: load test at twice the expected closed-beta peak (REQ-NFR-01, gate G2).
//
// Each virtual user is one listener: sign in, open the library, ask for a song, follow it until
// both takes are ready, play a take, then browse and share. The pass bar is REQ-NFR-01: a song
// playable within 60 s at p95, plus the API budgets in thresholds below.
//
//   k6 run scripts/load/k6-beta.js                                   # local stack, stub GPU
//   BASE=https://staging.beatmind.ai SESSIONS=sessions.json k6 run scripts/load/k6-beta.js
//
// Settings (environment variables):
//   BASE        web app origin; requests go through its /v1 forwarder like a browser. Default local.
//   PEAK        expected beta peak of people making songs at the same time. Default 50 (see README).
//   SESSIONS    JSON file with an array of bm_session cookie values, one per test account. Needed
//               where dev sign-in codes are off (staging). Without it, accounts are made with dev codes.
//   HOLD        minutes at 2x peak. Default 10.
//   RAMP        minutes for each ramp step. Default 2.
import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE || 'http://localhost:3000';
const PEAK = Number(__ENV.PEAK || 50);
const HOLD = Number(__ENV.HOLD || 10);
const RAMP = Number(__ENV.RAMP || 2);
const SESSIONS = __ENV.SESSIONS ? JSON.parse(open(__ENV.SESSIONS)) : null;

const playable = new Trend('song_playable_seconds', false);
const failedSongs = new Rate('song_failed');
const refused = new Counter('song_refused_by_cap');

export const options = {
  noCookiesReset: true, // keep each virtual user signed in across iterations
  scenarios: {
    beta: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: `${RAMP * 60}s`, target: PEAK },
        { duration: `${RAMP * 60}s`, target: PEAK * 2 },
        { duration: `${HOLD * 60}s`, target: PEAK * 2 },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '90s',
    },
  },
  thresholds: {
    song_playable_seconds: ['p(95)<60'], // REQ-NFR-01, beta target
    song_failed: ['rate<0.02'],
    'http_req_duration{kind:read}': ['p(95)<500'],
    'http_req_duration{kind:write}': ['p(95)<1500'],
    http_req_failed: ['rate<0.01'],
  },
};

const JSON_HEADERS = { 'content-type': 'application/json' };
const PROMPTS = [
  'monsoon lofi with flute',
  'sufi qawwali with harmonium and handclaps',
  'upbeat punjabi wedding song',
  'slow acoustic love song in Hinglish',
  'chill study beat with rain sounds',
];

function signIn(vu) {
  const jar = http.cookieJar();
  if (SESSIONS) {
    if (!SESSIONS.length) fail('SESSIONS is empty');
    jar.set(BASE, 'bm_session', SESSIONS[vu % SESSIONS.length]);
    return;
  }
  const email = `load-${exec.scenario.iterationInTest}-${vu}-${Date.now()}@example.com`;
  const start = http.post(`${BASE}/v1/auth/email/start`, JSON.stringify({ email }), {
    headers: JSON_HEADERS, tags: { kind: 'write', name: 'auth/start' },
  });
  const code = start.json('devCode');
  if (!code) fail('no dev code returned; set SESSIONS for an environment without dev codes');
  http.post(`${BASE}/v1/auth/email/verify`, JSON.stringify({ email, code }), {
    headers: JSON_HEADERS, tags: { kind: 'write', name: 'auth/verify' },
  });
  http.post(`${BASE}/v1/me/profile`,
    JSON.stringify({ birthDate: '1995-04-12', acceptTerms: true, termsVersion: '2026-11-beta' }),
    { headers: JSON_HEADERS, tags: { kind: 'write', name: 'me/profile' } });
}

export default function () {
  const vu = exec.vu.idInTest;
  if (exec.vu.iterationInScenario === 0) signIn(vu);

  http.get(`${BASE}/v1/songs`, { tags: { kind: 'read', name: 'songs' } });
  http.get(`${BASE}/v1/me`, { tags: { kind: 'read', name: 'me' } });

  const beat = Math.random() < 0.25;
  const body = beat
    ? { kind: 'beat', genre: 'lofi', bpm: 80 + Math.floor(Math.random() * 60), key: 'A minor' }
    : { kind: 'song', prompt: PROMPTS[Math.floor(Math.random() * PROMPTS.length)], language: 'hi' };
  const asked = Date.now();
  const res = http.post(`${BASE}/v1/generations`, JSON.stringify(body), {
    headers: JSON_HEADERS, tags: { kind: 'write', name: 'generations' },
  });
  if (res.status === 429 || res.status === 402) {
    // Daily cap reached for this account: a real limit, not a failure. Browse instead.
    refused.add(1);
    sleep(30);
    return;
  }
  if (!check(res, { 'song accepted': (r) => r.status === 201 || r.status === 202 })) {
    failedSongs.add(1);
    sleep(10);
    return;
  }
  const id = res.json('id');

  // The web app listens to /events; k6 has no event-source client, so poll as a slow client would.
  let status = 'queued';
  let gen = null;
  while (Date.now() - asked < 180000) {
    sleep(2);
    const g = http.get(`${BASE}/v1/generations/${id}`, { tags: { kind: 'read', name: 'generations/id' } });
    gen = g.json();
    status = gen && gen.status;
    if (status === 'succeeded' || status === 'failed' || status === 'dead' || status === 'blocked') break;
  }
  const ok = status === 'succeeded';
  failedSongs.add(!ok);
  if (!ok) return;

  const audio = http.get(`${BASE}/v1/generations/${id}/takes/1/audio`, {
    tags: { kind: 'read', name: 'takes/audio' },
  });
  if (check(audio, { 'take plays': (r) => r.status === 200 && r.body.length > 1000 })) {
    playable.add((Date.now() - asked) / 1000);
  }

  // Listen for a while, then look at the song and sometimes share it.
  sleep(20 + Math.random() * 20);
  const song = gen.songId;
  http.get(`${BASE}/v1/songs/${song}`, { tags: { kind: 'read', name: 'songs/id' } });
  if (Math.random() < 0.2) {
    http.post(`${BASE}/v1/songs/${song}/shares`, JSON.stringify({ visibility: 'private' }), {
      headers: JSON_HEADERS, tags: { kind: 'write', name: 'shares' },
    });
  }
  sleep(10 + Math.random() * 20);
}
