# Song languages and rollout phases

The single list of languages BeatMind AI plans to sing in, and when. It replaces the language rows
scattered across the PRD, the decision log and the older PRD .docx (DEC-028).

## Rules

- **Supported means passed G1.** A language is shown as supported only after it passes the G1
  listening test (PRD section 12): at least 30 native listeners, BeatMind preferred or tied in at
  least 40% of pairings. Until then it is hidden or marked "experimental" (REQ-F-07).
- **Quick check first.** Before G1, each language gets the 5-song spike used for Hindi
  (`workers/spikes/hindi`), with one native listener. It is cheap and catches a weak language early.
- **Phase 1 is the most popular languages worldwide; Phase 2 is regional languages.** Phases are
  split further by popularity when a list is long (founder, 2026-10-31).
- **Model coverage decides the work.** "ACE-Step" below says whether the language is in ACE-Step
  1.5's `VALID_LANGUAGES` list (checked 2026-10-31). A language that is not listed needs another
  model, a fine-tune on licensed data, or the Lyria 3 API fallback (RISK-003) before it can pass G1.
- **Interface languages are separate.** The app interface stays English and Hindi at beta
  (FEAT-16, REQ-NFR-09). This list is for lyrics and singing.

Popularity order is our estimate from speaker numbers and music-market size; check it against the
market research before committing dates.

## Phase 1: most popular languages worldwide

### Phase 1a: beta (India beachhead)

| Language | Code | ACE-Step | Notes |
| --- | --- | --- | --- |
| Hindi | hi | Yes | 5-song spike done 2026-10-30: pronunciation slips fixed by respelling and the LM planner |
| English | en | Yes | |
| Hinglish | hi | Via Hindi | Hindi and English mixed, usually in Roman script; sung with `vocal_language=hi` |
| Punjabi | pa | Yes | Gurmukhi script; Shahmukhi (Pakistan) to test separately |
| Urdu | ur | Yes | Nastaliq script, right to left |

### Phase 1b: public launch

| Language | Code | ACE-Step | Notes |
| --- | --- | --- | --- |
| Spanish | es | Yes | |
| Portuguese | pt | Yes | Brazilian and European pronunciation differ |
| French | fr | Yes | Named in DEC-018 |
| German | de | Yes | |
| Russian | ru | Yes | Named in DEC-018; no paid launch inside Russia (RISK-012) |
| Japanese | ja | Yes | Lyrics need kanji converted to kana before singing |
| Korean | ko | Yes | |
| Mandarin Chinese | zh | Yes | Tones make pronunciation checks harder |
| Arabic | ar | Yes | Right to left; dialects vary widely |
| Bengali | bn | Yes | About the 7th most spoken language worldwide |
| Indonesian | id | Yes | |
| Turkish | tr | Yes | |
| Italian | it | Yes | |

## Phase 2: regional languages

### Phase 2a: largest regional languages

| Language | Code | ACE-Step | Notes |
| --- | --- | --- | --- |
| Tamil | ta | Yes | Was FEAT-23 |
| Telugu | te | Yes | Was FEAT-23 |
| Marathi | mr | **No** | Was FEAT-23; needs a model that covers it |
| Gujarati | gu | **No** | |
| Kannada | kn | **No** | |
| Malayalam | ml | **No** | |
| Bhojpuri | bho | **No** | Devanagari, close to Hindi; not one of India's 22 scheduled languages, so few speech tools cover it |
| Odia | or | **No** | |
| Nepali | ne | Yes | |
| Cantonese | yue | Yes | |
| Vietnamese | vi | Yes | |
| Thai | th | Yes | |
| Filipino (Tagalog) | tl | Yes | |
| Persian | fa | Yes | |
| Swahili | sw | Yes | |
| Malay | ms | Yes | |
| Polish | pl | Yes | |
| Ukrainian | uk | Yes | |
| Dutch | nl | Yes | |

### Phase 2b: further regional languages

| Language | Code | ACE-Step | Notes |
| --- | --- | --- | --- |
| Assamese | as | **No** | |
| Haryanvi | bgc | **No** | |
| Rajasthani | raj | **No** | |
| Maithili | mai | **No** | |
| Konkani | kok | **No** | |
| Kashmiri | ks | **No** | |
| Sindhi | sd | **No** | |
| Tulu | tcy | **No** | |
| Santali | sat | **No** | |
| Manipuri (Meitei) | mni | **No** | |
| Sanskrit | sa | Yes | Devotional music |
| Javanese | jv | **No** | |
| Yoruba | yo | **No** | |
| Hausa | ha | **No** | |
| Amharic | am | **No** | |
| Greek | el | Yes | |
| Hebrew | he | Yes | Right to left |
| Romanian | ro | Yes | |
| Czech | cs | Yes | |
| Hungarian | hu | Yes | |
| Swedish | sv | Yes | |
| Danish | da | Yes | |
| Norwegian | no | Yes | |
| Finnish | fi | Yes | |
| Bulgarian | bg | Yes | |
| Serbian | sr | Yes | |
| Croatian | hr | Yes | |
| Slovak | sk | Yes | |
| Lithuanian | lt | Yes | |
| Catalan | ca | Yes | |
| Azerbaijani | az | Yes | |
| Icelandic | is | Yes | |
| Haitian Creole | ht | Yes | |

Latin (`la`) is also in ACE-Step's list but is not planned.

## Gaps

- **Most Indian regional languages are not in ACE-Step's list,** including Marathi, Gujarati,
  Kannada, Malayalam, Bhojpuri and Odia. Phase 2a for India depends on a second model,
  a fine-tune on licensed regional songs (BUG-086, FEAT-33) or the Lyria 3 fallback.
- **Listed does not mean good.** ACE-Step's list says the model accepts the language, not that it
  sings it well. Every language still needs the quick check and G1.
