"""Build beatmind-pitch-deck-v2.pptx from the slide script in pitch-deck.md.

Run: python3 build_deck.py
"""
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.util import Inches, Pt

HERE = Path(__file__).parent
NAVY = RGBColor(0x1F, 0x3A, 0x5F)
ACCENT = RGBColor(0xE8, 0x6A, 0x33)
INK = RGBColor(0x22, 0x22, 0x22)
GREY = RGBColor(0x6B, 0x6B, 0x6B)
PLACEHOLDER = RGBColor(0xB0, 0x3A, 0x2E)

SLIDES = [
    ("Problem", [
        "Creators in India need original music for videos every week.",
        "AI music costs Indians about the US price: Suno Pro ₹999/month, 5x Spotify Premium's ₹199 (Oct 2026)",
        "Leading tools lack native Hindi; Google's Lyria 3 has it (Jul 2026)",
        "Some lock songs in: Udio does not allow downloads (Apr 2026)",
    ], "Sources: SRC-111, SRC-116, SRC-112, SRC-101 in docs/registers/sources.csv"),
    ("Solution", [
        "Type a vibe or paste lyrics in Hindi, Hinglish or English",
        "Two takes in about a minute; keep one; download it",
        "On a paid plan, the song is yours: every right BeatMind holds is assigned to you",
        "Every song labelled as AI-made, with a licence and rights certificate",
    ], ""),
    ("Demo", ["[TO ADD: 60-second recording from the beta: vibe to video track; Hindi lyrics to song; certificate and verify page]"], ""),
    ("Why now", [
        "India: 518 million YouTube users, the largest audience (Apr 2026)",
        "AI audio must now carry labels and provenance: India from 20 Feb 2026; EU from 2 Aug 2026",
        "Open music models with commercial licences arrived in 2026 (ACE-Step 1.5, Feb 2026)",
        "Self-hosted generation costs well under one US cent per take (calculated, to be measured)",
    ], "Sources: SRC-114, SRC-117, SRC-119, SRC-106; TDD section 9.1"),
    ("Market", [
        "Bottom-up serviceable market for Indian creators",
        "[TO ADD after interviews and waitlist test: creator counts, measured conversion, local price]",
        "No top-down market figure until one reputable source is chosen; published estimates differ about tenfold",
    ], "Market analysis sections 2.2 and 7"),
    ("What makes BeatMind different", [], "Prices and features as reported Oct 2026; sources in market analysis section 3"),
    ("Technology", [
        "Java 21 / Spring Boot core, Python GPU workers, Next.js web",
        "Open-weight models with commercial licences; Lyria API as a capped fallback",
        "Provenance ledger, C2PA content credentials and watermark on every song",
        "Honest risk: model publishers' training-data statements are not independently verified; outputs are similarity-screened",
    ], "TDD v2.0; ADR-007; RISK-001"),
    ("Business model", [
        "Subscriptions with purchasing-power pricing in five country bands",
        "India: Creator ₹149, Pro ₹349 (hypotheses to test); US: $8 and $18",
        "Modelled contribution margin: about 61% (India Creator) to 92% (US Creator)",
        "Free tier: capped, 5 commercial songs a month with attribution",
    ], "Financial model (hypotheses; GPU cost to be measured)"),
    ("Traction", ["[TO ADD only from the evidence log: waitlist, interviews, listening-test results, beta keep rate and retention]"], "docs/11-validation/evidence-log.csv"),
    ("Go-to-market", [
        "India first, creators first",
        "Founding-creator programme; Shorts and Reels content; SEO; creator and college communities",
        "Then South Asia, Indonesia, Brazil",
    ], "GTM plan"),
    ("Competition and risks", [
        "Google Lyria 3 is the main threat in India (Hindi, Gemini and YouTube reach)",
        "Open-model quality may trail Suno; language gate decides launch languages",
        "Training-data provenance of open models; free-tier cost",
        "Each risk has an owner and mitigation in the risk register",
    ], "docs/registers/risks.csv"),
    ("Team", ["[TO ADD: founder and three senior developers, roles, experience]", "Hiring after funding: audio-ML engineer, rights and partnerships lead"], ""),
    ("Roadmap", [
        "Jan 2027: closed beta and investor demo",
        "Paid launch in India after gate G3",
        "Phase 2: mixer and DJ deck for own songs, release to Spotify via a distributor, more Indian languages",
        "Phase 3: licensed artist voices, public API, licensed-data model",
    ], ""),
    ("The ask", [
        "[TO ADD by founders: amount and instrument]",
        "Use of funds: product and engineering 40%, compute 20%, licensed data and rights 15%, go-to-market 15%, legal, compliance and contingency 10%",
    ], "Financial model section 4.2"),
]

TABLE = [
    ["", "BeatMind", "Suno", "Udio", "Google Lyria 3"],
    ["India price / month", "₹149 / ₹349 (to test)", "₹999 / ₹2,999", "Not checked", "Not reported"],
    ["Native Hindi", "Language gate [TO ADD]", "No (Hinglish works)", "Not reported", "Yes"],
    ["Download and own paid songs", "Yes", "Downloads yes; ownership unclear", "No downloads", "Not reported"],
    ["AI label and certificate", "Yes, every song", "Not reported", "Not reported", "SynthID watermark"],
]


def title_bar(slide, prs, text):
    bar = slide.shapes.add_shape(1, 0, 0, prs.slide_width, Inches(1.0))
    bar.fill.solid()
    bar.fill.fore_color.rgb = NAVY
    bar.line.fill.background()
    tf = bar.text_frame
    tf.text = text
    p = tf.paragraphs[0]
    p.font.size, p.font.bold, p.font.color.rgb = Pt(28), True, RGBColor(0xFF, 0xFF, 0xFF)
    tf.margin_left = Inches(0.5)


def footer(slide, prs, text, n):
    box = slide.shapes.add_textbox(Inches(0.5), prs.slide_height - Inches(0.5), prs.slide_width - Inches(1.5), Inches(0.35))
    box.text_frame.text = text
    box.text_frame.paragraphs[0].font.size = Pt(10)
    box.text_frame.paragraphs[0].font.color.rgb = GREY
    num = slide.shapes.add_textbox(prs.slide_width - Inches(0.9), prs.slide_height - Inches(0.5), Inches(0.5), Inches(0.35))
    num.text_frame.text = str(n)
    num.text_frame.paragraphs[0].font.size = Pt(10)
    num.text_frame.paragraphs[0].alignment = PP_ALIGN.RIGHT


def bullets(slide, items):
    box = slide.shapes.add_textbox(Inches(0.6), Inches(1.4), Inches(12.1), Inches(5.2))
    tf = box.text_frame
    tf.word_wrap = True
    for i, item in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.text = ("• " if not item.startswith("[") else "") + item
        p.font.size = Pt(22)
        p.font.color.rgb = PLACEHOLDER if item.startswith("[") else INK
        p.space_after = Pt(14)


def table(slide):
    rows, cols = len(TABLE), len(TABLE[0])
    shape = slide.shapes.add_table(rows, cols, Inches(0.6), Inches(1.5), Inches(12.1), Inches(3.6))
    for r in range(rows):
        for c in range(cols):
            cell = shape.table.cell(r, c)
            cell.text = TABLE[r][c]
            para = cell.text_frame.paragraphs[0]
            para.font.size = Pt(15)
            para.font.bold = r == 0 or c == 0
            if "[TO ADD" in TABLE[r][c]:
                para.font.color.rgb = PLACEHOLDER


def main():
    prs = Presentation()
    prs.slide_width, prs.slide_height = Inches(13.333), Inches(7.5)
    blank = prs.slide_layouts[6]

    s = prs.slides.add_slide(blank)
    bg = s.shapes.add_shape(1, 0, 0, prs.slide_width, prs.slide_height)
    bg.fill.solid()
    bg.fill.fore_color.rgb = NAVY
    bg.line.fill.background()
    box = s.shapes.add_textbox(Inches(0.8), Inches(2.4), Inches(11.5), Inches(3))
    tf = box.text_frame
    tf.text = "BeatMind AI"
    tf.paragraphs[0].font.size, tf.paragraphs[0].font.bold = Pt(54), True
    tf.paragraphs[0].font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    p = tf.add_paragraph()
    p.text = "Original songs in your language, at your price, that you own."
    p.font.size, p.font.color.rgb = Pt(26), ACCENT
    p = tf.add_paragraph()
    p.text = "Draft v0.1 · [Founders' names] · [Date] · Contains no traction figures until measured"
    p.font.size, p.font.color.rgb = Pt(14), RGBColor(0xDD, 0xDD, 0xDD)

    for n, (title, items, src) in enumerate(SLIDES, start=2):
        s = prs.slides.add_slide(blank)
        title_bar(s, prs, title)
        if title.startswith("What makes"):
            table(s)
        else:
            bullets(s, items)
        footer(s, prs, src, n)

    prs.save(HERE / "beatmind-pitch-deck-v2.pptx")


if __name__ == "__main__":
    main()
