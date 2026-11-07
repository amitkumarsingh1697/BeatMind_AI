import numpy as np

from beatmind_workers import diversity
from beatmind_workers.similarity import SAMPLE_RATE

C_MAJOR = [(261.6, 329.6, 392.0), (349.2, 440.0, 523.3), (392.0, 493.9, 587.3), (261.6, 329.6, 392.0)]
E_FLAT = [(311.1, 392.0, 466.2), (207.7, 261.6, 311.1), (233.1, 293.7, 349.2), (311.1, 392.0, 466.2)]


def song(chords, seconds=16, seed=0, phase=0.0):
    rng = np.random.default_rng(seed)
    t = np.arange(SAMPLE_RATE * seconds) / SAMPLE_RATE
    out = np.zeros_like(t)
    for k, chord in enumerate(chords * seconds):
        part = (t >= k) & (t < k + 1)
        for f in chord:
            out[part] += np.sin(2 * np.pi * f * t[part] + phase)
    return (out / np.abs(out).max() * 0.5 + rng.normal(0, 0.02, len(t))).astype(np.float32)


def test_the_same_recording_is_a_duplicate():
    a = song(C_MAJOR)
    c = diversity.compare_samples(a, a.copy())
    assert c.waveform > 0.99 and c.duplicate


def test_the_same_chords_in_a_new_recording_are_a_duplicate():
    # What the RCA heard: different bytes and waveforms, the same harmony second by second.
    c = diversity.compare_samples(song(C_MAJOR, seed=1, phase=0.0), song(C_MAJOR, seed=2, phase=1.3))
    assert c.waveform < diversity.WAVEFORM_DUPLICATE
    assert c.harmony >= diversity.HARMONY_DUPLICATE and c.duplicate


def test_the_same_song_a_few_seconds_later_is_still_a_duplicate():
    a = song(C_MAJOR, seed=1)
    late = np.concatenate([np.zeros(SAMPLE_RATE * 3, dtype=np.float32), a])
    assert diversity.compare_samples(a, late).duplicate


def test_a_different_progression_is_a_different_song():
    c = diversity.compare_samples(song(C_MAJOR, seed=1), song(E_FLAT, seed=2))
    assert not c.duplicate
    assert c.as_dict()["duplicate"] is False
