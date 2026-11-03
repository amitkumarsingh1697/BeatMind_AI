# G1 listening test protocol (EXP-03, US-040)

How to run gate G1 for Hindi, Hinglish and English. The kit is in
`workers/beatmind_workers/g1.py`; this page is the protocol around it. The test itself needs people,
so it is a TODO.md item, not code.

## Pass mark (PRD section 12, gate G1)

A language passes when **at least 30 native listeners** have compared BeatMind with the best
available competitor for creator background music, blind, and BeatMind was **preferred or tied in
at least 40% of the pairings**. Until a language passes, the app shows it as experimental
(REQ-F-07). After a pass, set `BEATMIND_G1_PASSED` on the API (for example `hi,hinglish`) and log
the decision in `docs/00-governance/decision-log.md`.

## Before the test

1. **Prompts.** 20 per language, covering creator uses: vlog background, reel hook, devotional,
   wedding, lofi study, motivational, podcast intro. Same prompt text for both systems. Store them in
   `docs/11-validation/g1/<language>-prompts.csv`.
2. **Clips.** For each prompt, one BeatMind take (the first take, no cherry-picking) and one take
   from the best available competitor. Trim both to the same 30 seconds from the start, normalise to
   -14 LUFS, export as MP3 192 kbps so file quality does not give the system away.
3. **Competitor terms.** Check each competitor's terms allow internal evaluation before generating.
   Competitor clips are used only for this test and deleted afterwards (validation plan, EXP-03).
4. **Clips file.** `clips.csv` with `language,prompt_id,system,file`, system `beatmind` or
   `competitor`.
5. **Plan.** `python -m beatmind_workers.g1 plan clips.csv --listeners 32 --out g1-run --seed <date>`.
   Recruit two spare listeners per language in case some drop out. Keep `g1-run/key.csv` with the
   test lead only.

## Listeners

- Native speakers for Hindi and English; for Hinglish, people who type Hindi in Latin letters every
  day. 18 or older, not BeatMind staff or their families, not paid by any competitor.
- Consent form: what they will hear, that answers are anonymous, how long the data is kept.
- A small honorarium if the budget allows; the same for everyone, not tied to answers.

## During the test

- Each listener gets their own sheet (`g1-run/sheets/<listener>.csv`): 20 pairs, random order,
  BeatMind on a random side. They see only "A" and "B".
- Play through headphones in a quiet room or with good earphones. Each clip can be replayed.
- For each pair: "Which would you rather use as background music for your video?" (A, B or "no
  difference"), then "How clear and natural was the singing in clip X?" 1 to 5, asked about the
  BeatMind clip only, without saying which one it is (the sheet names the clip).
- No discussion between listeners until everyone is done.

## After the test

1. Collect answers into one `answers.csv`: `listener,pair_id,choice,pronunciation` (choice `A`, `B`
   or `tie`).
2. `python -m beatmind_workers.g1 analyse g1-run/key.csv answers.csv --out docs/11-validation/g1/report.md`.
3. Commit the report, the prompts, the key and the anonymised answers (not the audio) under
   `docs/11-validation/g1/`, and add an entry to `evidence-log.csv`.
4. Delete the competitor clips.

## Reading the result

- The report gives the share preferred or tied, a 95% Wilson interval, and the mean per listener.
  The interval treats pairings as independent, so it is a little too narrow; if the share passes
  but the per-listener mean is well below it, a few listeners drove the result and the test should
  be repeated with more people.
- A pass with a lower bound below 40% is still a pass under the PRD rule, but say so in the
  decision log.
- Pronunciation below 3.5 on average is worth fixing (respelling, model choice) even when the
  language passes.
