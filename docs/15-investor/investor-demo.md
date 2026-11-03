# Investor demo script (US-041)

A five-minute live walk-through of the beta on staging, recorded once as a back-up for meetings
where a live demo is risky. It shows the product, not the market: the deck (`pitch-deck.md`) does
that. Every claim in the voice-over must be true of the build being shown; numbers come only from
`docs/11-validation/evidence-log.csv`.

## Before recording

- Staging is on the release being shown, with the real model on the GPU (not the stub) and the
  G1-passed languages set. If Hindi has not passed G1, say "experimental" when it appears.
- Two browser profiles: the creator (`demo@…`, Creator plan, empty library) and a signed-out window.
- One song made earlier as a fallback, in case the queue is slow on the day.
- Screen at 1440 × 900, browser zoom 110%, notifications off, the bookmarks bar hidden.
- A phone mirrored for the last scene, set to Hindi.

## Script

| Time | Scene | Do | Say |
|---|---|---|---|
| 0:00 | Hook | Open `/hi` on the phone | "[Opening line from the market sizing in `docs/01-market`, with its source.] This is BeatMind: songs from a sentence, in Hindi and English." |
| 0:20 | Describe a song | On the laptop, Create, type "बारिश की रात, lofi, flute के साथ", choose Hindi, press Make | "You describe it in your own words, in Hindi, English or Hinglish." |
| 0:45 | Queue | Point at the position and the wait estimate | "You can see where you are in the queue and about how long it will take." |
| 1:00 | Two takes | Play take 1, then take 2, pick one | "Every request gives two takes. People keep the one they like; that keep rate is our main metric." |
| 1:40 | Beat mode | Switch to Make a beat: lofi, 90 BPM, A minor, include tabla, exclude drums | "Producers want control. The tempo is measured on every take and shown under the player." |
| 2:20 | Label and licence | Open the song, show the AI-generated label, take the commercial licence, open the certificate | "Every song is labelled as AI-generated, and every licence has a certificate anyone can check." |
| 3:00 | Provenance | Show the ledger on the song page | "Each step is written to a tamper-evident ledger: prompt, model, takes, licence." |
| 3:30 | Share | Make a public link, open it in the signed-out window | "Sharing is a link the owner can revoke at any time." |
| 3:50 | Safety | Try "a song in the voice of Arijit Singh", show the refusal | "We do not imitate real artists. Requests like this are refused and reviewed." |
| 4:15 | Takedowns | Open the admin takedown queue with one order on its 3-hour clock | "Rights holders and courts can report a song. A court order is acted on within three hours, as Indian rules require." |
| 4:40 | Close | Back to the phone, play the first song | "Built for India first, safe by design, and ready for the closed beta in January." |
| 5:00 | End | | |

## After recording

- Watch it through once with the sound off and once with only the sound, and fix anything that does
  not work on its own.
- Export at 1080p, keep the file in the data room, and link it from `diligence-pack.md`.
- Re-record when a scene above changes.
