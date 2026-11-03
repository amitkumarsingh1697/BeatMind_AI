# Manual screen-reader pass (US-043, TC-REQ-NFR-08-02)

axe-core runs in CI on every page (the stack job in `.github/workflows/ci.yml`), but it cannot tell
whether the app makes sense when heard. This pass covers that. Run it before gate G2 and again
before each release that changes a core flow. Record each run as a new table at the end.

## Set-up

| Pair | Device | Browser | Language |
|---|---|---|---|
| NVDA 2025 or later | Windows laptop | Firefox and Chrome | English, then Hindi (set NVDA to Hindi voice) |
| TalkBack | Mid-range Android phone (the beta's main device) | Chrome | Hindi, then English |
| VoiceOver (optional) | iPhone | Safari | English |

Use staging, a fresh test account, and the screen reader's default settings. Do not look at the
screen for the "heard" column: write down what was spoken.

## Flows and what must be heard

| # | Flow | Steps | Must be heard |
|---|---|---|---|
| 1 | Sign in | Open `/hi`, sign in with an email code | The code field's label, the error when the code is wrong, and where focus lands after sign-in |
| 2 | 18+ step | Enter a birth date under 18, then over 18 | The refusal is read out without moving focus away |
| 3 | Make a song | Describe a song, choose Hindi, submit | Queue position and the wait estimate in the live region, then "takes ready"; no repeated announcements (UX-A11Y-15) |
| 4 | Make a beat | Switch to "Make a beat", set genre, BPM, key, two instruments to include and one to exclude | Each checkbox's name and state; the message when an instrument is in both lists |
| 5 | Play and pick a take | Play take 1, pause, play take 2 | The player's name includes the title and the AI-generated label (UX-A11Y-01); measured tempo for beats |
| 6 | Download | Download the MP3 | The format choice and what the free plan includes |
| 7 | Share | Make a public link, copy it, revoke it | The link is read once with its copy button; the revoke confirmation |
| 8 | Shared page | Open the link signed out | Title, AI-generated label and the player; nothing about the owner |
| 9 | Report a song | Open Report from the footer, fill it in | Every field label, the required-fields errors, and the reference number after sending |
| 10 | Removed song and appeal | As the owner of a removed song, open it and appeal | The Removed badge, why it was removed, and the appeal form |
| 11 | Your data | Download the export, open Delete account, cancel | The dialog's title, that focus is trapped in it, and that Escape closes it |
| 12 | Admin takedowns | As an admin, acknowledge and decide a report | The deadline and whether it is overdue for each card |

## Pass rule

A flow passes when every "must be heard" item is spoken correctly, everything can be done with the
screen reader's own gestures or keys, and focus never gets lost. Log each failure in
`docs/registers/review-findings.csv` with its flow number. Gate G2 needs every flow to pass on NVDA
and TalkBack, or a logged finding with a fix date that the founder accepts.

## Runs

### Run 1: [date], [tester], [screen reader and device]

| # | Result | What was heard, if it differs | Finding |
|---|---|---|---|
| 1 | | | |
| 2 | | | |
| 3 | | | |
| 4 | | | |
| 5 | | | |
| 6 | | | |
| 7 | | | |
| 8 | | | |
| 9 | | | |
| 10 | | | |
| 11 | | | |
| 12 | | | |
