import io
import json
import threading
import wave
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from beatmind_workers import main, queue
from beatmind_workers.acestep import AceStepError, AceStepRunner


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
    assert params["batch_size"] == 2
    assert params["thinking"] is True
    assert AceStepRunner.params({"prompt": "x", "instrumental": True})["lyrics"] == "[Instrumental]"


class FakeAceStep(BaseHTTPRequestHandler):
    fail = False

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
        if self.path == "/release_task":
            assert body["batch_size"] == 2
            self._json({"code": 200, "data": {"task_id": "t1"}})
        elif self.path == "/query_result":
            if FakeAceStep.fail:
                self._json({"code": 200, "data": [{"status": 2, "result": "out of memory"}]})
                return
            files = [{"file": "/v1/audio?path=a.mp3"}, {"file": "/v1/audio?path=b.mp3"}]
            self._json({"code": 200, "data": [{"status": 1, "result": json.dumps(files)}]})

    def do_GET(self):  # noqa: N802 - http.server API
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ID3" + self.path.encode())


@pytest.fixture
def acestep_server():
    server = HTTPServer(("127.0.0.1", 0), FakeAceStep)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()
    FakeAceStep.fail = False


def test_acestep_runner_fetches_both_takes(acestep_server):
    store = FakeStore()
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    result = main.run_job(job({"prompt": "lofi chai", "takes": 2}), store, "acestep", runner)

    assert [t["key"] for t in result["takes"]] == ["takes/job-1/take-1.mp3", "takes/job-1/take-2.mp3"]
    data, content_type = store.objects["takes/job-1/take-2.mp3"]
    assert content_type == "audio/mpeg"
    assert data.startswith(b"ID3")


def test_acestep_failure_raises(acestep_server):
    FakeAceStep.fail = True
    runner = AceStepRunner(acestep_server, poll_seconds=0.01)

    with pytest.raises(AceStepError):
        runner.render({"prompt": "x", "takes": 2})


def test_acestep_url_must_be_http():
    with pytest.raises(ValueError):
        AceStepRunner("file:///etc/passwd")
