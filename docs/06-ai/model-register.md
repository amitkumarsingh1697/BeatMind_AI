# AI model register and generation system card

Version 0.1 (Batch 2), 2026-10-28. This document lists every AI model BeatMind plans to use, what each is allowed to do, and what is known about its training data. It also holds the system card for the BeatMind song generator. Licence facts are source-stated until the licence files are read and archived (C-02). No model has been benchmarked by us yet.

## 1. Model register

| ID | Model and version | Publisher | Role | Licence (tag) | Commercial use of outputs | Training data statement (tag) | Hosting | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MDL-01 | ACE-Step 1.5 (Base, Turbo or XL; variant chosen after benchmark) | ACE-Step (StepFun and ACE Studio) | Songs with vocals, beats, extend and repaint | MIT on the Hugging Face card [SS, SRC-106]; Apache-2.0 per ComfyUI Wiki [ES, SRC-107] | "Commercial-Ready" per model card [SS] | Licensed tracks, royalty-free and public-domain music, MIDI-rendered synthetic audio [SS, SRC-106] | Self-hosted GPU | Primary candidate |
| MDL-02 | HeartMuLa-oss-3B | MuLa Labs | Alternative song generator | Apache-2.0 for code and weights since 2026-01-20 [SS, SRC-105] | Not addressed beyond the licence [SS] | Not published [SS] | Self-hosted GPU | Evaluate only |
| MDL-03 | YuE | HKUST and M-A-P | Alternative lyrics-to-song | Apache-2.0 [ES, SRC-104] | Commercial grant noted by SRC-104 [ES] | Not stated [ES] | Self-hosted GPU | Evaluate only |
| MDL-04 | Stable Audio 3.0 Small or Medium | Stability AI | Instrumentals, loops, sound effects | Stability AI Community Licence [ES, SRC-108] | Users own outputs; organisations over US$1M revenue need an enterprise licence [ES] | "Trained entirely on licensed data" [ES, SRC-108] | Self-hosted GPU | Candidate for beat mode |
| MDL-05 | Lyria 3 Pro (API) | Google | Fallback generation; Hindi backstop | Commercial API terms [U, A-027] | To confirm (C-02) | Not published by us [U] | Google API | Fallback with spend cap |
| MDL-06 | Lyric language model (Qwen family or similar Apache-2.0 model) | To be chosen (TD-01) | Lyrics in Hindi, Hinglish, English | Apache-2.0 expected [A] | To confirm | Varies [U] | Self-hosted GPU | Selection in Sprint 1 |
| MDL-07 | Demucs (htdemucs) | Meta (open source) | Stem separation | MIT [A, C-02] | Yes [A] | Not relevant to generation | Self-hosted GPU or CPU | Selected |
| MDL-08 | AudioSeal | Meta (open source) | Watermark embed and detect | MIT [A, C-02] | Yes [A] | Not applicable | Self-hosted | Selected |
| MDL-09 | Moderation classifier for Hindi, Hinglish, English text | To be chosen | Prompt and lyric moderation | To confirm | Not applicable | To confirm | Self-hosted | Selection in Sprint 1 |
| MDL-10 | Language identification on sung lyrics (Whisper-family ASR) | To be chosen | Quality checks: did the song sing the requested language | To confirm | Not applicable | Varies | Self-hosted | Selection in Sprint 2 |
| — | MusicGen | Meta | — | Weights non-commercial (CC-BY-NC) [A, SRC-001] | No | — | — | **Excluded** |
| — | LeVo / SongGeneration | Tencent | — | Academic, research and education use only [ES, SRC-104] | No | — | — | **Excluded** |

### Rules for adding or changing a model

1. Read the licence file in the publisher's official repository; save a dated copy in `docs/06-ai/licences/` with its SHA-256 hash.
2. Record the training data statement exactly as published. If none exists, the model may be used for evaluation only unless the founders accept the risk in writing (Q-F04).
3. Pin weights by SHA-256 and load only `safetensors` files (REQ-SEC-11).
4. Run gate G1 for each language before launch use.
5. Update this register and the risk register in the same pull request.

## 2. System card: BeatMind song generator (MVP)

### 2.1 Intended use

- Creating original songs, instrumentals and lyrics from text descriptions and user lyrics, for personal and commercial use within the plan's rights (PRD section 9).
- Users: adults (18+) in the beta, primarily creators and independent artists in India.

### 2.2 Out-of-scope and prohibited uses

- Imitating a real artist's voice, name or style; reproducing existing songs or lyrics.
- Political deepfakes, harassment, hate, sexual content involving minors.
- Bulk generation for streaming fraud.
- Presenting outputs as human-made where the law or a platform requires AI disclosure.

### 2.3 Known limitations (expected; to be measured)

| Limitation | Expected effect | How measured |
| --- | --- | --- |
| Quality likely below Suno v5.5 and Lyria 3 | Some takes unusable | Gate G1 blind tests (METRIC-04) |
| Hindi and regional pronunciation | Mispronounced words | Native-speaker panel; ASR language check (MDL-10) |
| Indian classical forms (raga, tala, ornamentation) | Western harmony dominates | Listening panel notes; not promised in MVP |
| Separated stems contain bleed | Imperfect stems | SDR on test set (TC-REQ-F-23-03) |
| Possible similarity to training material | Infringement risk | Similarity screen (REQ-F-52); incident rate (METRIC-06) |

### 2.4 Safeguards

Moderation before generation; artist and title block lists; similarity screen after generation; watermark, C2PA and ledger on every output; daily caps; takedown and appeal paths. Details in the [threat model](../04-security/threat-model.md).

### 2.5 Evaluation plan

| Evaluation | Method | Pass mark | When |
| --- | --- | --- | --- |
| Language quality (G1) | 30 or more native listeners per language, blind pairwise against best competitor, creator background music prompts | BeatMind preferred or tied in at least 40% of pairs | Before beta, per language |
| Prompt adherence | Tempo and key detection on outputs versus requested values | Tempo within ±3 BPM in 90% of takes; key correct in 80% [H, to set after first benchmark] | Sprint 2 onward, nightly |
| Technical quality | Clipping, silence, length, loudness checks | 99% of takes pass | Every take in production |
| Similarity | Labelled test set of near-copies and originals | Targets set in TD-03 | Before beta |
| Safety | Red-team prompt set in Hindi, Hinglish and English | No prohibited output passes moderation in the test set | Before beta and monthly |

### 2.6 Change log

| Date | Change |
| --- | --- |
| 2026-10-28 | First version |
