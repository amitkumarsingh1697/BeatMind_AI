# Song remake on a GPU

Tests whether ACE-Step 1.5, set up properly, fixes the listener feedback on Raat Ka Safar: vocals
that sound read aloud, no pitch or dynamic change, and a rap that sounds like a lullaby. The CPU
runs used the turbo model at 8 steps and the 0.6B LM, and the LM rewrote our style prompt.

## Run (about an hour on one rented GPU)

```sh
git clone https://github.com/ace-step/ACE-Step-1.5.git && cd ACE-Step-1.5
uv sync
uv run acestep-download --model acestep-v15-sft
ACESTEP_INIT_LLM=true ACESTEP_CONFIG_PATH=acestep-v15-sft ACESTEP_LM_MODEL_PATH=acestep-5Hz-lm-1.7B uv run acestep-api
```

In a second shell, from this folder:

```sh
python3 remake.py --out remake --takes 6
```

Zip the `remake` folder and send it back. Each take is a 2.5-minute WAV.

What the script changes from the CPU runs: `use_cot_caption=false` so our prompt is used as written,
fixed `bpm`, `key_scale` and `time_signature`, 50 inference steps, and 6 takes to choose from.
