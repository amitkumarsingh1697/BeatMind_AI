"""Finishing (REQ-F-30, REQ-F-20, REQ-F-23): watermark, AI label, C2PA and the three download files."""

import json
import shutil
import subprocess

import numpy as np
import pytest

from beatmind_workers import finishing, main, queue, signing, stub, watermark
from beatmind_workers.finishing import Finisher

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


class FakeStore:
    def __init__(self):
        self.objects = {}

    def put(self, key, data, content_type):
        self.objects[key] = (data, content_type)
        return key


@pytest.fixture(scope="module")
def finisher():
    return Finisher()


@pytest.fixture(scope="module")
def finished(finisher):
    payload = {"songId": "song-1", "title": "रात का सफ़र", "rights": "bm_owned", "prompt": "x"}
    return finisher.finish(stub.render_tone("raat ka safar"), payload, 1)


def probe_tags(data: bytes, suffix: str, tmp_path) -> dict:
    path = tmp_path / f"f.{suffix}"
    path.write_bytes(data)
    out = subprocess.run(
        [
            "ffprobe",
            "-loglevel",
            "error",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            str(path),
        ],
        capture_output=True,
        check=True,
    ).stdout
    return json.loads(out)


@pytest.mark.tc("TC-REQ-F-30-01")
@pytest.mark.parametrize("name,fmt", [("mp3_192", "mp3"), ("mp3_320", "mp3"), ("wav", "wav")])
def test_every_file_has_label_c2pa_and_watermark(finished, name, fmt, tmp_path):
    asset = finished.assets[name]

    manifest, state = signing.read_manifest(asset.data, fmt)
    assert state == "Valid"  # signature checks out; the development signer is simply not trusted
    actions = next(a for a in manifest["assertions"] if a["label"].startswith("c2pa.actions"))
    assert actions["data"]["actions"][0]["digitalSourceType"] == signing.TRAINED_ALGORITHMIC_MEDIA
    ours = next(a for a in manifest["assertions"] if a["label"] == "ai.beatmind.provenance")
    assert ours["data"] == {
        "songId": "song-1",
        "take": 1,
        "watermarkId": finished.watermark_id,
        "label": "AI-generated",
        "rights": "bm_owned",
    }

    detection = watermark.detect(finishing.decode(asset.data))
    assert detection.found
    assert detection.payload == finished.watermark_id == watermark.payload_for("song-1", 1)

    tags = probe_tags(asset.data, fmt, tmp_path)["format"]["tags"]
    assert tags["comment"] == "AI-generated. Made with BeatMind AI."
    assert tags["title"] == "रात का सफ़र (take 1)"


@pytest.mark.tc("TC-REQ-F-20-01")
def test_free_mp3_is_192_kbps_with_attribution(finished, tmp_path):
    info = probe_tags(finished.assets["mp3_192"].data, "mp3", tmp_path)
    assert info["streams"][0]["bit_rate"] == "192000"
    tags = info["format"]["tags"]
    assert "Made with BeatMind AI" in tags["comment"]
    assert tags["AI_GENERATED"] == "true"
    assert tags["copyright"] == "BeatMind AI. Personal listening only."


@pytest.mark.tc("TC-REQ-F-23-01")
def test_paid_files_are_mp3_320_and_wav_48k_24bit(finished, tmp_path):
    mp3 = probe_tags(finished.assets["mp3_320"].data, "mp3", tmp_path)["streams"][0]
    wav = probe_tags(finished.assets["wav"].data, "wav", tmp_path)["streams"][0]
    assert mp3["bit_rate"] == "320000"
    assert wav["sample_rate"] == "48000"
    assert wav["bits_per_sample"] == 24


def test_unmarked_audio_is_not_detected():
    t = np.arange(watermark.SAMPLE_RATE * 3) / watermark.SAMPLE_RATE
    music = (0.3 * np.sin(2 * np.pi * 330 * t)).astype(np.float32)
    assert not watermark.detect(music).found
    assert watermark.detect(watermark.embed(music, 0xC0FFEE42)).payload == 0xC0FFEE42


def test_watermark_survives_a_low_bitrate_reencode(finished):
    reencoded = finishing._ffmpeg(
        ["-i", "pipe:0", "-c:a", "libmp3lame", "-b:a", "128k", "-f", "mp3", "pipe:1"],
        finished.assets["wav"].data,
    )
    assert watermark.detect(finishing.decode(reencoded)).payload == finished.watermark_id


def test_tampered_file_fails_c2pa_validation(finished):
    data = bytearray(finished.assets["wav"].data)
    data[len(data) // 2] ^= 0xFF  # change one audio byte after signing
    _, state = signing.read_manifest(bytes(data), "wav")
    assert state == "Invalid"


def test_run_job_stores_three_labelled_files_per_take(finisher):
    store = FakeStore()
    job = queue.Job(
        id="job-9",
        kind="song",
        payload={"prompt": "x", "songId": "s9", "rights": "user_owned"},
        attempts=1,
        max_attempts=3,
    )

    result = main.run_job(job, store, "stub", finisher=finisher)

    assert len(result["takes"]) == 2
    first = result["takes"][0]
    assert set(first["assets"]) == {"mp3_192", "mp3_320", "wav"}
    assert first["assets"]["mp3_192"]["key"] == "takes/job-9/take-1-192.mp3"
    assert first["c2pa"] is True
    assert first["watermarkId"] == watermark.payload_for("s9", 1)
    assert store.objects["takes/job-9/take-1-master.wav"][1] == "audio/wav"
    assert result["takes"][1]["watermarkId"] != first["watermarkId"]
