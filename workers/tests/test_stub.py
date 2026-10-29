import io
import wave

from beatmind_workers import stub


def test_renders_valid_wav_of_requested_length():
    audio = stub.render_tone("monsoon romance", seconds=1.0)

    with wave.open(io.BytesIO(audio)) as w:
        assert w.getnchannels() == 1
        assert w.getframerate() == stub.SAMPLE_RATE
        assert w.getnframes() == stub.SAMPLE_RATE


def test_same_prompt_gives_same_audio():
    assert stub.render_tone("a") == stub.render_tone("a")
