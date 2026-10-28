# UX and accessibility specification

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 2), 2026-10-28 |
| Scope | MVP web app on desktop and mobile web (DEC-016) |
| Accessibility target | WCAG 2.2 level AA (DEC-017, REQ-NFR-08) |
| Languages | English and Hindi interface at beta (FEAT-16) |
| Status | Draft. No designs exist yet; this spec is the brief for them. |

## 1. Design principles

1. **One clear action per screen.** Creators on phones need to get a track in a few taps.
2. **Speak the user's language.** Hindi and English everywhere, including errors, emails, certificates and receipts.
3. **Show cost and rights before the click.** Credits used, commercial-use status and ownership are visible before generating or downloading.
4. **Label AI honestly.** The AI-generated label is part of the design, not a footnote (REQ-F-33).
5. **Light pages.** The app must work on a 3 GB RAM Android phone over 4G (REQ-NFR-10).

## 2. Information architecture

```
Home (signed out): value, examples, prices for your country, sign in
Create
  ├─ For my video (vibe mode)           FEAT-01
  ├─ Song from my lyrics (custom mode)   FEAT-02, FEAT-03
  └─ Beat (instrumental mode)            FEAT-04
Library
  ├─ Projects, songs, takes, versions    FEAT-12
  └─ Song page: player, editor, downloads, licence, share   FEAT-06, FEAT-07, FEAT-09, FEAT-13
Plans and billing                        FEAT-08
Settings: profile, language, privacy, data export, delete account, MFA
Help and verify (public)                 FEAT-10
Admin (role-gated)                       FEAT-18
```

## 3. Core screens

| Screen | Key elements | States to design |
| --- | --- | --- |
| Create: vibe | Text box with example chips ("chill rain vlog", "shaadi dance", "motivational gym"), length selector, vocals on or off, language, Generate button showing credit cost | Empty, typing, validation error, blocked prompt with reason, out of credits, daily cap reached, queue wait estimate, generating, two takes ready, failed with refund notice |
| Create: lyrics | Lyrics editor with grapheme counter, "Write lyrics for me" with topic and mood, style, voice type | Same as above plus lyric moderation message |
| Create: beat | Genre, BPM slider and number field, key, instruments include and exclude | Same as above |
| Take review | Two players side by side (stacked on mobile), Keep, Regenerate, Discard | Playing, paused, kept, discarded |
| Song page | Player with waveform, lyrics, AI-generated label, version list, editor actions, downloads by format, licence and certificate panel, share | Owner view, public view, disabled by takedown, under appeal |
| Licence panel | Rights status in plain words: "You own this song" (paid) or "BeatMind owns this song; you may use it commercially with attribution" (free licensed) or "Personal listening only" (free) | Remaining free commercial licences this month |
| Plans | Local prices for the user's billing country, what each plan includes (entitlement matrix), UPI shown first in India | Current plan, upgrade, downgrade, cancel |
| Verify (public) | Upload file or enter certificate ID; result card | Match, partial match, no match, rate limited |
| Admin | Users, moderation queue, takedowns with deadline timers, appeals, caps | Overdue (3-hour orders highlighted) |

## 4. Copy rules

- Plain words. Never promise copyright protection or "no claims ever" (REQ-F-26). Approved phrases are kept in a copy library reviewed by counsel (Batch 4).
- Every blocked action explains why and what to do next.
- Numbers and currency follow the user's locale (₹1,49,900 style in Indian English and Hindi where appropriate; REQ-NFR-09).
- Hindi copy is written by a native speaker, not machine-translated, before beta.

## 5. Accessibility requirements (WCAG 2.2 AA)

General AA criteria apply to every page. These items need specific design decisions for BeatMind:

| ID | WCAG criterion | BeatMind application |
| --- | --- | --- |
| UX-A11Y-01 | 1.1.1 Non-text content | Waveforms and cover art have text alternatives; the player exposes title, artist and AI label to screen readers |
| UX-A11Y-02 | 1.2.1 and 1.2.2 Audio-only and captions | Lyrics are shown as text for every song with vocals; time-synced lyrics are a Phase 2 goal |
| UX-A11Y-03 | 1.4.2 Audio control | No audio plays automatically; every player has pause and volume controls |
| UX-A11Y-04 | 1.4.3 and 1.4.11 Contrast | 4.5:1 for text, 3:1 for controls, waveform playhead and focus rings, in light and dark themes |
| UX-A11Y-05 | 1.4.4 and 1.4.10 Resize and reflow | Works at 200% zoom and 320 CSS px width without horizontal scrolling, including Devanagari text |
| UX-A11Y-06 | 2.1.1 Keyboard | Player, take comparison, BPM slider and editor fully keyboard operable; documented shortcuts that can be turned off (2.1.4) |
| UX-A11Y-07 | 2.4.7, 2.4.11 Focus visible and not obscured | Visible focus ring; sticky player bar never covers the focused element |
| UX-A11Y-08 | 2.5.7 Dragging movements | Every drag (trim handles, BPM slider, region select) has a non-drag alternative such as number fields or buttons |
| UX-A11Y-09 | 2.5.8 Target size | Interactive targets at least 24 by 24 CSS px; 44 px recommended for primary mobile buttons |
| UX-A11Y-10 | 3.1.1 and 3.1.2 Language | `lang` attribute set per page and per lyric block (`hi`, `en`, `hi-Latn` for Hinglish) so screen readers pronounce correctly |
| UX-A11Y-11 | 3.3.1 and 3.3.3 Errors | Errors named in text, linked to the field, with a fix suggestion |
| UX-A11Y-12 | 3.3.7 Redundant entry | Generation settings are remembered between takes and edits |
| UX-A11Y-13 | 3.3.8 Accessible authentication | Sign-in by Google or email code; codes can be pasted; no cognitive puzzle; Turnstile in non-interactive mode where possible |
| UX-A11Y-14 | 4.1.2 Name, role, value | Custom controls (player, waveform, take switcher) use correct ARIA roles and states |
| UX-A11Y-15 | 4.1.3 Status messages | Generation progress, queue position and "takes ready" announced through a polite live region |
| UX-A11Y-16 | 3.2.6 Consistent help | Help link in the same place on every page |

Testing: automated axe-core checks on every page (TC-REQ-NFR-08-01) and manual passes with NVDA on Windows, VoiceOver on macOS and iOS, and TalkBack on Android (TC-REQ-NFR-08-02).

## 6. Fair-design rules (dark patterns)

These follow consumer-protection expectations (COMP-IN-06) and build trust:

- Prices include tax where the law requires; no hidden fees at checkout.
- Cancellation online in three steps or fewer (REQ-F-43); no forced chat or call.
- No pre-ticked boxes for marketing, analytics or training consent.
- Trial or plan renewal reminders sent before charging (RBI pre-debit notice is handled by the provider, COMP-IN-08).
- No fake urgency or countdowns.

## 7. Performance budget

| Metric (mid-range Android, 4G) | Budget |
| --- | --- |
| JavaScript on first load (compressed) | 200 KB or less |
| Largest Contentful Paint | 2.5 s or less at p75 |
| Interaction to Next Paint | 200 ms or less at p75 |
| Cumulative Layout Shift | 0.1 or less |
| MP3 preview start | Streaming starts within 1 s of play on a cached CDN object |

## 8. Internationalisation

- All strings in message catalogues (next-intl); no text in images.
- Fonts: a Devanagari-capable font family with Latin support, subsetted; system fonts as fallback.
- Layout tolerates text 40% longer than English.
- Right-to-left support is built into components now (logical CSS properties) so Urdu can be added in Wave 2.
- Dates, numbers and currency formatted with the Intl API per locale.

## 9. Research before final designs

Five quick tests in Sprint 1 and 2, tracked in the validation plan (Batch 3):

1. First-click test on the Create screen with 5 Indian creators.
2. Hindi copy comprehension check with 5 native speakers.
3. Licence panel comprehension: can users say who owns the song?
4. Price page comprehension at local prices.
5. Screen-reader walkthrough of the core flow with at least one screen-reader user.
