"""US-039, REQ-F-23: four stems, the sum test, SDR on a test set, and a stems job end to end."""

import json
import shutil

import numpy as np
import pytest

from beatmind_workers import finishing, main, queue, stems, stub, verify, watermark
from beatmind_workers.finishing import Finisher

from .test_editing import MemoryStore, music


@pytest.mark.tc("TC-REQ-F-23-02")
def test_four_stems_sum_back_to_the_mix():
    mix = music(8.0)
    separated = stems.stub_separate(mix)
    assert set(separated) == set(stems.NAMES)
    for s in separated.values():
        assert s.shape == mix.shape and s.dtype == np.float32
    check = stems.sum_check(mix, separated)
    assert check["passed"] is True, check
    json.dumps(check)
    assert check["snrDb"] >= stems.SUM_MIN_SNR_DB and check["maxAbsError"] < 1e-4


def test_the_sum_test_catches_a_missing_stem():
    mix = music(4.0)
    separated = stems.stub_separate(mix)
    separated["vocals"] = np.zeros_like(mix)  # a separator that drops the voice
    check = stems.sum_check(mix, separated)
    assert not check["passed"] and check["snrDb"] < stems.SUM_MIN_SNR_DB


@pytest.mark.tc("TC-REQ-F-23-03")
def test_sdr_is_reported_on_the_test_set(capsys):
    report = stems.evaluate(stems.stub_separate, stems.synthetic_set())
    print(json.dumps(report["meanSdrDb"]))
    assert len(report["cases"]) == 4
    for name in stems.NAMES:
        # The stub only splits bands, so on band-limited sources it should be near perfect; a real
        # separator is measured on real songs, with its own floor (TODO.md).
        assert report["minSdrDb"][name] > 20, report
    assert "vocals" in capsys.readouterr().out


def test_sdr_falls_when_a_stem_is_wrong():
    ref = music(2.0)
    assert stems.sdr(ref, ref) == 120.0
    assert stems.sdr(ref, ref * 0.5) == pytest.approx(6.02, abs=0.05)


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")
def test_a_stems_job_makes_four_labelled_marked_wavs():
    store = MemoryStore()
    store.put("takes/j/take-1.wav", stub.render_tone("raw take", 8.0), "audio/wav")
    job = queue.Job(
        id="stems-1",
        kind="stems",
        payload={
            "songId": "song-9",
            "takeIndex": 1,
            "version": 1,
            "sourceKey": "takes/j/take-1.wav",
            "title": "Stems",
            "rights": "user_owned",
        },
        attempts=1,
        max_attempts=3,
    )
    result = main.run_job(job, store, "stub", finisher=Finisher())
    json.dumps(result)  # the queue stores the result as JSON: no numpy types may leak into it
    assert result["model"] == "stub-bands" and result["sumCheck"]["passed"]
    assert set(result["stems"]) == set(stems.NAMES)
    vocals = store.get(result["stems"]["vocals"]["key"])
    assert result["stems"]["vocals"]["contentType"] == "audio/wav"
    detection = watermark.detect(finishing.decode(vocals))
    assert detection.found and detection.payload == watermark.payload_for("song-9", 1)
    found = verify.check(vocals)
    assert found["c2pa"]["beatmind"] and found["c2pa"]["songId"] == "song-9"
