"""Separated stems (US-039, REQ-F-23): vocals, drums, bass and other.

The production separator is Demucs (``htdemucs``, MIT licence, model register) on the GPU worker:
run a worker with ``BEATMIND_STEMS=demucs`` and the ``demucs`` package installed. Locally the
stub separator splits the spectrum into bands, which is enough to test the pipeline end to end.

Two checks come with every separation:

* the **sum test** (TC-REQ-F-23-02): the four stems added together must give back the mix. The
  check reports the ratio of mix energy to residual energy in dB and passes above
  ``SUM_MIN_SNR_DB``.
* **SDR** (TC-REQ-F-23-03): signal-to-distortion ratio of each stem against known sources, on a
  test set (``evaluate``). The stub is measured on synthetic band-limited sources; Demucs has to be
  measured on real multitrack songs (TODO.md).
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

import numpy as np

from beatmind_workers import finishing

RATE = finishing.watermark.SAMPLE_RATE
NAMES = ("vocals", "drums", "bass", "other")
SUM_MIN_SNR_DB = 30.0

Separator = Callable[[np.ndarray], dict[str, np.ndarray]]


def _band(samples: np.ndarray, low: float, high: float) -> np.ndarray:
    spectrum = np.fft.rfft(samples, axis=0)
    freqs = np.fft.rfftfreq(len(samples), 1 / RATE)
    mask = ((freqs >= low) & (freqs < high))[:, None]
    return np.fft.irfft(spectrum * mask, n=len(samples), axis=0).astype(np.float32)


def stub_separate(samples: np.ndarray) -> dict[str, np.ndarray]:
    """Stand-in for Demucs: bass below 180 Hz, vocals 180 Hz to 4 kHz, drums above 8 kHz, and the
    rest (4 to 8 kHz plus rounding) as "other", so the stems add back up to the mix exactly."""
    bass = _band(samples, 0, 180)
    vocals = _band(samples, 180, 4000)
    drums = _band(samples, 8000, RATE)
    other = (samples - bass - vocals - drums).astype(np.float32)
    return {"vocals": vocals, "drums": drums, "bass": bass, "other": other}


class DemucsSeparator:
    """htdemucs through ``demucs.api`` (demucs 4.1). Imported only on the GPU worker."""

    model = "htdemucs"

    def __init__(self, model: str = "htdemucs") -> None:
        import demucs.api  # noqa: PLC0415 - heavy, GPU-only dependency

        self.model = model
        self._separator = demucs.api.Separator(model=model)

    def __call__(self, samples: np.ndarray) -> dict[str, np.ndarray]:
        import torch  # noqa: PLC0415
        from demucs.audio import convert_audio  # noqa: PLC0415

        rate = self._separator.samplerate
        wav = torch.from_numpy(np.ascontiguousarray(samples.T))
        wav = convert_audio(wav, RATE, rate, 2)
        _, separated = self._separator.separate_tensor(wav, rate)
        out = {}
        for name in NAMES:
            stem = convert_audio(separated[name].cpu(), rate, RATE, 2).numpy().T
            stem = stem[: len(samples)]
            if len(stem) < len(samples):
                stem = np.pad(stem, ((0, len(samples) - len(stem)), (0, 0)))
            out[name] = stem.astype(np.float32)
        return out


def _ratio_db(signal: np.ndarray, noise: np.ndarray) -> float:
    s = float(np.sum(signal.astype(np.float64) ** 2))
    n = float(np.sum(noise.astype(np.float64) ** 2))
    if n <= 1e-20:
        return 120.0
    if s <= 1e-20:
        return -120.0
    return round(float(10 * np.log10(s / n)), 2)


def sum_check(mix: np.ndarray, stems: dict[str, np.ndarray]) -> dict:
    """TC-REQ-F-23-02: the stems must add back up to the mix."""
    total = sum(stems[n] for n in NAMES)
    snr = _ratio_db(mix, mix - total)
    return {
        "snrDb": snr,
        "minSnrDb": SUM_MIN_SNR_DB,
        "maxAbsError": round(float(np.max(np.abs(mix - total))) if len(mix) else 0.0, 6),
        "passed": bool(snr >= SUM_MIN_SNR_DB),
    }


def sdr(reference: np.ndarray, estimate: np.ndarray) -> float:
    """Signal-to-distortion ratio in dB (the plain BSS form, without allowed distortions)."""
    return _ratio_db(reference, reference - estimate)


@dataclass
class Case:
    name: str
    sources: dict[str, np.ndarray]

    @property
    def mix(self) -> np.ndarray:
        return sum(self.sources[n] for n in NAMES).astype(np.float32)


def synthetic_set(count: int = 4, seconds: float = 3.0, seed: int = 7) -> list[Case]:
    """A small test set with known sources in the stub's bands: a low bass line, a mid "voice",
    hi-hat-like noise bursts for drums and a 5 kHz "other" part."""
    rng = np.random.default_rng(seed)
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    cases = []
    for i in range(count):
        bass = 0.3 * np.sin(2 * np.pi * rng.uniform(50, 110) * t)
        voice_f = rng.uniform(250, 700)
        vocals = 0.2 * np.sin(2 * np.pi * voice_f * t + 2 * np.sin(2 * np.pi * 5 * t))
        other = 0.08 * np.sin(2 * np.pi * rng.uniform(4600, 7200) * t)
        burst = (np.sin(2 * np.pi * rng.uniform(2, 4) * t) > 0.95).astype(np.float64)
        noise = _band(rng.normal(0, 1, (n, 2)).astype(np.float32), 9000, 20000)[:, 0]
        drums = 0.5 * burst * noise
        stereo = {
            k: np.stack([v, v], axis=1).astype(np.float32)
            for k, v in {"vocals": vocals, "drums": drums, "bass": bass, "other": other}.items()
        }
        cases.append(Case(f"synthetic-{i + 1}", stereo))
    return cases


def evaluate(separator: Separator, cases: list[Case]) -> dict:
    """TC-REQ-F-23-03: SDR per stem on a test set, with the mean and the worst case."""
    per: dict[str, list[float]] = {n: [] for n in NAMES}
    rows = []
    for case in cases:
        estimate = separator(case.mix)
        row = {"case": case.name}
        for name in NAMES:
            value = sdr(case.sources[name], estimate[name])
            per[name].append(value)
            row[name] = value
        rows.append(row)
    return {
        "cases": rows,
        "meanSdrDb": {n: round(float(np.mean(v)), 2) for n, v in per.items()},
        "minSdrDb": {n: round(float(np.min(v)), 2) for n, v in per.items()},
    }


def separator_from_env(kind: str) -> tuple[Separator, str]:
    if kind == "demucs":
        d = DemucsSeparator()
        return d, d.model
    return stub_separate, "stub-bands"
