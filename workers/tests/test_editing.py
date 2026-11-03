"""US-038: the editor's signal processing, the seam check, and an edit job end to end."""

import shutil

import numpy as np
import pytest

from beatmind_workers import editing, finishing, main, queue, stub, verify, watermark
from beatmind_workers.finishing import Finisher

RATE = editing.RATE


def music(seconds=20.0, seed=1):
    """A stand-in for a song: a few chords with a little noise, so steps look like real audio."""
    rng = np.random.default_rng(seed)
    t = np.arange(int(seconds * RATE)) / RATE
    tone = sum(0.12 * np.sin(2 * np.pi * f * t) for f in (220.0, 277.2, 329.6, 440.0))
    mono = tone + 0.01 * rng.standard_normal(len(t))
    return np.stack([mono, mono * 0.9], axis=1).astype(np.float32)


def render(seconds, seed):
    t = np.arange(int(seconds * RATE)) / RATE
    mono = sum(0.12 * np.sin(2 * np.pi * f * t) for f in (196.0, 246.9, 293.7, 392.0))
    return np.stack([mono, mono], axis=1).astype(np.float32)


def test_trim_keeps_the_section_without_clicks():
    out = editing.trim(music(), 2.0, 12.0).samples
    assert len(out) == 10 * RATE
    assert abs(out[0]).max() < 1e-3 and abs(out[-1]).max() < 1e-3
    with pytest.raises(ValueError):
        editing.trim(music(), 5.0, 5.5)


def test_fades_start_and_end_silent_and_leave_the_middle():
    src = music()
    out = editing.fade(src, 2.0, 3.0).samples
    assert abs(out[0]).max() == 0
    assert abs(out[-1]).max() < 1e-4
    mid = slice(8 * RATE, 9 * RATE)
    assert np.allclose(out[mid], src[mid])


@pytest.mark.tc("TC-REQ-F-10-02")
def test_ai_seams_pass_and_a_hard_splice_fails():
    src = music()
    extended = editing.extend(src, 10.0, render, "seed")
    assert len(extended.samples) == pytest.approx(30 * RATE, abs=RATE * 0.01)
    assert editing.seam_check(extended.samples, extended.seams)["passed"]

    section = editing.regenerate(src, 6.0, 12.0, render, "seed")
    assert len(section.seams) == 2
    assert abs(len(section.samples) - len(src)) < 0.02 * RATE
    check = editing.seam_check(section.samples, section.seams)
    assert check["passed"], check

    # A splice with no crossfade into audio at another level jumps at the join: the check must catch it.
    spliced = np.concatenate([src[: 10 * RATE], render(10.0, "x") + 0.5])
    bad = editing.seam_check(spliced, [10.0])
    assert not bad["passed"] and bad["worstRatio"] > editing.SEAM_LIMIT


class MemoryStore:
    def __init__(self):
        self.objects = {}

    def put(self, key, data, content_type):
        self.objects[key] = data
        return key

    def get(self, key):
        return self.objects[key]

    def delete(self, key):
        self.objects.pop(key, None)


needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


@needs_ffmpeg
def test_an_edit_job_makes_finished_files_with_one_watermark_and_credentials():
    store = MemoryStore()
    store.put("takes/j/take-1.wav", stub.render_tone("raw take", 12.0), "audio/wav")
    job = queue.Job(
        id="edit-1",
        kind="edit",
        payload={
            "songId": "song-7",
            "takeIndex": 1,
            "version": 2,
            "op": "regenerate",
            "params": {"start": 3, "end": 7},
            "sourceKey": "takes/j/take-1.wav",
            "title": "Edited",
            "rights": "user_owned",
            "prompt": "lofi",
        },
        attempts=1,
        max_attempts=3,
    )
    result = main.run_job(job, store, "stub", finisher=Finisher())
    take = result["takes"][0]
    assert set(take["assets"]) == {"mp3_192", "mp3_320", "wav"}
    assert take["seam"]["passed"], take["seam"]
    assert take["rawKey"] in store.objects
    wav = store.get(take["assets"]["wav"]["key"])
    detection = watermark.detect(finishing.decode(wav))
    assert detection.found and detection.payload == watermark.payload_for("song-7", 1)

    # US-021: the verify check finds the mark and our credentials, including the version.
    found = verify.check(store.get(take["assets"]["mp3_192"]["key"]))
    assert found["format"] == "mp3"
    assert found["watermark"]["found"] and found["watermark"]["payload"] == detection.payload
    assert found["c2pa"]["beatmind"] and found["c2pa"]["aiGenerated"] and found["c2pa"]["songId"] == "song-7"


@needs_ffmpeg
def test_verify_reads_an_unmarked_file_as_not_ours_and_deletes_the_upload():
    store = MemoryStore()
    store.put("verify/1", finishing.encode(music(12.0), "mp3_192", {}), "application/octet-stream")
    job = queue.Job(id="v", kind="verify", payload={"uploadKey": "verify/1"}, attempts=1, max_attempts=3)
    result = main.run_job(job, store, "stub")["verify"]
    assert not result["watermark"]["found"]
    assert result["c2pa"] == {"present": False}
    assert "verify/1" not in store.objects
    garbage = verify.check(b"not audio at all")
    assert garbage["watermark"].get("decodable") is False and garbage["format"] is None
