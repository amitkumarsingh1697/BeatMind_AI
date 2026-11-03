"""US-015: beat mode style text, the stub beat and the tempo check (REQ-F-04)."""

import numpy as np
import pytest

from beatmind_workers import beat, main, queue
from beatmind_workers.acestep import AceStepRunner

SETTINGS = {"genre": "lofi", "bpm": 90, "key": "A minor", "include": ["piano", "tabla"], "exclude": ["drums"]}


class FakeStore:
    def __init__(self):
        self.objects = {}

    def put(self, key, data, content_type):
        self.objects[key] = (data, content_type)
        return key


def test_style_names_every_setting_and_says_instrumental():
    text = beat.style(SETTINGS, "rainy chai stall")
    assert text == (
        "lofi, 90 BPM, A minor, featuring piano, tabla, no drums, instrumental, no vocals, rainy chai stall"
    )
    assert "A minor" not in beat.style({**SETTINGS, "key": "any"})
    assert "808 bass" in beat.style({"genre": "trap", "bpm": 140, "include": ["808"]})


@pytest.mark.parametrize("bpm", [60, 72, 90, 128, 174, 200])
def test_tempo_check_measures_the_stub_beat(bpm):
    samples, rate = beat.read_wav(beat.render_stub(bpm, "seed", seconds=6))
    result = beat.check(samples, rate, bpm)
    assert result["withinTolerance"] is True
    assert result["octave"] is False
    assert abs(result["measuredBpm"] - bpm) <= bpm * beat.TOLERANCE


def test_tempo_check_flags_a_wrong_tempo_and_accepts_double_time():
    samples, rate = beat.read_wav(beat.render_stub(100, "seed", seconds=6))
    wrong = beat.check(samples, rate, 130)
    assert wrong["withinTolerance"] is False
    double = beat.check(samples, rate, 200)
    assert double["withinTolerance"] is True and double["octave"] is True


def test_silence_has_no_tempo():
    result = beat.check(np.zeros(22050 * 3), 22050, 120)
    assert result["measuredBpm"] is None and result["withinTolerance"] is False


def test_stub_beat_job_records_a_tempo_check_on_every_take():
    payload = {"prompt": "x", "beat": SETTINGS}
    job = queue.Job(id="job-b", kind="beat", payload=payload, attempts=1, max_attempts=3)
    result = main.run_job(job, FakeStore(), "stub")
    assert len(result["takes"]) == 2
    for take in result["takes"]:
        assert take["tempo"]["targetBpm"] == 90
        assert take["tempo"]["withinTolerance"] is True


def test_acestep_params_for_a_beat():
    params = AceStepRunner.params({"prompt": "", "beat": SETTINGS, "durationSeconds": 60, "takes": 2})
    assert params["lyrics"] == "[Instrumental]"
    assert params["bpm"] == 90 and params["key_scale"] == "A minor"
    assert params["thinking"] is False
    assert params["prompt"].startswith("lofi, 90 BPM")
    assert "key_scale" not in AceStepRunner.params({"beat": {**SETTINGS, "key": "any"}})
