"""Does a song plan still match what the creator asked for? (vibe mode with ACE-Step)

ACE-Step's 5Hz LM writes a plan (caption, lyrics, BPM, key) from the creator's description. It is a
small model sampling at temperature 0.85, and it drifts: in the 4 Nov RCA it dropped "Bhojpuri",
"orchestra" and "wedding night" and changed the length. The worker now sends the creator's own
words to the music model, and uses the LM only for the lyrics and the musical metadata. This module
screens each plan before any audio is made, so a plan that drifted is rewritten, not sung.

The checks are deliberately narrow and explainable:

* the plan has words to sing (not empty, not "[Instrumental]");
* the lyrics are in the script of the language asked for;
* the plan does not turn a non-devotional request into a devotional song (the theme reported in
  the RCA), judged on both the LM's caption and its lyrics;
* the second take's lyrics are not a near-copy of the first take's.

They are word and script rules, not a model that understands mood. A full intent classifier needs a
language model we host (TODO.md); these rules catch the failures we have actually seen.
"""

from __future__ import annotations

import re
import unicodedata

# Two different songs share some common words (dil, pyaar, raat). More than this share of distinct
# words in common means the second plan is a rewrite of the first, not a new song.
MAX_LYRIC_OVERLAP = 0.6
# Share of letters that must be in the script of the language asked for.
MIN_SCRIPT_SHARE = 0.85

SECTION_LINE = re.compile(r"^\s*\[[^\]]*\]\s*$", re.MULTILINE)
# Letters plus Devanagari vowel signs and virama, which are marks rather than letters in Unicode.
WORD = re.compile(r"(?:[^\W\d_]|[\u0900-\u0963\u0971-\u097f])+", re.UNICODE)

# Words that make a song devotional. Names like "ram" or "hari" are also everyday names, so a song
# counts as devotional only when it uses at least two different ones.
DEVOTIONAL_WORDS = frozenset(
    {
        # Latin script (Hindi transliterated and English)
        "bhagwan",
        "bhagavan",
        "prabhu",
        "ishwar",
        "bhakti",
        "bhajan",
        "aarti",
        "arti",
        "mandir",
        "puja",
        "pooja",
        "mahadev",
        "shiv",
        "shiva",
        "shankar",
        "krishna",
        "kanha",
        "govind",
        "gopal",
        "ram",
        "raam",
        "hari",
        "mata",
        "maiya",
        "devi",
        "durga",
        "hanuman",
        "bajrang",
        "namah",
        "om",
        "jai",
        "god",
        "lord",
        "pray",
        "prayer",
        "worship",
        "holy",
        "temple",
        "divine",
        "sacred",
        "hymn",
        "hallelujah",
        # Devanagari
        "भगवान",
        "प्रभु",
        "ईश्वर",
        "भक्ति",
        "भजन",
        "आरती",
        "मंदिर",
        "पूजा",
        "महादेव",
        "शिव",
        "शंकर",
        "कृष्ण",
        "कान्हा",
        "गोविंद",
        "गोपाल",
        "राम",
        "हरि",
        "माता",
        "मैया",
        "देवी",
        "दुर्गा",
        "हनुमान",
        "बजरंग",
        "नमः",
        "ॐ",
        "जय",
    }
)
# Caption words the LM uses when it decides a song is devotional.
DEVOTIONAL_STYLE = frozenset(
    {
        "devotional",
        "spiritual",
        "bhajan",
        "kirtan",
        "worship",
        "prayer",
        "hymn",
        "sacred",
        "religious",
        "gospel",
        "chant",
        "chanting",
        "mantra",
        "aarti",
        "qawwali",
    }
)


def words(text: str) -> list[str]:
    """Lower-case words of the lyrics, without section tags like [Verse 1]."""
    body = SECTION_LINE.sub(" ", unicodedata.normalize("NFC", text or ""))
    return [w.lower() for w in WORD.findall(body)]


def script_share(text: str) -> dict[str, float]:
    """Share of letters in Devanagari, Latin and any other script."""
    counts = {"devanagari": 0, "latin": 0, "other": 0}
    for ch in SECTION_LINE.sub(" ", text or ""):
        if not ch.isalpha() and unicodedata.category(ch) not in ("Mn", "Mc"):
            continue
        if "ऀ" <= ch <= "ॿ":
            counts["devanagari"] += 1
        elif ch.isascii() or "À" <= ch <= "ɏ":
            counts["latin"] += 1
        else:
            counts["other"] += 1
    total = sum(counts.values()) or 1
    return {k: v / total for k, v in counts.items()}


def overlap(a: str, b: str) -> float:
    """Jaccard overlap of the distinct words of two lyrics, 0 to 1."""
    wa, wb = set(words(a)), set(words(b))
    if not wa or not wb:
        return 0.0
    return len(wa & wb) / len(wa | wb)


def devotional_hits(text: str) -> set[str]:
    return set(words(text)) & DEVOTIONAL_WORDS


def asks_for_devotional(prompt: str) -> bool:
    found = set(words(prompt))
    return bool(found & DEVOTIONAL_STYLE) or len(found & DEVOTIONAL_WORDS) >= 1


def problems(lyrics: str, caption: str, prompt: str, language: str, earlier: list[str]) -> list[str]:
    """Why this plan does not fit the request; empty when it does."""
    out: list[str] = []
    if not words(lyrics):
        return ["the plan has no lyrics to sing"]
    share = script_share(lyrics)
    if language == "hi":
        # Hindi is shown and sung from Devanagari or from Hindi typed in Latin letters.
        if share["devanagari"] + share["latin"] < MIN_SCRIPT_SHARE:
            out.append(f"lyrics are not in Hindi script ({share['other']:.0%} other scripts)")
    elif share["latin"] < MIN_SCRIPT_SHARE:
        out.append(f"lyrics are not in Latin letters ({share['latin']:.0%} Latin)")
    if not asks_for_devotional(prompt):
        style = set(words(caption)) & DEVOTIONAL_STYLE
        if style:
            out.append(f"the plan made it devotional ({', '.join(sorted(style))}), which was not asked for")
        hits = devotional_hits(lyrics)
        if len(hits) >= 2:
            out.append(f"the lyrics are devotional ({', '.join(sorted(hits))}), which was not asked for")
    for i, other in enumerate(earlier, start=1):
        shared = overlap(lyrics, other)
        if shared > MAX_LYRIC_OVERLAP:
            out.append(f"the lyrics repeat take {i} ({shared:.0%} of words shared)")
    return out
