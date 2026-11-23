// Builds BeatMind_AI_Presentation_Design_Guide.pdf (A4 landscape) from HTML with Playwright.
const FONT_DIR = 'file://' + (process.env.FONT_DIR || require('path').join(require('os').homedir(), '.fonts'));
// Usage: node src/build_design_guide.js out/r out/BeatMind_AI_Presentation_Design_Guide.pdf
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const [renderDir, outPdf] = process.argv.slice(2);
const ROOT = path.resolve(__dirname, '..');
const f = (p) => 'file://' + path.resolve(p);
const slide = (n) => f(path.join(renderDir, `s-${String(n).padStart(2, '0')}.jpg`));

const COLORS = [
  ['Night', '#07050F', 'Background of every slide. 60–70% of the visual weight.'],
  ['Surface', '#151125', 'Glass cards, table cells (as white at 94% transparency over Night).'],
  ['Surface raised', '#1D1733', 'Table headers, raised chips.'],
  ['Line', '#2E2650', 'Hairlines, table borders.'],
  ['Text', '#F4F1FF', 'Headlines and primary text. 17.9:1 on Night.'],
  ['Muted text', '#B3ABD1', 'Body and supporting text. 9.1:1 on Night.'],
  ['Dim text', '#7F78A0', 'Sources, footers, captions only. 4.6:1 on Night.'],
  ['Marigold (primary accent)', '#FFB020', 'The product’s action colour: eyebrows, key words, the one thing to look at.'],
  ['Rani pink', '#FF5C93', 'Sound, pain points, energy.'],
  ['Violet', '#8B5CF6', 'Brand gradient end, trust layer fills.'],
  ['Iris', '#B4A8FF', 'AI and trust labels, connectors.'],
  ['Cyan', '#5CE1E6', 'Data and technology accents, used sparingly.'],
  ['Leaf', '#56D38A', 'Built, verified, positive outcomes.'],
];
const sw = COLORS.map(([n, h, u]) => `<div class="sw"><div class="chip" style="background:${h}"></div><b>${n}</b><code>${h}</code><span>${u}</span></div>`).join('');

const html = `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{font-family:Manrope;src:url(${FONT_DIR}/Manrope_800ExtraBold.ttf);font-weight:800}
@font-face{font-family:Manrope;src:url(${FONT_DIR}/Manrope_700Bold.ttf);font-weight:700}
@font-face{font-family:Manrope;src:url(${FONT_DIR}/Manrope_400Regular.ttf);font-weight:400}
@page{size:A4 landscape;margin:0}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:Inter,sans-serif;color:#F4F1FF;background:#07050F;-webkit-print-color-adjust:exact;print-color-adjust:exact}
.pg{width:297mm;height:210mm;padding:16mm 18mm;position:relative;page-break-after:always;overflow:hidden;
  background:radial-gradient(700px 420px at 92% 0%,rgba(91,61,245,.28),transparent 60%),radial-gradient(600px 400px at 0% 100%,rgba(255,92,147,.10),transparent 60%),#07050F}
.eb{font:700 9pt Inter;letter-spacing:.22em;color:#FFB020;text-transform:uppercase;margin-bottom:3mm}
h1{font:800 30pt Manrope;line-height:1.05;margin-bottom:5mm}
h2{font:700 19pt Manrope;margin-bottom:5mm}
h3{font:700 11.5pt Manrope;margin:4mm 0 1.5mm;color:#F4F1FF}
p,li{font-size:10pt;line-height:1.5;color:#B3ABD1}
li{margin-left:5mm;margin-bottom:1mm}
.ft{position:absolute;left:18mm;bottom:9mm;font-size:8pt;color:#7F78A0}
.pn{position:absolute;right:18mm;bottom:9mm;font-size:8pt;color:#7F78A0}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:4mm}
.sw{background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.12);border-radius:10px;padding:3mm;display:grid;grid-template-columns:14mm 1fr;column-gap:3mm;row-gap:.5mm;font-size:9pt}
.sw .chip{grid-row:span 3;width:14mm;height:14mm;border-radius:8px;border:1px solid rgba(255,255,255,.2)}
.sw b{font-family:Manrope;font-size:10pt}.sw code{color:#FFB020;font-size:8.5pt}.sw span{color:#B3ABD1;font-size:8pt;line-height:1.3}
.card{background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.12);border-radius:12px;padding:5mm}
.two{display:grid;grid-template-columns:1fr 1fr;gap:6mm}
.three{display:grid;grid-template-columns:1fr 1fr 1fr;gap:5mm}
img.sl{width:100%;border-radius:8px;border:1px solid rgba(255,255,255,.15)}
table{width:100%;border-collapse:collapse;font-size:9.5pt}
td,th{border-bottom:1px solid #2E2650;padding:2mm 2.5mm;text-align:left;vertical-align:top;color:#B3ABD1}
th{color:#FFB020;font-size:8.5pt;letter-spacing:.12em;text-transform:uppercase}
td:first-child{color:#F4F1FF;font-weight:600}
.grad{height:9mm;border-radius:6px;background:linear-gradient(90deg,#FFB020,#FF5C93 55%,#8B5CF6)}
.cap{font-size:8pt;color:#7F78A0;margin-top:1.5mm}
</style></head><body>

<section class="pg" style="background:url('${f(path.join(ROOT, 'assets/generated/cover_poster.jpg'))}') center/cover">
  <div style="position:absolute;inset:0;background:linear-gradient(90deg,rgba(7,5,15,.97) 0%,rgba(7,5,15,.85) 45%,rgba(7,5,15,.2) 75%)"></div>
  <div style="position:relative;margin-top:40mm;max-width:150mm">
    <img src="${f(path.join(ROOT, 'assets/generated/logo_mark.png'))}" style="width:14mm;margin-bottom:8mm">
    <div class="eb">BeatMind AI · Client pitch deck</div>
    <h1 style="font-size:40pt">Presentation design guide</h1>
    <p style="font-size:12pt;max-width:130mm">The colour, type, layout and motion rules behind the deck, so it can be extended for new prospects without drifting from the system.</p>
  </div>
  <div class="ft">Version 1.0 · November 2026</div>
</section>

<section class="pg"><div class="eb">01 · Design direction</div><h2>Festival colour on a night sky, with the product as the hero</h2>
<div class="two"><div>
<p>The deck borrows its identity from the BeatMind product itself (web/app/globals.css): a deep night background, marigold for action, rani pink for sound and violet for AI and trust. On top of that it adds the cinematic layer a client pitch needs: depth, glow and slow, deliberate motion.</p>
<h3>Principles</h3><ul>
<li><b style="color:#F4F1FF">Real product first.</b> Every device frame holds a real Sprint 6 screenshot. Nothing is mocked up or altered.</li>
<li><b style="color:#F4F1FF">One message per slide.</b> The title states the insight, not the topic.</li>
<li><b style="color:#F4F1FF">Honest numbers.</b> Every figure carries its source; modelled or illustrative figures say so on the slide.</li>
<li><b style="color:#F4F1FF">Depth with restraint.</b> Glass cards, soft glow and 3D tilt support the story; they never carry it.</li>
<li><b style="color:#F4F1FF">Dark throughout.</b> One premium dark theme from cover to close; contrast does the work, not colour changes.</li></ul>
</div><div><img class="sl" src="${slide(6)}"><div class="cap">Slide 6: the product reveal. Statement left, real screens in perspective right.</div></div></div>
<div class="ft">BeatMind AI · Presentation design guide</div><div class="pn">2</div></section>

<section class="pg"><div class="eb">02 · Colour</div><h2>Thirteen tokens, each with one job</h2>
<div class="grid">${sw}</div>
<h3>Brand gradient</h3><div class="grad"></div><div class="cap">#FFB020 → #FF5C93 (55%) → #8B5CF6. Used only for the logo mark, progress bars (roadmap, pilot) and the cover scene. Never on text.</div>
<div class="ft">Contrast ratios measured against Night (#07050F). Body text never uses Dim.</div><div class="pn">3</div></section>

<section class="pg"><div class="eb">03 · Typography</div><h2>Manrope for voice, Inter for reading</h2>
<div class="two"><div>
<div class="card" style="margin-bottom:4mm"><div style="font:800 34pt Manrope;line-height:1">Aa  Manrope</div><p>Display and titles · Bold / ExtraBold · tight leading (0.92–0.95)</p></div>
<div class="card"><div style="font:600 26pt Inter;line-height:1">Aa  Inter</div><p>Body, labels, tables, charts · Regular / Bold · leading 1.05–1.2</p></div>
<p style="margin-top:4mm">Devanagari text (रात का सफ़र) falls back to Noto Sans Devanagari or Nirmala UI. Both families are free (SIL Open Font License); install the bundled TTFs from <b>source/fonts</b> before presenting. If they are missing, PowerPoint substitutes a sans serif and line breaks may move.</p>
</div><div><table>
<tr><th>Role</th><th>Font</th><th>Size</th><th>Colour</th></tr>
<tr><td>Cover headline</td><td>Manrope Bold</td><td>40 pt</td><td>Text + Marigold</td></tr>
<tr><td>Statement headline</td><td>Manrope Bold</td><td>42 pt</td><td>Text + Marigold</td></tr>
<tr><td>Slide title</td><td>Manrope Bold</td><td>28 pt</td><td>Text</td></tr>
<tr><td>Eyebrow</td><td>Inter Bold, +3 tracking, caps</td><td>11 pt</td><td>Marigold</td></tr>
<tr><td>Big numbers</td><td>Manrope Bold</td><td>34–40 pt</td><td>Accent</td></tr>
<tr><td>Card heading</td><td>Manrope Bold</td><td>13–17 pt</td><td>Text</td></tr>
<tr><td>Body</td><td>Inter Regular</td><td>12–16 pt</td><td>Muted</td></tr>
<tr><td>Labels and chips</td><td>Inter Bold</td><td>10.5–11 pt</td><td>Accent</td></tr>
<tr><td>Sources, footer</td><td>Inter Regular</td><td>9 pt</td><td>Dim</td></tr></table></div></div>
<div class="ft">BeatMind AI · Presentation design guide</div><div class="pn">4</div></section>

<section class="pg"><div class="eb">04 · Layout</div><h2>A 16:9 grid with a fixed title zone and varied bodies</h2>
<div class="two"><div>
<ul><li>Canvas 13.33 × 7.5 in. Margins 0.6 in on all sides; footer and page number at 7.03 in.</li>
<li>Eyebrow at 0.5 in, title at 0.82 in, content from 1.85–2.35 in. Titles never move between content slides.</li>
<li>Gaps of 0.2–0.3 in between cards; card corner radius 0.14 in; glass = white fill at 94% transparency, hairline at 84%, soft shadow.</li>
<li>Layouts rotate: cinematic full screen, split, stat columns, journey line, process flow, device row, matrix, before/after, persona cards, table plus chart, framework, layer diagram, timeline.</li>
<li>Sources sit at 6.68 in in 9 pt Dim, above the footer.</li></ul>
<h3>Recurring motif</h3><p>Three soft glow orbs (violet, rani, marigold) live on every slide with the same names, so Morph drifts them from slide to slide. This is the deck's signature and its continuity device.</p>
</div><div class="three" style="grid-template-columns:1fr 1fr">
<div><img class="sl" src="${slide(3)}"><div class="cap">Stat columns</div></div><div><img class="sl" src="${slide(7)}"><div class="cap">Process flow</div></div>
<div><img class="sl" src="${slide(9)}"><div class="cap">Device row</div></div><div><img class="sl" src="${slide(12)}"><div class="cap">Before / after</div></div>
<div><img class="sl" src="${slide(17)}"><div class="cap">Layer diagram</div></div><div><img class="sl" src="${slide(20)}"><div class="cap">Timeline</div></div></div></div>
<div class="ft">BeatMind AI · Presentation design guide</div><div class="pn">5</div></section>

<section class="pg"><div class="eb">05 · Imagery and 3D</div><h2>Real screens, given depth</h2>
<div class="two"><div>
<ul><li>Source of truth: <b style="color:#F4F1FF">beatmind/sprint-06-screens</b>. Use the newest sprint folder when updating.</li>
<li>Screens are cropped (never edited) and placed in browser or phone frames, then rendered with CSS 3D perspective (rotateY ±14–34°, rotateX 3–7°), a hairline rim, gloss and a coloured glow. Script: source/src/render_assets.js.</li>
<li>Browser frames for desktop, phone frames for Hindi and mobile, plain cards for panels (certificate, history, editor).</li>
<li>Tilt toward the slide centre: left images face right, right images face left.</li>
<li>Do not add UI that is not in the product. The floating pills on the cover repeat labels that exist in the product.</li>
<li>Icons: Lucide line icons at 1.75 stroke, tinted with one accent, never filled.</li></ul>
</div><div><img class="sl" src="${slide(8)}"><div class="cap">Slide 8: three real screens in a perspective arc.</div><img class="sl" style="margin-top:3mm" src="${slide(18)}"><div class="cap">Slide 18: panels as flat cards beside text.</div></div></div>
<div class="ft">BeatMind AI · Presentation design guide</div><div class="pn">6</div></section>

<section class="pg"><div class="eb">06 · Motion</div><h2>Slow reveals, one direction, nothing that bounces</h2>
<div class="two"><div><table>
<tr><th>Element</th><th>Effect</th><th>Duration</th></tr>
<tr><td>Slide to slide</td><td>Morph, by object (fallback Fade)</td><td>1.2 s</td></tr>
<tr><td>Cover</td><td>Fade through black + 8 s looping video</td><td>1.0 s</td></tr>
<tr><td>Eyebrow</td><td>Fade</td><td>0.5 s</td></tr>
<tr><td>Title, text</td><td>Float In (rise 6%, decelerate)</td><td>0.6 s</td></tr>
<tr><td>Cards, devices, charts</td><td>Zoom from 82% + fade</td><td>0.9 s</td></tr>
<tr><td>Lines, bars, bands</td><td>Wipe from left</td><td>0.7–1.4 s</td></tr>
<tr><td>Stagger</td><td>Left to right, top to bottom</td><td>120–280 ms</td></tr></table>
<h3>Rules</h3><ul>
<li>Everything builds automatically after the transition, so the presenter clicks once per slide.</li>
<li>Motion follows reading order and the direction of the story (left to right, past to future).</li>
<li>A full build finishes within about 2.5 s; do not add effects that hold the audience longer.</li>
<li>No spins, bounces or exits. Emphasis comes from colour and size, not movement.</li></ul>
</div><div class="card"><h3 style="margin-top:0">Implemented in the PPTX</h3><ul>
<li>Native PowerPoint Morph transitions on slides 2–23, with a Fade fallback for players without Morph.</li>
<li>Native entrance effects (Fade, Float In, Zoom, Wipe) on every content object, all starting automatically.</li>
<li>One embedded MP4 (cover, 8 s, 1920×1080, H.264, muted, loops).</li>
<li>Pre-rendered 3D: device tilt, glow and depth are static PNG renders, not interactive 3D models.</li></ul>
<h3>Not in the PDF</h3><p>The PDF shows the final state of every slide and the cover video's poster frame. Transitions, builds and the video are PowerPoint only.</p></div></div>
<div class="ft">BeatMind AI · Presentation design guide</div><div class="pn">7</div></section>

<section class="pg"><div class="eb">07 · Brand, copy and presenting</div><h2>Rules that keep the deck credible</h2>
<div class="three"><div class="card"><h3 style="margin-top:0">Brand</h3><ul>
<li>Logo mark: gradient tile with four bars, from web/components/Icons.tsx. Do not recolour or stretch.</li>
<li>Wordmark: “BeatMind” in Text, “AI” in Marigold.</li>
<li>One accent per element. Marigold marks the single most important thing on a slide.</li></ul></div>
<div class="card"><h3 style="margin-top:0">Copy</h3><ul>
<li>Titles are full-sentence insights in sentence case, no full stop.</li>
<li>Never invent customers, metrics, ROI or team credentials. Use [placeholders] and “illustrative”.</li>
<li>Separate built from planned in every claim. Planned prices say “planned”.</li>
<li>Cite source and month on the slide.</li></ul></div>
<div class="card"><h3 style="margin-top:0">Presenting</h3><ul>
<li>Use PowerPoint for Microsoft 365 or 2019+ on Windows or Mac for Morph and video.</li>
<li>Install Manrope and Inter first (source/fonts).</li>
<li>Rehearse with the presenter script; about 35 minutes plus questions.</li>
<li>For email, send the PDF; for meetings, present the PPTX.</li></ul></div></div>
<div style="margin-top:6mm" class="two"><img class="sl" src="${slide(1)}"><img class="sl" src="${slide(23)}"></div>
<div class="ft">BeatMind AI · Presentation design guide</div><div class="pn">8</div></section>
</body></html>`;

(async () => {
  const tmp = path.join(path.dirname(outPdf), '_guide.html');
  fs.writeFileSync(tmp, html);
  const b = await chromium.launch();
  const p = await b.newPage();
  await p.goto('file://' + path.resolve(tmp), { waitUntil: 'load' });
  await p.evaluate(() => document.fonts.ready);
  await p.pdf({ path: outPdf, width: '297mm', height: '210mm', printBackground: true });
  await b.close();
  fs.unlinkSync(tmp);
  console.log('wrote', outPdf);
})();
