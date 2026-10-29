# ACE-Step 1.5 GPU benchmark (US-005)

Measures seconds per task, peak GPU memory and US$ per take on a rented GPU, so the financial
model's GPU cost (A-025, A-026, BUG-076) can be replaced with a measured number.

## Run it (about 1 hour per GPU type)

1. Rent a Linux GPU machine with CUDA by the hour. Try three types, for example an RTX 4090
   (24 GB), an L4 (24 GB) and an RTX 3090 or A10 (24 GB). Note the exact hourly price you pay.
   Startup credits count too; use the list price for the cost figure.
2. On the machine, install ACE-Step 1.5 and start its API server
   ([install guide](https://github.com/ace-step/ACE-Step-1.5/blob/main/docs/en/INSTALL.md)):

   ```sh
   git clone https://github.com/ace-step/ACE-Step-1.5.git && cd ACE-Step-1.5
   uv sync
   uv run acestep-api        # serves on http://localhost:8001
   ```

3. In a second shell, copy this `bench` folder over and run:

   ```sh
   python3 benchmark.py --gpu "RTX 4090" --usd-per-hour 0.35 --out results/rtx4090 --keep-audio
   ```

   It runs a warm-up, then 10 creator prompts twice, two takes each (the PRD's two takes),
   and writes `results.csv` and `summary.md`.
4. Switch the server to another model variant (turbo, base, XL) if you want to compare, and rerun
   with a different `--out`.
5. Shut the machine down. Copy each `summary.md` and `results.csv` into
   `docs/06-ai/benchmarks/<gpu>/` and open a pull request. Keep the audio out of the repository.

## What to read in the results

- **US$ per take** is GPU time only at full use. Real cost is higher when the GPU sits idle, so
  the financial model should use this number times an idle factor until beta traffic is known.
- The financial model assumes US$0.004 per take [A]. Above about US$0.006, Indian Pro margin falls
  to about 40% (pricing-and-financial-model.md section 2).
- Prompts are in English. Hindi and Hinglish quality is a separate spike (`../spikes/hindi`).

## Licence check (C-02)

The MIT licence file from the ACE-Step 1.5 repository was archived on 2026-10-29 in
`docs/06-ai/licences/`. Before the beta, also save a dated copy of the Hugging Face model card for
the exact checkpoint used, since the card carries the training-data statement (SRC-106).
