"""US-029: the similarity screen withholds near-copies of indexed recordings (TC-REQ-F-52-01)."""

import io
import subprocess
import wave

import numpy as np
import pytest

from beatmind_workers import main, queue, similarity, stub

RATE = 22_050


def music(seed: int, seconds: float = 20.0) -> np.ndarray:
    """A synthetic tune: random notes with harmonics, a drum-like click and some noise."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(seconds * RATE), dtype=np.float32)
    t_note = 0
    while t_note < len(out):
        length = int(rng.uniform(0.15, 0.5) * RATE)
        freq = 110 * 2 ** (rng.integers(0, 36) / 12)
        tt = np.arange(min(length, len(out) - t_note)) / RATE
        env = np.exp(-3 * tt)
        tone = sum(np.sin(2 * np.pi * freq * k * tt) / k for k in (1, 2, 3))
        out[t_note : t_note + len(tt)] += 0.3 * env * tone
        out[t_note : t_note + min(200, len(tt))] += rng.normal(0, 0.3, min(200, len(tt)))
        t_note += length
    out += rng.normal(0, 0.01, len(out)).astype(np.float32)
    return np.clip(out / np.abs(out).max() * 0.8, -1, 1)


def wav(samples: np.ndarray) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((samples * 32767).astype("<i2").tobytes())
    return buf.getvalue()


def mp3(data: bytes, kbps: int = 128) -> bytes:
    return subprocess.run(
        ["ffmpeg", "-v", "error", "-i", "pipe:0", "-f", "mp3", "-b:a", f"{kbps}k", "pipe:1"],
        input=data,
        capture_output=True,
        check=True,
    ).stdout


@pytest.fixture
def screen(conn):
    similarity.add_reference(conn, wav(music(1)), "Known song one", "Some artist", "test catalogue")
    similarity.add_reference(conn, wav(music(2)), "Known song two", None, "test catalogue")
    return similarity.Screen(conn)


@pytest.mark.integration
@pytest.mark.tc("TC-REQ-F-52-01")
def test_copy_reencoded_as_mp3_is_withheld(screen):
    result = screen.check(mp3(wav(music(1))))
    assert result["withheld"] is True
    assert result["referenceTitle"] == "Known song one"
    assert result["score"] > 0.2


@pytest.mark.integration
@pytest.mark.tc("TC-REQ-F-52-01")
def test_excerpt_quieter_and_shifted_is_withheld(screen):
    excerpt = music(2)[int(5.3 * RATE) : int(15 * RATE)] * 0.4
    result = screen.check(wav(excerpt))
    assert result["withheld"] is True
    assert result["referenceTitle"] == "Known song two"


@pytest.mark.integration
@pytest.mark.tc("TC-REQ-F-52-01")
def test_new_music_passes(screen):
    for seed in (3, 4, 5, 6):
        result = screen.check(wav(music(seed)))
        assert result["withheld"] is False, (seed, result)
        assert result["score"] < similarity.MIN_SCORE


@pytest.mark.integration
def test_stub_tone_and_silence_pass(screen):
    assert screen.check(stub.render_tone("monsoon"))["withheld"] is False
    silent = screen.check(wav(np.zeros(RATE * 3, dtype=np.float32)))
    assert silent["withheld"] is False
    assert silent["landmarks"] == 0


@pytest.mark.integration
def test_empty_index_passes_everything(conn):
    assert similarity.Screen(conn).check(wav(music(1)))["withheld"] is False


@pytest.mark.integration
def test_worker_marks_a_matching_take_withheld_but_keeps_its_files(conn):
    similarity.add_reference(conn, stub.render_tone("same tune#take2"), "Indexed tone", None, "test")

    class Store:
        def __init__(self):
            self.keys = []

        def put(self, key, data, content_type):
            self.keys.append(key)
            return key

    store = Store()
    job = queue.Job(id="job-9", kind="song", payload={"prompt": "same tune"}, attempts=1, max_attempts=3)
    result = main.run_job(job, store, "stub", screen=similarity.Screen(conn, min_matched=5, min_score=0.05))
    first, second = result["takes"]
    assert "withheld" not in first and first["similarity"]["checked"] is True
    assert second["withheld"] is True
    assert second["withheldReason"] == "similarity"
    assert second["similarity"]["referenceTitle"] == "Indexed tone"
    assert "takes/job-9/take-2.wav" in store.keys
