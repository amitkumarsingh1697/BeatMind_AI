#!/usr/bin/env python3
"""US-005: time and cost per take for ACE-Step 1.5 on a rented GPU.

Run on the GPU machine while `uv run acestep-api` is serving. Example:

    python3 benchmark.py --gpu "RTX 4090" --usd-per-hour 0.35 --out results/rtx4090

It writes results.csv (one row per task) and summary.md, and samples GPU memory with nvidia-smi.
Repeat on three GPU types, then copy the result folders into docs/06-ai/benchmarks/.
"""

from __future__ import annotations

import argparse
import csv
import shutil
import statistics
import subprocess
import threading
import time
from pathlib import Path

from acestep_client import AceStepClient

HERE = Path(__file__).parent
NVIDIA_SMI = shutil.which("nvidia-smi") or "nvidia-smi"


class VramSampler(threading.Thread):
    """Polls nvidia-smi for the peak memory used on GPU 0."""

    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.peak_mib = 0
        self.stop_flag = threading.Event()
        self.available = shutil.which("nvidia-smi") is not None

    def run(self) -> None:
        while self.available and not self.stop_flag.is_set():
            query = [NVIDIA_SMI, "--query-gpu=memory.used", "--format=csv,noheader,nounits", "-i", "0"]
            out = subprocess.run(  # noqa: S603 - fixed command, no user input
                query,
                capture_output=True,
                text=True,
                check=False,
            )
            if out.returncode == 0 and out.stdout.strip():
                self.peak_mib = max(self.peak_mib, int(out.stdout.strip().splitlines()[0]))
            time.sleep(0.5)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", default="http://localhost:8001")
    ap.add_argument("--api-key", default="")
    ap.add_argument("--gpu", required=True, help='GPU name for the report, e.g. "RTX 4090"')
    ap.add_argument("--usd-per-hour", type=float, required=True, help="what you pay for this GPU per hour")
    ap.add_argument("--prompts", default=str(HERE / "prompts.csv"))
    ap.add_argument("--batch-size", type=int, default=2, help="takes per task (PRD: two takes)")
    ap.add_argument("--inference-steps", type=int, default=8, help="8 for turbo models")
    ap.add_argument("--repeats", type=int, default=2)
    ap.add_argument("--out", required=True)
    ap.add_argument("--keep-audio", action="store_true", help="download the audio for listening")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    client = AceStepClient(args.api, args.api_key)
    with open(args.prompts, newline="", encoding="utf-8") as f:
        prompts = list(csv.DictReader(f))

    print("Warm-up task (not counted)...")
    client.generate({"prompt": "short warm up", "audio_duration": 10, "batch_size": 1})

    sampler = VramSampler()
    sampler.start()
    rows = []
    for rep in range(args.repeats):
        for p in prompts:
            params = {
                "prompt": p["prompt"],
                "lyrics": p["lyrics"],
                "vocal_language": p["vocal_language"],
                "audio_duration": float(p["duration"]),
                "batch_size": args.batch_size,
                "inference_steps": args.inference_steps,
                "audio_format": "mp3",
            }
            r = client.generate(params)
            takes = len(r.files) if r.ok else 0
            cost_per_take = (args.usd_per_hour / 3600 * r.seconds / takes) if takes else None
            rows.append(
                {
                    "gpu": args.gpu,
                    "prompt_id": p["id"],
                    "repeat": rep + 1,
                    "duration_s": p["duration"],
                    "ok": r.ok,
                    "seconds": round(r.seconds, 2),
                    "takes": takes,
                    "usd_per_take": round(cost_per_take, 5) if cost_per_take is not None else "",
                    "dit_model": r.dit_model,
                    "lm_model": r.lm_model,
                    "error": r.error[:200],
                }
            )
            print(f"{p['id']} rep {rep + 1}: {'ok' if r.ok else 'FAILED'} in {r.seconds:.1f}s")
            if r.ok and args.keep_audio:
                for i, file_url in enumerate(r.files):
                    client.download(file_url, str(out / f"{p['id']}-r{rep + 1}-t{i + 1}.mp3"))
    sampler.stop_flag.set()

    with open(out / "results.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    ok = [r for r in rows if r["ok"]]
    secs = [r["seconds"] for r in ok]
    costs = [r["usd_per_take"] for r in ok]
    models = sorted({r["dit_model"] for r in ok} | {r["lm_model"] for r in ok})
    settings = f"batch size {args.batch_size}; inference steps {args.inference_steps}"
    lines = [
        f"# ACE-Step 1.5 benchmark: {args.gpu}",
        "",
        f"- Date: {time.strftime('%Y-%m-%d')}",
        f"- Models reported by the server: {', '.join(models)}",
        f"- Price paid: US${args.usd_per_hour}/hour; {settings}",
        f"- Tasks: {len(rows)}, succeeded: {len(ok)}",
        f"- Peak GPU memory: {sampler.peak_mib} MiB"
        if sampler.available
        else "- Peak GPU memory: nvidia-smi not found",
    ]
    if ok:
        p95 = sorted(secs)[int(0.95 * (len(secs) - 1))]
        lines += [
            f"- Seconds per task: median {statistics.median(secs):.1f}, p95 {p95:.1f}",
            f"- **US$ per take: median {statistics.median(costs):.5f}, max {max(costs):.5f}** "
            "(GPU time only, at full utilisation; idle time and storage are extra)",
        ]
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
