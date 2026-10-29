import csv
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "bench"))
sys.path.insert(0, str(ROOT / "spikes" / "hindi"))

import score  # noqa: E402
from acestep_client import AceStepClient  # noqa: E402


class FakeAceStep(BaseHTTPRequestHandler):
    """Mimics the two ACE-Step endpoints the client uses: queued once, then succeeded."""

    polls = 0

    def do_POST(self):  # noqa: N802
        length = int(self.headers["Content-Length"])
        json.loads(self.rfile.read(length))
        if self.path == "/release_task":
            data = {"task_id": "t1", "status": "queued"}
        else:
            FakeAceStep.polls += 1
            done = FakeAceStep.polls > 1
            result = json.dumps([{"file": "/v1/audio?path=a.mp3", "dit_model": "turbo", "lm_model": "lm"}])
            data = [{"task_id": "t1", "status": 1 if done else 0, "result": result if done else ""}]
        body = json.dumps({"data": data, "code": 200, "error": None}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def test_client_waits_for_task_and_reports_models():
    server = HTTPServer(("127.0.0.1", 0), FakeAceStep)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = AceStepClient(f"http://127.0.0.1:{server.server_port}")
        result = client.generate({"prompt": "x"}, poll_seconds=0.01)
    finally:
        server.shutdown()

    assert result.ok
    assert result.files == ["/v1/audio?path=a.mp3"]
    assert result.dit_model == "turbo"


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def test_score_applies_pass_marks_per_language(tmp_path):
    write_csv(
        tmp_path / "key.csv",
        ["code", "set", "prompt_id", "seconds"],
        [["a", "hindi", "H01", 1], ["b", "hindi", "H02", 1], ["c", "hinglish", "G01", 1]],
    )
    score_header = [
        "code",
        "pronunciation_1to5",
        "musicality_1to5",
        "usable_for_creator_video_yes_no",
        "notes",
    ]
    for n in (1, 2, 3):
        write_csv(
            tmp_path / f"sheet-listener-{n}.csv",
            score_header,
            [["a", 4, 4, "yes", ""], ["b", 4, 3, "yes" if n < 3 else "no", ""], ["c", 2, 3, "no", ""]],
        )

    result = score.summarise(tmp_path)

    assert result["hindi"]["go"] is True
    assert result["hindi"]["usable_share"] == 1.0
    assert result["hinglish"]["go"] is False
    assert result["hinglish"]["mean_pronunciation"] == 2
