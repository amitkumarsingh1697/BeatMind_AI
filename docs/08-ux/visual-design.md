# Visual design system

| Item | Value |
| --- | --- |
| Version | 1.0 (Sprint 2), 2026-11-02 |
| Code | `web/app/globals.css` (tokens and components), `web/components/` |
| Live style guide | `/en/design` (not linked from the app, not indexed) |
| Checked by | `web/e2e/ui-check.mjs`: axe-core on every screen, light and dark, desktop and phone |
| Status | First version. Needs the five quick research tests in the UX spec, section 9 |

This is the look the UX and accessibility spec (`ux-accessibility-spec.md`) asked for. The spec
still decides behaviour; this page decides how it looks.

## 1. Character

Warm, bright and honest about AI. Indian creators should feel the product is made for them: festival
colours on a night-sky background, rounded Devanagari-friendly type, and the AI label treated as a
first-class part of the design rather than a footnote.

## 2. Colour

Three brand colours, each with one job:

| Token | Dark | Light | Job |
| --- | --- | --- | --- |
| `--accent` (marigold) | `#FFB020` | `#F5A200` | The main action on a screen: Make, Download, Save. Dark text on it |
| `--accent-text` | `#FFB020` | `#8A4B00` | Marigold used as text (eyebrows, small badges) |
| `--highlight` (rani pink) | `#FF5C93` | `#C2185B` | Sound: the playhead, slider fill |
| `--ai` (iris) | `#B4A8FF` | `#5B47D6` | The AI-generated label, and nothing else |
| `--success` / `--danger` | leaf / chilli | darker leaf / chilli | Ready, verified / failed, delete |

Surfaces: `--bg`, `--surface`, `--surface-raised`, `--surface-sunken`, `--border`,
`--border-strong`. The brand gradient (`--hero`, marigold to rani to violet) is used only for the
logo, song covers, the credit meter and the waitlist hero.

The theme follows the device's light or dark setting. Text pairs pass 4.5:1 and controls, focus
rings and the playhead pass 3:1 in both themes (UX-A11Y-04); axe-core checks this on every screen.

## 3. Type

* **Baloo 2** (variable) for headings and the wordmark: rounded and friendly, with a full
  Devanagari set.
* **Mukta** (400, 600, 700) for text: very readable at small sizes in both scripts.

Both are self-hosted from `@fontsource` and split by script with `unicode-range`, so an English
page does not download Devanagari files and the other way round. Scale: `--step--1` 14 px to
`--step-5` (fluid, up to 60 px). Body text is 16 px with 1.6 line height; lyrics are 18 px with
1.8 so matras and conjuncts never touch.

## 4. Space, shape, layout

4 px grid (`--space-1` 4 px to `--space-8` 64 px). Radii: 8, 14 and 22 px, and pills for buttons
and badges. Content is 760 px wide (1080 px for the waitlist and admin). Layout uses logical
properties (`inline-size`, `margin-inline`) so right-to-left scripts can be added later. Every page
works at 320 px wide and 200% zoom without horizontal scrolling (UX-A11Y-05).

## 5. Components

| Component | Where | Notes |
| --- | --- | --- |
| App shell | Every page | Skip link, sticky header with brand and main navigation, footer with Help in the same place everywhere (UX-A11Y-16). `scroll-padding` keeps focused elements clear of the sticky header (UX-A11Y-07) |
| Buttons | `.btn-primary`, `-secondary`, `-ghost`, `-danger` | 44 px tall (52 px large); one primary per screen |
| Fields | `.field` with label, hint, counter, error | Errors are linked with `aria-describedby` and say how to fix (UX-A11Y-11). Counters count characters as people see them (Devanagari conjuncts once) |
| Segmented control | Create: vibe or lyrics | A real radio group, so arrow keys work |
| Badges | AI label, status, rights | The AI label (iris, sparkle icon) is never hidden on a song, player or download |
| Notices | Info, success, danger, AI | Blocked actions say why and what to do next |
| Credit meter | Create | "16 of 20 credits left today", refunds noted |
| Player | Create, song page | Native button and range inputs: Tab, Space or Enter, arrow keys; no autoplay; mute and volume; spoken position ("45 seconds of 2 minutes"); one take plays at a time (UX-A11Y-03, -06, -14) |
| Take switcher | Song page | Toggle buttons with `aria-pressed`; held-back takes are disabled and explained |
| Dialogs | Rename, delete, download | Native `<dialog>`: focus moves in, Escape closes, focus returns |
| Song card, empty state | Library, create | |

Icons are inline SVG, decorative unless they are the only content of a control.

## 6. Screens in this version

Waitlist, sign-in and the 18+ step, Create (vibe and lyrics), Library (search, rename, delete),
Song page (player, lyrics, downloads, rights, history), Help, Admin (cap, plans, ledger check,
failed jobs) and the style guide. Screenshots of each, in both themes, are in the project files
under `beatmind/sprint-02-screens/`.

## 7. Not done yet

* Native review of the Hindi interface copy (US-010 built it in Sprint 3).
* Waveform drawing in the player (the spec mentions it; the slider shows progress for now).
* Cover art per song (the gradient cover stands in).
* Research tests from the UX spec, section 9, and a screen-reader pass with NVDA, VoiceOver and
  TalkBack (TC-REQ-NFR-08-02).

## Sprint 3 additions

- **Language switch:** the last item in the header navigation, outlined, labelled in the other
  language's own script (हिन्दी / English). It keeps the current page.
- **Experimental badge:** accent badge next to the vocal language when it has not passed G1, with
  one line of explanation; the option text also says "(Experimental)".
- **Blocked request:** danger notice with a heading, the reason in plain words, and a secondary
  "Ask a person to review" button that opens an optional note.
- **Lyric writer:** an iris-tinted card (the AI colour) inside the lyrics step; the written lyrics
  sit in a scrollable, focusable box with the AI label above them.
- **Rights panel:** plain-language owner line, licence quota, a confirm dialog listing the four
  conditions, and a read-only credit line with Copy. Always ends with "not a certificate of
  copyright".
- **Certificate page:** a single card, at most 760 px wide; header, footer and buttons are hidden
  when printing, colours drop to black on white.
