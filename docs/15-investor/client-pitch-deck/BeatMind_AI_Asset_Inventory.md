# BeatMind AI · Client pitch deck asset inventory

This file lists every visual in `BeatMind_AI_Client_Pitch_Deck.pptx`. For each one it gives where it came from, how it was made and whether it should be replaced before the deck goes to clients. Paths are relative to the `source/` folder that ships with the deck.

## 1. Product screenshots (real, Sprint 6 build)

The newest captures in `beatmind/sprint-06-screens/` are the source of truth. Nothing on a screen was altered or mocked up. The crops are made by `src/make_crops.sh`, which records the exact crop box for each file.

| Crop file (`assets/crops/`) | Source screenshot | Used in (via 3D frame) | Slides |
|---|---|---|---|
| `create-vibe.png` | `03-create-vibe.png` | `dev_create_left/right.png` | 6, 8 |
| `song-hi.png` | `07-song.png` (top) | `dev_song_*.png` | spare |
| `history.png` | `07-song.png` (history panel) | `card_history_flat.png` | 18 |
| `song-licensed.png` | `08b-licensed.png` | not used in final deck | none |
| `certificate.png`, `certificate-card.png` | `08c-certificate.png` | `card_certificate_flat.png` | 8 |
| `verify.png` | `22-verify-certificate.png` | `dev_verify_*.png` | none (rendered, spare) |
| `stems.png` | `23-stems.png` (stem list) | `card_stems_flat.png` | spare |
| `admin.png` | `12-admin.png` | `dev_admin_right.png` | 8 |
| `pricing.png` | `19-pricing.png` | `dev_pricing_*.png` | spare |
| `library.png` | `05-library.png` | `dev_library_left.png` | spare |
| `editor.png` | `20-editor.png` (editor panel) | `card_editor_*.png` | spare |
| `report.png` | `08f-report-form.png` | not used in final deck | none |
| `blocked.png` | `03a-blocked.png` (refusal message) | `card_blocked_flat.png` | 18 |
| `shared.png` | `08e-shared-page.png` | not used in final deck | none |
| `waitlist.png` | `01-waitlist.png` | `dev_waitlist_left.png` | spare |
| `ph-14-phone-create-hi.png` | `14-phone-create-hi.png` | `ph_create_hi*.png` | 9, 11 |
| `ph-13-phone-song-hi.png` | `13-phone-song-hi.png` | `ph_song_hi*.png` | 1, 6, 9, 11, 23 |
| `ph-19c-phone-pricing-hi.png` | `19c-phone-pricing-hi.png` | `ph_pricing_hi_flat.png` | 9 |
| `ph-22b-phone-verify-hi.png` | `22b-phone-verify-hi.png` | `ph_verify_hi_flat.png` | 9 |
| `ph-15-phone-library-hi.png` | `15-phone-library-hi.png` | `ph_library_hi_flat.png` | 9, 11 |
| `ph-18-phone-shared-hi.png` | `18-phone-shared-hi.png` | `ph_shared_hi_flat.png` | spare |
| `ph-10-phone-create.png`, `ph-11-phone-song.png` | English phone captures | `ph_*_en_flat.png` | spare |

"Spare" means the asset was rendered and kept in `assets/generated/` so it can be swapped in, but no slide places it now.

**Important about the audio in these captures:** the Sprint 6 build used a test-tone generator, so the waveforms and players show test audio, not ACE-Step songs. Slide 8 says this in its footnote. Re-capture once the GPU worker is live (see section 5).

## 2. Generated assets (made for this deck)

All of these are made by `src/render_assets.js`, which uses Playwright and Chromium with CSS 3D perspective, followed by `src/tight_trim.py`. They contain no stock imagery.

| File (`assets/generated/`) | What it is | Slides |
|---|---|---|
| `cover_loop.mp4` | 8 s, 1920×1080, H.264, 30 fps, silent loop. Floating browser and phone with real screens, drifting light, equaliser bars. Rendered frame by frame and encoded with ffmpeg. | 1 |
| `cover_poster.jpg` | Still frame of the same scene. Used as the video's poster and as the slide 23 backdrop. | 1, 23 |
| `dev_*.png` | Browser frames holding real screenshots, tilted left, tilted right or flat, with a soft glow. | 6, 8 |
| `ph_*.png` | Phone frames holding the real Hindi phone captures. | 1, 6, 9, 11, 23 |
| `card_*.png` | Floating glass cards cropped from real screens (certificate, history, refusal). | 8, 18 |
| `bg_a.jpg`, `bg_b.jpg`, `bg_c.jpg` | Midnight background plates with a faint grid and grain. | all content slides |
| `orb_violet.png`, `orb_rani.png`, `orb_marigold.png` (`orb_cyan.png` spare) | Blurred glow orbs. Their names are the same on every slide, so Morph moves them between slides. | 2 to 22 |
| `logo_mark.png` | The BeatMind logo mark, rasterised from the SVG in `web/components/Icons.tsx`. | 1, 23 |
| `_page.html` | Temporary page used during rendering. It is not an asset. | none |

**Native PowerPoint objects (no image files).** These are all editable in PowerPoint:
- Cards, chips, timelines, tables, arrows and numbered markers.
- The price bar chart on slide 5 and the margin column chart on slide 14, which are native charts with editable data.
- The brand gradient bars, which are native gradient fills.
- Icons from the Lucide set (MIT licence), embedded through `react-icons` as PNG at 256 px.

## 3. Fonts

| Font | Use | Licence | Shipped in |
|---|---|---|---|
| Manrope (200–800) | Headlines, numbers | SIL OFL | `source/fonts/` |
| Inter | Body, labels, tables | SIL OFL | Not shipped. Install from rsms.me/inter or Google Fonts. |
| Noto Sans Devanagari | Hindi text | SIL OFL | `source/fonts/` |

The fonts are **not embedded** in the PPTX. Install them on the presenting machine (see the technical notes).

## 4. Video, audio and renders

- **Video:** there is one video, `cover_loop.mp4` on slide 1. It is silent, autoplays and loops.
- **Audio:** none. The deck plays no sound.
- **Static 3D renders:** all of the device and card images listed in section 2.
- **Interactive 3D:** none. PowerPoint 3D models (GLB) were not used, because they do not survive in PDF, Google Slides or LibreOffice.

## 5. Assets to replace or complete before external use

| What | Where | Why |
|---|---|---|
| Partner name, presenter name and role | Slide 1 footer line | Placeholder `[Partner name]`, `[Name, role]` |
| Team names, roles and experience | Slide 19 marigold box | Placeholder `[TEAM: …]`. No team data was in the materials. |
| Contact line | Slide 23 | Placeholder `[Name] · [email] · [phone] · [website]` |
| Official logo file | Slides 1 and 23 | The logo was rebuilt from the SVG in code. Swap in the master logo if one exists. |
| Screens with real songs | Slides 1, 6, 8, 9, 11, 23 | Captures use test tones. Re-capture with ACE-Step output after the GPU worker goes live, then run `ASSETS=1 bash build.sh`. |
| Hindi quality (G1) result | Slides 15 and 20 | Currently "test pending". Update once the blind listening test is scored. |
| Measured GPU cost per take | Slide 14 | Modelled at US$0.004. Replace after the benchmark. |
| Customer logos, testimonials, traction | Not in the deck | None exist yet, so none were shown. Add a traction slide once the beta produces evidence. |
