# Hindi and Hinglish quality spike (US-006)

A quick check, before the full G1 listening test, of whether ACE-Step 1.5 sings Hindi and
Hinglish well enough for creator videos (A-021, RISK-003).

## Pass marks (proposals, agree them before scoring)

The validation plan says pass marks are set before an experiment and never changed after it.
These are proposals [H] for the founder to confirm:

- Mean pronunciation score at least **3.5 out of 5**, and
- at least **60%** of takes judged "usable for a creator video" by most listeners.

A set that passes is **GO** for the beta. A set that fails falls back to the Lyria API for that
language until the full G1 test (30 or more listeners per language).

## Quick CPU trial

The script also runs without a GPU, for a small first look. On a 4-core CPU with 16 GB RAM, one
60-second take of the turbo model took about 5 minutes (2026-10-30). Start the server with the
LM off and a small VAE decode chunk, or the decode runs out of memory:

```sh
ACESTEP_DEVICE=cpu ACESTEP_INIT_LLM=false ACESTEP_VAE_DECODE_CHUNK_SIZE=64 uv run acestep-api
python3 generate.py --out trial --sets hindi --limit 5 --timeout 5400
```

## Steps

1. With the ACE-Step API server running on a GPU (see `../../bench/README.md`):

   ```sh
   python3 generate.py --out run1 --listeners 3
   ```

   This makes one take for each of 20 Hindi and 20 Hinglish prompts (`prompts-*.csv`), renames
   the audio to random codes, and writes `key.csv`, `lyrics-sheet.csv` and one blank sheet per
   listener.
2. Give each of 3 native Hindi speakers the `audio` folder, `lyrics-sheet.csv` and their own
   `sheet-listener-N.csv`. Do not share `key.csv`. They fill in, for every code:
   pronunciation (1 to 5), musicality (1 to 5), and whether they would use it in a video (yes/no).
3. Put the filled sheets back in `run1` and run:

   ```sh
   python3 score.py run1
   ```

4. Record the result and the raw sheets in `docs/11-validation/` and the evidence log, then note
   the go or fallback decision in the decision log.

The lyrics in the prompt files were written for this spike and are original. They should be
checked by a native speaker before use.
