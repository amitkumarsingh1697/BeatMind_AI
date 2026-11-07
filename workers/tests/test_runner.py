import io
import json
import threading
import wave
from http.server import BaseHTTPRequestHandler, HTTPServer

import numpy as np
import pytest

from beatmind_workers import main, queue
from beatmind_workers.acestep import AceStepError, AceStepRunner, sample_query


class FakeStore:
    def __init__(self):
        self.objects = {}

    def put(self, key, data, content_type):
        self.objects[key] = (data, content_type)
        return key


def job(payload):
    return queue.Job(id="job-1", kind="song", payload=payload, attempts=1, max_attempts=3)


def test_stub_stores_two_distinct_takes_by_default():
    store = FakeStore()

    result = main.run_job(job({"prompt": "monsoon bhangra"}), store, "stub")

    assert [t["key"] for t in result["takes"]] == ["takes/job-1/take-1.wav", "takes/job-1/take-2.wav"]
    one, two = (store.objects[t["key"]][0] for t in result["takes"])
    assert one != two
    for data in (one, two):
        with wave.open(io.BytesIO(data)) as w:
            assert w.getnframes() > 0


def test_unknown_runner_fails_the_job():
    with pytest.raises(ValueError):
        main.run_job(job({"prompt": "x"}), FakeStore(), "magic")


def test_acestep_params_map_the_job():
    params = AceStepRunner.params(
        {
            "prompt": "lofi chai",
            "language": "hinglish",
            "durationSeconds": 90,
            "takes": 2,
            "instrumental": False,
        }
    )

    assert params["vocal_language"] == "hi"
    assert params["audio_duration"] == 90.0
    # One take per request: a batch shares one plan, which made both takes the same song.
    assert params["batch_size"] == 1
    assert params["thinking"] is True
    assert params["use_cot_caption"] is False
    assert params["use_cot_language"] is False
    assert "sample_mode" not in params
    instrumental = AceStepRunner.params({"prompt": "x", "instrumental": True})
    assert instrumental["lyrics"] == "[Instrumental]"
    assert "sample_mode" not in instrumental


WEDDING = {
    "prompt": "Hindi romantic song for the wedding first night. Bhojpuri music vibe with orchestra",
    "vocalType": "duet",
    "language": "hi",
    "durationSeconds": 120,
    "instrumental": False,
    "takes": 2,
}


@pytest.mark.tc("TC-REQ-F-05-02")
def test_acestep_vibe_take_sings_the_plan_but_keeps_the_request_as_written():
    # RCA 4 Nov: sample mode and the CoT caption replaced the request with the LM's reading of it,
    # dropping Bhojpuri, orchestra and the length. Now only the lyrics and metre come from the plan.
    plan = {"lyrics": "[Verse 1]\nतेरी आँखों में सपने", "bpm": 92, "keyscale": "D minor", "timesignature": "4"}
    params = AceStepRunner.params(WEDDING, plan, seed=1234)

    assert params["prompt"] == (
        "Hindi romantic song for the wedding first night. Bhojpuri music vibe with orchestra, "
        "male and female duet vocals"
    )
    assert params["lyrics"] == plan["lyrics"]
    assert params["audio_duration"] == 120.0
    assert params["vocal_language"] == "hi"
    assert params["thinking"] is True
    assert params["use_cot_caption"] is False and params["use_cot_language"] is False
    assert params["use_random_seed"] is False and params["seed"] == 1234
    assert (params["bpm"], params["key_scale"], params["time_signature"]) == (92, "D minor", "4")
    assert "sample_mode" not in params and "sample_query" not in params


def test_sample_query_describes_the_song_for_the_plan():
    assert sample_query("wedding duet", "hi", 120) == "wedding duet, sung in Hindi, about 2 minutes long"


ROMANTIC_1 = "[Verse 1]\nतेरी आँखों में सपने हैं\nदिल की धड़कन तेरे नाम\n[Chorus]\nसाजन मेरे पास आ\nसात जनम का वादा"
ROMANTIC_2 = "[Verse 1]\nचाँदनी रात सुहानी है\nमहकी महकी हवा चले\n[Chorus]\nबाहों में भर ले मुझे\nधीरे धीरे पास आ"
DEVOTIONAL = "[Verse 1]\nजय जय शिव शंकर\nहरि का नाम जपो\n[Chorus]\nप्रभु तेरी भक्ति\nमंदिर में दीप जले"


def tone(freqs, seconds=12.0, seed=0, rate=22050):
    """A WAV file: each second plays the next chord of ``freqs`` with a little noise."""
    rng = np.random.default_rng(seed)
    t = np.arange(int(rate * seconds)) / rate
    signal = np.zeros_like(t)
    for k, chord in enumerate(freqs * int(seconds)):
        part = (t >= k) & (t < k + 1)
        for f in chord:
            signal[part] += np.sin(2 * np.pi * f * t[part])
    signal = signal / np.abs(signal).max() * 0.5 + rng.normal(0, 0.02, len(t))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((signal * 32767).astype("<i2").tobytes())
    return buf.getvalue()


C_MAJOR = [(261.6, 329.6, 392.0), (349.2, 440.0, 523.3), (392.0, 493.9, 587.3), (261.6, 329.6, 392.0)]
E_FLAT = [(311.1, 392.0, 466.2), (207.7, 261.6, 311.1), (233.1, 293.7, 349.2), (311.1, 392.0, 466.2)]


class FakeAceStep(BaseHTTPRequestHandler):
    fail = False
    no_file = False
    plans: list = []
    audio: list = []
    requests: list = []
    samples = 0
    tasks = 0

    def log_message(self, *args):
        pass

    def _json(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):  # noqa: N802 - http.server API
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        cls = FakeAceStep
        if self.path == "/v1/create_sample":
            cls.requests.append(("create_sample", body))
            plan = cls.plans[min(cls.samples, len(cls.plans) - 1)] if cls.plans else {}
            cls.samples += 1
            self._json(
                {
                    "code": 200,
                    "data": dict(
                        {
                            "caption": "romantic duet",
                            "bpm": 90 + cls.samples,
                            "keyscale": "C major",
                            "duration": 125,
                        },
                        **plan,
                    ),
                }
            )
        elif self.path == "/release_task":
            cls.requests.append(("release_task", body))
            assert body["batch_size"] == 1
            cls.tasks += 1
            self._json({"code": 200, "data": {"task_id": f"t{cls.tasks}"}})
        elif self.path == "/query_result":
            if cls.fail:
                self._json({"code": 200, "data": [{"status": 2, "result": "out of memory"}]})
                return
            task = body["task_id_list"][0]
            files = [{"file": f"/v1/audio?path={task}.mp3", "seed_value": "1", "dit_model": "turbo"}]
            if cls.no_file:
                files = [{"file": ""}]
            self._json({"code": 200, "data": [{"status": 1, "result": json.dumps(files)}]})

    def do_GET(self):  # noqa: N802 - http.server API
        n = int(self.path.split("path=t")[1].split(".")[0])
        self.send_response(200)
        self.end_headers()
        audio = FakeAceStep.audio
        self.wfile.write(audio[min(n, len(audio)) - 1] if audio else b"ID3" + self.path.encode())


@pytest.fixture
def acestep_server():
    FakeAceStep.plans, FakeAceStep.audio, FakeAceStep.requests = [], [], []
    FakeAceStep.samples = FakeAceStep.tasks = 0
    server = HTTPServer(("127.0.0.1", 0), FakeAceStep)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()
    FakeAceStep.fail = False
    FakeAceStep.no_file = False


def sent(kind):
    return [body for k, body in FakeAceStep.requests if k == kind]


def test_acestep_runner_fetches_both_takes(acestep_server):
    store = FakeStore()
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    payload = {"prompt": "lofi chai", "lyrics": "my words", "takes": 2}
    result = main.run_job(job(payload), store, "acestep", runner)

    assert [t["key"] for t in result["takes"]] == ["takes/job-1/take-1.mp3", "takes/job-1/take-2.mp3"]
    data, content_type = store.objects["takes/job-1/take-2.mp3"]
    assert content_type == "audio/mpeg"
    assert data.startswith(b"ID3")
    # Typed lyrics need no plan; each take is still its own request with its own seed.
    assert sent("create_sample") == []
    seeds = [b["seed"] for b in sent("release_task")]
    assert len(seeds) == 2 and seeds[0] != seeds[1]


@pytest.mark.tc("TC-REQ-F-05-02")
def test_acestep_vibe_takes_are_two_compositions(acestep_server):
    FakeAceStep.plans = [{"lyrics": ROMANTIC_1}, {"lyrics": ROMANTIC_2}]
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    result = main.run_job(job(WEDDING), FakeStore(), "acestep", runner)

    assert len(sent("create_sample")) == 2  # a plan per take, not one shared plan
    assert sent("create_sample")[0]["vocal_language"] == "hi"
    bodies = sent("release_task")
    assert [b["lyrics"] for b in bodies] == [ROMANTIC_1, ROMANTIC_2]
    assert bodies[0]["seed"] != bodies[1]["seed"]
    assert bodies[0]["bpm"] != bodies[1]["bpm"]
    assert all("Bhojpuri music vibe with orchestra" in b["prompt"] for b in bodies)
    assert all(b["audio_duration"] == 120.0 for b in bodies)
    take_one, take_two = result["takes"]
    assert (take_one["lyrics"], take_two["lyrics"]) == (ROMANTIC_1, ROMANTIC_2)
    assert result["lyrics"] == ROMANTIC_1
    # The audit trail the RCA could not find: what was sent and what the LM planned.
    assert take_two["generation"]["seed"] == bodies[1]["seed"]
    assert take_two["generation"]["captionSent"] == bodies[1]["prompt"]
    assert take_two["generation"]["plan"]["caption"] == "romantic duet"
    assert take_two["generation"]["plan"]["lmDuration"] == 125


@pytest.mark.tc("TC-REQ-F-05-02")
def test_acestep_rewrites_a_plan_that_turned_the_song_devotional(acestep_server):
    FakeAceStep.plans = [
        {"lyrics": DEVOTIONAL, "caption": "devotional bhajan, harmonium"},
        {"lyrics": ROMANTIC_1},
        {"lyrics": ROMANTIC_1},  # a copy of take 1: written again
        {"lyrics": ROMANTIC_2},
    ]
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    takes = runner.render(WEDDING)

    assert [t.lyrics for t in takes] == [ROMANTIC_1, ROMANTIC_2]
    first, second = (t.meta["plan"] for t in takes)
    assert first["attempts"] == 2 and "devotional" in first["rejected"][0]["problems"][0]
    assert second["attempts"] == 2 and "repeat take 1" in second["rejected"][0]["problems"][0]


def test_acestep_fails_rather_than_sing_a_plan_that_never_fits(acestep_server):
    FakeAceStep.plans = [{"lyrics": "[Instrumental]"}]
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    with pytest.raises(AceStepError, match="no song plan that fits"):
        runner.render(WEDDING)
    assert sent("release_task") == []


def test_acestep_instrumental_result_has_no_lyrics(acestep_server):
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    payload = {"prompt": "lofi", "instrumental": True, "takes": 2}
    result = main.run_job(job(payload), FakeStore(), "acestep", runner)
    assert "lyrics" not in result
    assert sent("create_sample") == []


@pytest.mark.tc("TC-REQ-F-05-02")
def test_a_take_that_duplicates_the_other_is_made_again(acestep_server):
    same = tone(C_MAJOR, seed=1)
    third = "[Verse 1]\nगोरी तेरा गाँव बड़ा प्यारा\nनदिया किनारे\n[Chorus]\nमैं तो गया मारा\nआ के यहाँ रे"
    FakeAceStep.plans = [{"lyrics": ROMANTIC_1}, {"lyrics": ROMANTIC_2}, {"lyrics": third}]
    FakeAceStep.audio = [same, tone(C_MAJOR, seed=2), tone(E_FLAT, seed=3)]
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    result = main.run_job(job(dict(WEDDING, takes=2)), FakeStore(), "acestep", runner)

    assert len(sent("release_task")) == 3
    check = result["takes"][1]["diversity"]
    assert check["remade"] is True and check["duplicate"] is False
    assert not result["takes"][1].get("withheld")


@pytest.mark.tc("TC-REQ-F-05-02")
def test_a_take_that_is_still_a_duplicate_is_withheld_and_refunded(acestep_server):
    FakeAceStep.plans = [{"lyrics": ROMANTIC_1}, {"lyrics": ROMANTIC_2}]
    FakeAceStep.audio = [tone(C_MAJOR, seed=1)]  # every render sounds the same
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    result = main.run_job(job(dict(WEDDING, takes=2)), FakeStore(), "acestep", runner)

    two = result["takes"][1]
    assert two["withheld"] is True and two["withheldReason"] == "duplicate_take"
    assert two["diversity"]["duplicate"] is True
    assert not result["takes"][0].get("withheld")


def test_acestep_failure_raises(acestep_server):
    FakeAceStep.fail = True
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    with pytest.raises(AceStepError):
        runner.render({"prompt": "x", "lyrics": "la la", "takes": 2})


def test_acestep_success_without_a_file_names_the_cause(acestep_server):
    FakeAceStep.no_file = True
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    with pytest.raises(AceStepError, match="without an audio file"):
        runner.render({"prompt": "x", "lyrics": "la la", "takes": 2})


def test_acestep_url_must_be_http():
    with pytest.raises(ValueError):
        AceStepRunner("file:///etc/passwd")


def test_acestep_params_sing_custom_lyrics_with_the_vocal_type():
    params = AceStepRunner.params(
        {
            "mode": "lyrics",
            "prompt": "slow ballad",
            "lyrics": "Raat ka safar",
            "vocalType": "female",
            "language": "hinglish",
        }
    )

    assert params["prompt"] == "slow ballad, female vocals"
    assert params["lyrics"] == "Raat ka safar"
    assert params["thinking"] is False
    assert "sample_mode" not in params


LONG_TAIL = [
    "[Chorus]",
    "ओ सजना",
    "ओ सजनी",
    "साथ रहें",
    "हम दोनों",
    "[Verse 2]",
    "नई सुबह",
    "नया सवेरा",
    "नया घर",
    "नया बसेरा",
]


def test_a_plan_written_for_a_longer_song_is_cut_to_fit(acestep_server):
    # On the Mac the LM wrote lyrics for 125 to 213 s while the take is 60 or 120 s long.
    long_one = "\n".join(["[Verse 1]", "चाँद तारे", "रात सुहानी", "तेरा मेरा", "प्यार पुराना"] + LONG_TAIL)
    long_two = "\n".join(["[Verse 1]", "मेहंदी लगी", "हाथों में", "डोली सजी", "बारात चली"] + LONG_TAIL)
    FakeAceStep.plans = [{"lyrics": long_one, "duration": 360}, {"lyrics": long_two, "duration": 360}]
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    takes = runner.render(WEDDING)

    assert takes[0].lyrics == "\n".join(long_one.splitlines()[:5])
    plan = takes[0].meta["plan"]
    assert plan["lyricsLinesSung"] < plan["lyricsLinesWritten"]
    assert sent("release_task")[0]["lyrics"] == takes[0].lyrics
