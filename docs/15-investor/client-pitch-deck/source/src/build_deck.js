// Builds BeatMind_AI_Client_Pitch_Deck.pptx (editable, 16:9) with pptxgenjs.
// Motion (Morph transitions, entrance animations, video autoplay) is added afterwards
// by postprocess.py using anim_plan.json, which this script writes.
//
// Usage: node src/build_deck.js  -> out/_raw.pptx + out/anim_plan.json
const pptxgen = require('pptxgenjs');
const path = require('path');
const fs = require('fs');
const { execSync } = require('child_process');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const sharp = require('sharp');
const Lu = require('react-icons/lu');
const SCRIPT = require('./script_content');

const ROOT = path.resolve(__dirname, '..');
const G = (f) => path.join(ROOT, 'assets', 'generated', f);
const OUTDIR = path.join(ROOT, 'out');
fs.mkdirSync(OUTDIR, { recursive: true });

// ───────────────────────── Design system ─────────────────────────
const C = {
  night: '07050F', surface: '151125', surface2: '1D1733', line: '2E2650',
  text: 'F4F1FF', muted: 'B3ABD1', dim: '7F78A0',
  violet: '8B5CF6', iris: 'B4A8FF', rani: 'FF5C93', marigold: 'FFB020', cyan: '5CE1E6', leaf: '56D38A',
};
const F = { head: 'Manrope', body: 'Inter' };
const W = 13.333, H = 7.5, M = 0.6;
// Motion system (ms). Text 600, media 900, stagger 150.
const DUR = { text: 600, media: 900, wipe: 700 };

// ───────────────────────── helpers ─────────────────────────
const sizeCache = {};
function aspect(file) {
  if (!sizeCache[file]) {
    const [w, h] = execSync(`identify -format "%w %h" "${file}[0]"`).toString().split(' ').map(Number);
    sizeCache[file] = h / w;
  }
  return sizeCache[file];
}
const iconCache = {};
async function icon(name, color, size = 256) {
  const k = name + color;
  if (iconCache[k]) return iconCache[k];
  const svg = renderToStaticMarkup(React.createElement(Lu[name], { size, color: '#' + color, strokeWidth: 1.75 }));
  const png = await sharp(Buffer.from(svg)).png().toBuffer();
  return (iconCache[k] = 'image/png;base64,' + png.toString('base64'));
}
async function logoMark() {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 32 32"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFB020"/><stop offset="0.55" stop-color="#FF5C93"/><stop offset="1" stop-color="#8B5CF6"/></linearGradient></defs><rect width="32" height="32" rx="9" fill="url(#g)"/><g fill="#1A1206"><rect x="7" y="13" width="3" height="6" rx="1.5"/><rect x="12" y="9" width="3" height="14" rx="1.5"/><rect x="17" y="6" width="3" height="20" rx="1.5"/><rect x="22" y="11" width="3" height="10" rx="1.5"/></g></svg>`;
  const png = await sharp(Buffer.from(svg)).png().toBuffer();
  fs.writeFileSync(G('logo_mark.png'), png);
  return G('logo_mark.png');
}

const pres = new pptxgen();
pres.layout = 'LAYOUT_WIDE';
pres.author = 'BeatMind AI';
pres.company = 'BeatMind AI';
pres.title = 'BeatMind AI · Client Pitch Deck';
pres.subject = 'Client and partner presentation';
pres.theme = { headFontFace: F.head, bodyFontFace: F.body };

pres.defineSlideMaster({
  title: 'BM_CONTENT',
  background: { color: C.night },
  objects: [
    { text: { text: 'BeatMind AI  ·  Client presentation  ·  Draft for discussion', options: { x: M, y: 7.03, w: 7, h: 0.3, fontFace: F.body, fontSize: 9, color: C.dim, margin: 0 } } },
  ],
  slideNumber: { x: 12.23, y: 7.03, w: 0.5, h: 0.3, fontFace: F.body, fontSize: 9, color: C.dim, align: 'right' },
});
pres.defineSlideMaster({ title: 'BM_FULL', background: { color: C.night } });

const ALT = {
  'dev_create_right.png': 'BeatMind Create screen: describe a vibe, use your lyrics or make a beat',
  'dev_create_left.png': 'BeatMind Create screen in a browser window',
  'dev_admin_right.png': 'BeatMind admin console showing takedowns and appeals',
  'card_certificate_flat.png': 'Song licence and rights certificate for the Hindi song Raat Ka Safar',
  'ph_song_hi.png': 'Hindi song page on a phone with take player and AI label',
  'ph_song_hi_flat.png': 'Hindi song page on a phone with two takes',
  'ph_create_hi.png': 'Hindi Create screen on a phone',
  'ph_create_hi_flat.png': 'Hindi Create screen on a phone',
  'ph_pricing_hi_flat.png': 'Hindi plans screen on a phone with prices in rupees',
  'ph_library_hi_flat.png': 'Hindi library screen on a phone',
  'ph_verify_hi_flat.png': 'Hindi verify page on a phone',
  'card_blocked_flat.png': 'Refusal message: BeatMind cannot make songs that name real artists or copy their voice',
  'card_history_flat.png': 'Song history panel: a tamper-evident record of human and AI steps',
  'cover_poster.jpg': 'BeatMind product screens floating over an equaliser',
};
const PLAN = [];
let cur; // { slide, anims, n }
let uid = 0;
function nm(base) { return `${base}_${++uid}`; }
function anim(name, effect, delay, dur) {
  cur.anims.push({ name, effect, delay, dur: dur || (effect === 'wipe' ? DUR.wipe : effect === 'zoom' || effect === 'media' ? DUR.media : DUR.text) });
}

// Ambient glow orbs. Same names on every slide so Morph drifts them between positions.
const ORB_SETS = [
  [[8.6, -2.2, 7.5], [10.5, 3.8, 5.0], [-2.5, 4.5, 5]],
  [[7.8, -2.8, 7.5], [-2.4, 3.8, 5.5], [10.8, 5.0, 4]],
  [[-2.8, -2.6, 7.0], [10.4, 3.2, 5.0], [5.0, 5.8, 4]],
  [[9.2, -3.0, 7.5], [-1.8, 4.2, 5.5], [6.0, 5.5, 4]],
];
function orbs(set, k = 0) {
  const s = ORB_SETS[set % ORB_SETS.length];
  const names = ['!!orbViolet', '!!orbRani', '!!orbMarigold'];
  const files = ['orb_violet.png', 'orb_rani.png', 'orb_marigold.png'];
  const tr = [10, 35, 55];
  s.forEach(([x, y, d], i) => {
    cur.slide.addImage({ path: G(files[i]), x: x + k * 0.3, y: y + k * 0.2, w: d, h: d, transparency: tr[i], objectName: names[i], altText: 'Decorative glow' });
  });
}

function newSlide(n, { master = 'BM_CONTENT', bg = 'bg_a.jpg', orbSet = n, transition = 'morph', section } = {}) {
  if (section) pres.addSection({ title: section });
  const slide = pres.addSlide({ masterName: master, sectionTitle: section || cur?.section });
  if (bg) slide.background = { path: G(bg) };
  cur = { slide, anims: [], n, section: section || cur?.section, transition };
  PLAN.push(cur);
  if (orbSet !== null && orbSet !== undefined) orbs(orbSet);
  const s = SCRIPT.find((x) => x.n === n);
  if (s) {
    slide.addNotes(
      `KEY MESSAGE: ${s.key}\n\n${s.notes}\n\nTALKING POINTS:\n- ${s.talking.join('\n- ')}\n\nTRANSITION TO NEXT: ${s.next}\n\nTIME: ${s.duration}`
    );
  }
  return slide;
}

function eyebrow(text, opts = {}) {
  const name = nm('eyebrow');
  cur.slide.addText(text, { x: M, y: 0.5, w: 9, h: 0.3, fontFace: F.body, fontSize: 11, bold: true, color: C.marigold, charSpacing: 3, margin: 0, isTextBox: true, objectName: name, ...opts });
  anim(name, 'fade', 0, 500);
}
function title(text, opts = {}) {
  const name = nm('title');
  cur.slide.addText(text, { x: M, y: 0.82, w: 11.6, h: 1.15, fontFace: F.head, fontSize: 28, bold: true, color: C.text, margin: 0, valign: 'top', isTextBox: true, lineSpacingMultiple: 0.95, objectName: name, ...opts });
  anim(name, 'float', 100);
}
function text(t, o, effect = 'float', delay = 300) {
  const name = nm(o.nameBase || 'txt');
  delete o.nameBase;
  cur.slide.addText(t, { fontFace: F.body, fontSize: 14, color: C.muted, margin: 0, isTextBox: true, valign: 'top', objectName: name, ...o });
  if (effect) anim(name, effect, delay, o.dur);
  return name;
}
function glass(x, y, w, h, o = {}) {
  const name = nm(o.nameBase || 'card');
  cur.slide.addShape(pres.ShapeType.roundRect, {
    x, y, w, h, rectRadius: o.r ?? 0.14,
    fill: { color: o.fill || 'FFFFFF', transparency: o.ft ?? 94 },
    line: { color: o.line || 'FFFFFF', transparency: o.lt ?? 84, width: o.lw ?? 0.75, dashType: o.dash },
    shadow: o.noShadow ? undefined : { type: 'outer', color: '000000', blur: 18, offset: 6, angle: 90, opacity: 0.45 },
    objectName: name,
  });
  return name;
}
async function img(file, x, y, w, o = {}) {
  const h = o.h || w * aspect(file);
  const name = nm(o.nameBase || 'img');
  cur.slide.addImage({ path: file, x, y, w, h, objectName: name, transparency: o.transparency, altText: ALT[path.basename(file)] || 'BeatMind AI product screen' });
  if (o.effect !== null) anim(name, o.effect || 'zoom', o.delay ?? 0, o.dur);
  return { name, h };
}
async function ico(n, color, x, y, s = 0.42, delay = 300, effect = 'fade') {
  const name = nm('icon');
  cur.slide.addImage({ data: await icon(n, color), x, y, w: s, h: s, objectName: name, altText: 'Icon' });
  if (effect) anim(name, effect, delay);
  return name;
}
function circle(x, y, d, color, label, o = {}) {
  const name = nm(o.nameBase || 'dot');
  cur.slide.addText(label || '', { shape: pres.ShapeType.ellipse, x, y, w: d, h: d, fill: { color, transparency: o.ft ?? 0 }, line: { color: o.lc || color, width: o.lw ?? 0 },
    fontFace: F.head, fontSize: o.fs || 14, bold: true, color: o.tc || C.night, align: 'center', valign: 'middle', margin: 0, objectName: name });
  if (o.effect !== null) anim(name, o.effect || 'zoom', o.delay ?? 300, 450);
  return name;
}
function source(t, y = 6.68) {
  text(t, { x: M, y, w: 12.1, h: 0.3, fontSize: 9, color: C.dim, nameBase: 'source' }, 'fade', 1800);
}
function chip(t, x, y, w, color, o = {}) {
  const name = nm('chip');
  cur.slide.addText(t, { shape: pres.ShapeType.roundRect, rectRadius: 0.17, x, y, w, h: o.h || 0.36, fill: { color, transparency: o.ft ?? 86 }, line: { color, transparency: o.lt ?? 40, width: 0.75 },
    fontFace: F.body, fontSize: o.fs || 11, bold: true, color: o.tc || color, align: 'center', valign: 'middle', margin: 0, objectName: name });
  if (o.effect !== null) anim(name, o.effect || 'fade', o.delay ?? 900);
  return name;
}
function arrow(x, y, w, color = C.iris, delay = 500) {
  const name = nm('arrow');
  cur.slide.addShape(pres.ShapeType.line, { x, y, w, h: 0, line: { color, width: 1.5, endArrowType: 'triangle' }, objectName: name });
  anim(name, 'wipe', delay);
  return name;
}

// ───────────────────────── slides ─────────────────────────
(async () => {
  const LOGO = await logoMark();

  // 1 ── Cover
  let s = newSlide(1, { master: 'BM_FULL', bg: null, orbSet: null, transition: 'fadeBlack', section: 'Act 1 · The opportunity' });
  s.background = { color: C.night };
  s.addMedia({ type: 'video', path: G('cover_loop.mp4'), cover: 'data:image/jpeg;base64,' + fs.readFileSync(G('cover_poster.jpg')).toString('base64'), x: 0, y: 0, w: W, h: H, objectName: 'heroVideo' });
  cur.video = 'heroVideo';
  s.addImage({ path: LOGO, x: M, y: 0.6, w: 0.5, h: 0.5, objectName: 'logo', altText: 'BeatMind AI logo' });
  anim('logo', 'fade', 200);
  text([{ text: 'BeatMind ', options: { color: C.text } }, { text: 'AI', options: { color: C.marigold } }], { x: 1.22, y: 0.6, w: 3, h: 0.5, fontFace: F.head, fontSize: 18, bold: true, valign: 'middle', nameBase: 'wordmark' }, 'fade', 250);
  text('CLIENT PRESENTATION  ·  NOVEMBER 2026', { x: M, y: 2.2, w: 6, h: 0.3, fontSize: 11, bold: true, color: C.marigold, charSpacing: 3 }, 'fade', 400);
  text([
    { text: 'Songs in your language, at your price, ', options: { color: C.text, breakLine: false } },
    { text: 'that you own.', options: { color: C.marigold } },
  ], { x: M, y: 2.6, w: 6.1, h: 2.05, fontFace: F.head, fontSize: 40, bold: true, lineSpacingMultiple: 0.92, nameBase: 'heroTitle' }, 'float', 600);
  text('An AI song studio for India’s creators: original music from a vibe or your own lyrics, labelled, licensed and provable from the first second.',
    { x: M, y: 4.85, w: 5.5, h: 0.95, fontSize: 16, color: C.muted, lineSpacingMultiple: 1.1 }, 'float', 900);
  text('Prepared for [Partner name]   ·   Presented by [Name, role]', { x: M, y: 6.6, w: 6, h: 0.3, fontSize: 11, color: C.dim }, 'fade', 1200);

  // 2 ── Executive summary
  newSlide(2, { orbSet: 0 });
  eyebrow('01  ·  EXECUTIVE SUMMARY');
  title('A song studio for the creators today’s AI music tools priced out', { w: 11 });
  text([
    { text: 'BeatMind AI turns a vibe or your own lyrics into an original song in about a minute, in ', options: {} },
    { text: 'Hindi, Hinglish or English', options: { color: C.text, bold: true } },
    { text: ', at a ', options: {} },
    { text: 'local price', options: { color: C.text, bold: true } },
    { text: ', and gives paid users ', options: {} },
    { text: 'ownership plus a certificate that proves it', options: { color: C.text, bold: true } },
    { text: '.', options: {} },
  ], { x: M, y: 2.3, w: 4.3, h: 2.8, fontSize: 19, color: C.muted, lineSpacingMultiple: 1.2, fontFace: F.head }, 'float', 250);
  chip('STATUS  ·  Working product after 6 sprints  ·  Closed beta planned for January 2027', M, 5.55, 4.3, C.marigold, { h: 0.62, fs: 11, delay: 1300 });
  const tiles = [
    ['LuSparkles', C.marigold, 'WHAT IT IS', 'Type a vibe or paste lyrics. Get two takes in about a minute. Keep one, edit it, download it, share it.'],
    ['LuUsers', C.rani, 'WHO IT SERVES', 'Short-form and YouTube creators first, then lyricists and beat makers. India first, then price-sensitive markets.'],
    ['LuTrendingUp', C.iris, 'WHY IT MATTERS', 'Leading tools cost Indian creators about 5x a music subscription, lack native Hindi and blur ownership. AI labelling is now law.'],
    ['LuHandshake', C.cyan, 'WHAT WE PROPOSE', 'A scoped pilot with your creators inside the January 2027 closed beta, judged on criteria we agree up front.'],
  ];
  for (let i = 0; i < 4; i++) {
    const [ic, col, lab, body] = tiles[i];
    const x = 5.3 + (i % 2) * 3.82, y = 2.3 + Math.floor(i / 2) * 2.2, d = 450 + i * 150;
    const g = glass(x, y, 3.6, 2.0); anim(g, 'zoom', d);
    await ico(ic, col, x + 0.28, y + 0.28, 0.42, d + 100);
    text(lab, { x: x + 0.85, y: y + 0.33, w: 2.5, h: 0.32, fontSize: 11, bold: true, color: col, charSpacing: 2 }, 'fade', d + 100);
    text(body, { x: x + 0.28, y: y + 0.85, w: 3.1, h: 1.05, fontSize: 13, color: C.text, lineSpacingMultiple: 1.08 }, 'fade', d + 150);
  }

  // 3 ── The world today
  newSlide(3, { bg: 'bg_b.jpg', orbSet: 1 });
  eyebrow('02  ·  THE WORLD TODAY');
  title('Creators need fresh music every week, and the rules for AI music just changed', { w: 11.2 });
  const stats = [
    ['518M', C.marigold, 'YouTube users in India', 'The largest YouTube audience anywhere, and every Short and Reel needs music.', 'SocialPilot citing Statista, Apr 2026'],
    ['>50%', C.rani, 'of new daily Deezer uploads were fully AI', 'About 90,000 a day at the June 2026 peak. Platforms now filter and label AI music.', 'Deezer newsroom, Jul 2026'],
    ['20 Feb', C.iris, 'India requires AI labelling', 'Amended IT Rules (2026) mandate labels for AI-generated content and fast takedowns.', 'Hogan Lovells, Feb 2026'],
    ['2 Aug', C.cyan, 'EU AI Act Article 50 applies', 'Transparency duties for AI-generated audio take effect across the EU in 2026.', 'Cloud Security Alliance, Jul 2026'],
  ];
  for (let i = 0; i < 4; i++) {
    const [big, col, lab, body, src] = stats[i];
    const x = M + i * 3.04, y = 2.35, d = 300 + i * 180;
    const g = glass(x, y, 2.84, 3.55); anim(g, 'float', d);
    text(big, { x: x + 0.28, y: y + 0.3, w: 2.4, h: 0.85, fontFace: F.head, fontSize: 40, bold: true, color: col }, 'float', d + 80);
    text(lab, { x: x + 0.28, y: y + 1.2, w: 2.35, h: 0.62, fontSize: 14, bold: true, color: C.text, lineSpacingMultiple: 1.0 }, 'fade', d + 120);
    text(body, { x: x + 0.28, y: y + 1.88, w: 2.35, h: 1.1, fontSize: 12, color: C.muted, lineSpacingMultiple: 1.08 }, 'fade', d + 160);
    text(src, { x: x + 0.28, y: y + 3.08, w: 2.35, h: 0.3, fontSize: 9, color: C.dim }, 'fade', d + 200);
  }
  text([{ text: 'The opportunity:  ', options: { color: C.marigold, bold: true } }, { text: 'music that is original, affordable, in the creator’s language, and labelled by design.', options: { color: C.text } }],
    { x: M, y: 6.15, w: 12.1, h: 0.4, fontSize: 15 }, 'fade', 1300);

  // 4 ── The problem
  newSlide(4, { orbSet: 2 });
  eyebrow('03  ·  THE PROBLEM');
  title('Today’s AI music tools were built for someone else’s creator');
  const stages = ['Idea', 'Create', 'Download', 'Publish', 'Earn'];
  const sx = [0.95, 3.8, 6.65, 9.5, 12.35];
  const ly = 2.75;
  const ln = nm('journey');
  s = cur.slide;
  s.addShape(pres.ShapeType.line, { x: sx[0], y: ly, w: sx[4] - sx[0], h: 0, line: { color: C.iris, width: 1.5, transparency: 30 }, objectName: ln });
  anim(ln, 'wipe', 300, 1000);
  stages.forEach((st, i) => {
    circle(sx[i] - 0.13, ly - 0.13, 0.26, C.iris, '', { delay: 350 + i * 180 });
    text(st, { x: sx[i] - 0.8, y: ly - 0.62, w: 1.6, h: 0.32, fontSize: 13, bold: true, color: C.text, align: 'center' }, 'fade', 350 + i * 180);
  });
  const pains = [
    ['LuIndianRupee', 'Priced in dollars', 'Suno Pro costs about ₹999 a month in India, five times Spotify Premium at ₹199.', 'OpenTheRank; Selectra, Oct 2026'],
    ['LuLanguages', 'Not in their language', 'Suno and Udio do not take native Hindi prompts. Google’s Lyria 3 does, and is the main threat in India.', 'Free Press Journal, Jul 2026'],
    ['LuLock', 'Unclear ownership', 'Udio does not allow downloads. Suno caps paid downloads and says users are “generally not considered the owner”.', 'MBW, Apr 2026; Suno, Oct 2026'],
    ['LuTriangleAlert', 'Claims after publishing', 'Copyright claims on videos are a reported creator pain, and no full-song generator offers claim clearance.', 'BeatMind market research, Oct 2026'],
  ];
  for (let i = 0; i < 4; i++) {
    const mx = (sx[i] + sx[i + 1]) / 2, d = 1200 + i * 220;
    circle(mx - 0.2, ly - 0.2, 0.4, C.rani, '!', { tc: 'FFFFFF', fs: 15, delay: d });
    const x = mx - 1.4, y = 3.35;
    const g = glass(x, y, 2.8, 3.05, { line: C.rani, lt: 60 }); anim(g, 'float', d + 80);
    await ico(pains[i][0], C.rani, x + 0.25, y + 0.25, 0.38, d + 120);
    text(pains[i][1], { x: x + 0.25, y: y + 0.72, w: 2.35, h: 0.36, fontSize: 15, bold: true, color: C.text, fontFace: F.head }, 'fade', d + 140);
    text(pains[i][2], { x: x + 0.25, y: y + 1.12, w: 2.35, h: 1.45, fontSize: 12, color: C.muted, lineSpacingMultiple: 1.08 }, 'fade', d + 160);
    text(pains[i][3], { x: x + 0.25, y: y + 2.65, w: 2.35, h: 0.28, fontSize: 9, color: C.dim }, 'fade', d + 180);
  }
  text('The result: creators overpay, settle for generic stock music, or publish songs they cannot prove they may use.', { x: M, y: 6.6, w: 12.1, h: 0.32, fontSize: 13, color: C.text, italic: true }, 'fade', 2400);

  // 5 ── Cost of the problem
  newSlide(5, { bg: 'bg_b.jpg', orbSet: 3 });
  eyebrow('04  ·  THE COST OF THE PROBLEM');
  title('For an Indian creator, the leading AI tool costs five times a music subscription', { w: 11.6 });
  const chartName = nm('chart');
  cur.slide.addChart(pres.ChartType.bar, [
    { name: 'Music subscription', labels: ['BeatMind Pro*', 'BeatMind Creator*', 'Suno Pro', 'Spotify Premium'], values: [0, 0, 0, 199] },
    { name: 'Leading AI music tool', labels: ['BeatMind Pro*', 'BeatMind Creator*', 'Suno Pro', 'Spotify Premium'], values: [0, 0, 999, 0] },
    { name: 'BeatMind (planned)', labels: ['BeatMind Pro*', 'BeatMind Creator*', 'Suno Pro', 'Spotify Premium'], values: [349, 149, 0, 0] },
  ], {
    x: M, y: 2.2, w: 6.5, h: 3.9, barDir: 'bar', barGrouping: 'stacked', barGapWidthPct: 55,
    chartColors: [C.iris, C.rani, C.marigold], showLegend: false,
    showValue: true, dataLabelPosition: 'inEnd', dataLabelFormatCode: '"₹"#,##0;;', dataLabelColor: C.night, dataLabelFontSize: 12, dataLabelFontBold: true, dataLabelFontFace: F.body,
    catAxisLabelColor: C.text, catAxisLabelFontSize: 13, catAxisLabelFontFace: F.body, catAxisLineShow: false,
    valAxisHidden: true, valGridLine: { style: 'none' }, catGridLine: { style: 'none' }, valAxisMaxVal: 1100,
    showTitle: true, title: 'Monthly price in India (₹)', titleColor: C.muted, titleFontSize: 12, titleFontFace: F.body,
    objectName: chartName,
  });
  anim(chartName, 'fade', 300, 900);
  const costs = [
    ['LuWallet', C.marigold, 'Money', 'A US-priced subscription paid out of creator earnings in rupees.'],
    ['LuClock', C.iris, 'Time', 'English prompts for Hindi songs, re-rolled takes, and videos re-edited after a claim.'],
    ['LuCoins', C.rani, 'Revenue', 'Monetisation can be held while a copyright claim is disputed.'],
    ['LuScale', C.cyan, 'Compliance risk', 'Publishing AI music without a label or proof of licence, now a legal requirement in India and the EU.'],
  ];
  for (let i = 0; i < 4; i++) {
    const y = 2.3 + i * 0.98, d = 700 + i * 180;
    const g = glass(7.55, y, 5.2, 0.82); anim(g, 'float', d);
    await ico(costs[i][0], costs[i][1], 7.75, y + 0.2, 0.42, d + 60);
    text([{ text: costs[i][2] + '   ', options: { bold: true, color: C.text } }, { text: costs[i][3], options: {} }], { x: 8.35, y: y + 0.08, w: 4.25, h: 0.68, fontSize: 12.5, valign: 'middle', lineSpacingMultiple: 1.05 }, 'fade', d + 80);
  }
  text('Illustrative, qualitative costs: we have not yet measured time or revenue loss for this audience. The pilot measures them for your creators.', { x: 7.55, y: 6.2, w: 5.2, h: 0.42, fontSize: 10, italic: true, color: C.muted }, 'fade', 1600);
  source('*Planned BeatMind launch prices incl. GST, to be validated in the beta. Suno: OpenTheRank (Oct 2026). Spotify: Selectra (2025–26).');

  // 6 ── Introducing
  newSlide(6, { bg: 'bg_c.jpg', orbSet: 0, section: 'Act 2 · Our solution' });
  eyebrow('05  ·  INTRODUCING BEATMIND AI');
  await img(G('dev_create_right.png'), 6.35, 1.15, 6.1, { effect: 'zoom', delay: 0, dur: 1100 });
  await img(G('ph_song_hi.png'), 10.85, 2.45, 1.8, { effect: 'zoom', delay: 300, dur: 1100 });
  text([{ text: 'Describe it. Hear two takes. Keep one. ', options: { color: C.text } }, { text: 'Own it.', options: { color: C.marigold } }],
    { x: M, y: 1.45, w: 5.6, h: 2.6, fontFace: F.head, fontSize: 42, bold: true, lineSpacingMultiple: 0.95, nameBase: 'bigline' }, 'float', 600);
  text('BeatMind AI is a song studio for creators: original songs and beats from a vibe or your own lyrics, in your language, at a local price, labelled and licensed from the first second.',
    { x: M, y: 4.2, w: 5.3, h: 1.3, fontSize: 15.5, color: C.muted, lineSpacingMultiple: 1.15 }, 'float', 900);
  chip('Hindi · Hinglish · English', M, 5.8, 2.2, C.marigold, { delay: 1200 });
  chip('Two takes in about a minute', M + 2.35, 5.8, 2.35, C.rani, { delay: 1350 });
  chip('A licence certificate per song', M, 6.3, 2.75, C.iris, { delay: 1500 });

  // 7 ── Experience architecture
  newSlide(7, { orbSet: 1 });
  eyebrow('06  ·  HOW THE EXPERIENCE FITS TOGETHER');
  title('Three ways in, one owned and provable song out');
  const colX = (i) => 0.6 + i * 2.47;
  const cw = 2.15, cy = 2.15, chh = 2.95;
  const heads = [['1', 'Start', C.marigold], ['2', 'Safety check', C.rani], ['3', 'Two takes', C.iris], ['4', 'Keep and edit', C.cyan], ['5', 'Own and use', C.leaf]];
  const bodies = [
    null,
    'Moderation refuses prompts that name real artists or copy their voice or style.',
    'About a minute per request. Pick the take you like. Failed takes are refunded.',
    'Trim, fade, extend the end, remake a section. Every edit is a new version.',
    'MP3 for everyone; WAV and four stems on paid plans. Share link and public song page.',
  ];
  const icons7 = [null, 'LuShieldCheck', 'LuAudioWaveform', 'LuScissors', 'LuDownload'];
  for (let i = 0; i < 5; i++) {
    const x = colX(i), d = 300 + i * 260;
    const g = glass(x, cy, cw, chh); anim(g, 'float', d);
    circle(x + 0.22, cy + 0.22, 0.42, heads[i][2], heads[i][0], { delay: d + 60, fs: 13 });
    text(heads[i][1], { x: x + 0.75, y: cy + 0.24, w: 1.35, h: 0.38, fontSize: 14, bold: true, color: C.text, fontFace: F.head, valign: 'middle' }, 'fade', d + 80);
    if (i === 0) {
      const ins = [['LuSparkles', 'Describe a vibe'], ['LuPenLine', 'Use my lyrics, or let AI write them'], ['LuDisc3', 'Make a beat: genre, BPM, key, instruments']];
      for (let j = 0; j < 3; j++) {
        const yy = cy + 0.85 + j * 0.68;
        const gg = glass(x + 0.15, yy, cw - 0.3, 0.58, { ft: 90, lt: 75, noShadow: true, r: 0.1 }); anim(gg, 'fade', d + 120 + j * 80);
        await ico(ins[j][0], C.marigold, x + 0.25, yy + 0.15, 0.28, d + 120 + j * 80);
        text(ins[j][1], { x: x + 0.6, y: yy + 0.04, w: 1.35, h: 0.5, fontSize: 10.5, color: C.text, valign: 'middle', lineSpacingMultiple: 0.95 }, 'fade', d + 140 + j * 80);
      }
    } else {
      await ico(icons7[i], heads[i][2], x + 0.22, cy + 0.9, 0.42, d + 100);
      text(bodies[i], { x: x + 0.22, y: cy + 1.45, w: cw - 0.4, h: 1.4, fontSize: 12, color: C.muted, lineSpacingMultiple: 1.08 }, 'fade', d + 120);
    }
    if (i < 4) arrow(x + cw + 0.04, cy + chh / 2, 0.24, C.iris, d + 200);
  }
  const tb = glass(0.6, 5.35, 12.03, 1.15, { fill: C.violet, ft: 80, line: C.violet, lt: 30 });
  anim(tb, 'wipe', 1800, 900);
  await ico('LuBadgeCheck', C.iris, 0.85, 5.62, 0.55, 2000);
  text([{ text: 'TRUST LAYER ON EVERY SONG\n', options: { bold: true, color: C.iris, fontSize: 11, charSpacing: 2 } },
    { text: 'AI label  ·  C2PA content credentials  ·  inaudible watermark  ·  tamper-evident history  ·  licence and rights certificate  ·  public verify page', options: { color: C.text, fontSize: 13 } }],
  { x: 1.6, y: 5.5, w: 10.9, h: 0.9, valign: 'middle', lineSpacingMultiple: 1.2, fontSize: 13 }, 'fade', 2100);

  // 8 ── Product showcase
  newSlide(8, { bg: 'bg_c.jpg', orbSet: 2 });
  eyebrow('07  ·  PRODUCT SHOWCASE');
  title('This is working software, not a concept');
  await img(G('dev_create_left.png'), 0.45, 1.95, 4.35, { effect: 'zoom', delay: 200 });
  await img(G('dev_admin_right.png'), 8.55, 1.95, 4.35, { effect: 'zoom', delay: 350 });
  await img(G('card_certificate_flat.png'), 4.95, 1.75, 3.45, { effect: 'zoom', delay: 600 });
  chip('Create: vibe, lyrics or beat', 1.25, 5.95, 2.75, C.marigold, { delay: 1300 });
  chip('Licence and rights certificate', 5.25, 5.95, 2.85, C.iris, { delay: 1400 });
  chip('Admin console: takedowns, moderation, audit log', 8.72, 5.95, 4.0, C.rani, { delay: 1500 });
  source('Screens captured from the Sprint 6 build (November 2026). Real songs come from the open ACE-Step 1.5 model on a GPU; these captures used the build’s test-tone stub.', 6.55);

  // 9 ── How it works
  newSlide(9, { bg: 'bg_b.jpg', orbSet: 3 });
  eyebrow('08  ·  HOW IT WORKS');
  title('From a feeling to a licensed song in five steps, in Hindi');
  const steps = [
    ['ph_create_hi_flat.png', 'Describe', 'Type a mood or paste lyrics in Hindi, Hinglish or English.'],
    ['ph_song_hi_flat.png', 'Listen and keep', 'Two takes in about a minute. Keep the one you like.'],
    ['ph_pricing_hi_flat.png', 'Pay locally', '₹149 or ₹349 a month by UPI, card or net banking.'],
    ['ph_library_hi_flat.png', 'Find it again', 'Your songs, searchable in Hindi or English letters.'],
    ['ph_verify_hi_flat.png', 'Share and prove', 'Anyone can check a song’s origin on the verify page.'],
  ];
  const cxs = [1.55, 4.0, 6.45, 8.9, 11.35];
  const lnm = nm('stepline');
  cur.slide.addShape(pres.ShapeType.line, { x: cxs[0], y: 5.42, w: cxs[4] - cxs[0], h: 0, line: { color: C.iris, width: 1.25, transparency: 40 }, objectName: lnm });
  anim(lnm, 'wipe', 200, 1200);
  for (let i = 0; i < 5; i++) {
    const d = 300 + i * 220;
    await img(G(steps[i][0]), cxs[i] - 0.78, 1.6, 1.56, { effect: 'float', delay: d });
    circle(cxs[i] - 0.2, 5.22, 0.4, [C.marigold, C.rani, C.iris, C.cyan, C.leaf][i], String(i + 1), { delay: d + 80, fs: 13 });
    text(steps[i][1], { x: cxs[i] - 1.15, y: 5.72, w: 2.3, h: 0.32, fontSize: 14, bold: true, color: C.text, align: 'center', fontFace: F.head }, 'fade', d + 100);
    text(steps[i][2], { x: cxs[i] - 1.12, y: 6.05, w: 2.24, h: 0.6, fontSize: 11, color: C.muted, align: 'center', lineSpacingMultiple: 1.0 }, 'fade', d + 120);
  }

  // 10 ── Capabilities mapped to problems
  newSlide(10, { orbSet: 0 });
  eyebrow('09  ·  CAPABILITIES');
  title('Every capability answers a specific creator problem');
  const hdrY = 1.85;
  text('CREATOR PROBLEM', { x: 0.85, y: hdrY, w: 2.8, h: 0.3, fontSize: 10.5, bold: true, color: C.rani, charSpacing: 2 }, 'fade', 200);
  text('WHAT BEATMIND DOES', { x: 4.0, y: hdrY, w: 5, h: 0.3, fontSize: 10.5, bold: true, color: C.marigold, charSpacing: 2 }, 'fade', 200);
  text('VALUE CREATED', { x: 9.55, y: hdrY, w: 3, h: 0.3, fontSize: 10.5, bold: true, color: C.leaf, charSpacing: 2 }, 'fade', 200);
  const rows = [
    ['Priced for US wallets', 'Purchasing-power prices in five country bands; UPI and cards via Razorpay in India, Paddle as merchant of record elsewhere', 'A plan below the price of a music subscription (₹149, planned)'],
    ['No native Hindi', 'Hindi, Hinglish and English lyrics; AI lyric writer; Hindi interface; Hindi search with English letters', 'Creators work in the language they think in'],
    ['Cannot own or prove a song', 'Paid songs belong to the user; licence and rights certificate; tamper-evident history; public verify page', 'Proof to show a platform, client or distributor'],
    ['Claims after publishing', 'Commercial-use licence on paid songs; 5 commercial songs a month on the free plan, with credit to BeatMind', 'Fewer surprises after a video goes live'],
    ['New AI-labelling rules', 'AI label, C2PA content credentials and an inaudible watermark on every file', 'Compliance by default in India and the EU'],
    ['Producers need parts', 'Beat mode with genre, BPM, key and instruments; four separated stems on paid plans', 'Drop parts into your own session'],
  ];
  for (let i = 0; i < rows.length; i++) {
    const y = 2.22 + i * 0.73, d = 350 + i * 140;
    const g = glass(M, y, 12.13, 0.63, { ft: i % 2 ? 95 : 92, noShadow: true, r: 0.1 }); anim(g, 'float', d);
    text(rows[i][0], { x: 0.85, y: y + 0.05, w: 2.9, h: 0.53, fontSize: 13, bold: true, color: C.text, valign: 'middle', fontFace: F.head }, 'fade', d + 40);
    await ico('LuArrowRight', C.dim, 3.62, y + 0.2, 0.24, d + 60);
    text(rows[i][1], { x: 4.0, y: y + 0.04, w: 5.2, h: 0.55, fontSize: 11.5, color: C.muted, valign: 'middle', lineSpacingMultiple: 1.0 }, 'fade', d + 60);
    await ico('LuArrowRight', C.dim, 9.22, y + 0.2, 0.24, d + 80);
    text(rows[i][2], { x: 9.55, y: y + 0.04, w: 3.05, h: 0.55, fontSize: 12, color: C.text, valign: 'middle', lineSpacingMultiple: 1.0 }, 'fade', d + 80);
  }
  source('All capabilities are in the Sprint 6 build. Payments run in test mode and stems use a stub until the payment accounts and the GPU worker go live (slide 21).', 6.68);

  // 11 ── UX
  newSlide(11, { bg: 'bg_b.jpg', orbSet: 1, section: 'Act 3 · User transformation' });
  eyebrow('10  ·  THE USER EXPERIENCE');
  title('Designed for a phone, in Hindi, for people who have never opened a studio', { w: 11.6 });
  await img(G('ph_create_hi.png'), 0.55, 2.3, 1.75, { effect: 'zoom', delay: 300 });
  await img(G('ph_song_hi.png'), 4.15, 2.3, 1.75, { effect: 'zoom', delay: 300 });
  await img(G('ph_library_hi_flat.png'), 2.3, 2.0, 1.85, { effect: 'zoom', delay: 100 });
  const ux = [
    ['LuLanguages', C.marigold, 'Two languages, one tap', 'Hindi and English interface. Typing “raat” in English letters finds the Hindi title रात.'],
    ['LuRefreshCw', C.rani, 'Fair credits', 'Every request makes two takes; failed takes are refunded automatically.'],
    ['LuFileCheck', C.iris, 'Rights in plain language', 'Each song says who owns it and what you may do with it.'],
    ['LuAccessibility', C.cyan, 'Accessible by default', 'axe-core checks every screen, light and dark, desktop and phone. Target: WCAG 2.2 AA.'],
    ['LuSmartphone', C.leaf, 'Works on any screen', 'Every page works at 320 px wide and at 200% zoom.'],
  ];
  for (let i = 0; i < 5; i++) {
    const y = 2.05 + i * 0.93, d = 600 + i * 170;
    await ico(ux[i][0], ux[i][1], 6.75, y + 0.08, 0.46, d);
    text([{ text: ux[i][2] + '\n', options: { bold: true, color: C.text, fontSize: 15, fontFace: F.head } }, { text: ux[i][3], options: { fontSize: 12.5 } }],
      { x: 7.45, y, w: 5.3, h: 0.85, lineSpacingMultiple: 1.05 }, 'float', d + 40);
  }

  // 12 ── Before vs after
  newSlide(12, { bg: 'bg_c.jpg', orbSet: 2 });
  eyebrow('11  ·  BEFORE AND AFTER');
  title('From five tools and a risk, to one sitting and a certificate');
  const lp = glass(M, 1.95, 5.75, 3.95, { ft: 96, lt: 88 }); anim(lp, 'fade', 200);
  const rp = glass(6.98, 1.95, 5.75, 3.95, { fill: C.marigold, ft: 93, line: C.marigold, lt: 45 }); anim(rp, 'float', 900);
  text('TODAY', { x: 0.95, y: 2.2, w: 4, h: 0.3, fontSize: 11, bold: true, color: C.dim, charSpacing: 3 }, 'fade', 250);
  text('WITH BEATMIND', { x: 7.33, y: 2.2, w: 4, h: 0.3, fontSize: 11, bold: true, color: C.marigold, charSpacing: 3 }, 'fade', 950);
  const before = ['Search stock libraries or a US-priced AI tool', 'Write English prompts for a Hindi song', 'Hope your plan allows the download', 'Unsure who owns the result', 'A claim arrives after you publish'];
  const after = ['Describe the vibe in Hindi, Hinglish or English', 'Two takes in about a minute; keep one', 'Trim, fade or extend without starting over', 'Download MP3 or WAV with a licence certificate', 'Show the certificate, history and verify page if asked'];
  for (let i = 0; i < 5; i++) {
    const y = 2.68 + i * 0.6;
    await ico('LuCircleX', C.rani, 0.95, y + 0.06, 0.3, 300 + i * 90);
    text(before[i], { x: 1.42, y, w: 4.75, h: 0.44, fontSize: 13.5, color: C.muted, valign: 'middle' }, 'fade', 300 + i * 90);
    await ico('LuCircleCheck', C.leaf, 7.33, y + 0.06, 0.3, 1000 + i * 110);
    text(after[i], { x: 7.8, y, w: 4.75, h: 0.44, fontSize: 13.5, color: C.text, valign: 'middle' }, 'fade', 1000 + i * 110);
  }
  circle(6.39, 3.63, 0.55, C.marigold, '', { delay: 700 });
  await ico('LuArrowRight', C.night, 6.51, 3.75, 0.31, 750);
  const imp = [['Easier', 'own language, on a phone', C.marigold], ['Faster', 'one sitting', C.rani], ['Safer', 'labelled, licensed, provable', C.iris], ['Affordable', 'a local price', C.cyan]];
  for (let i = 0; i < 4; i++) {
    const x = M + i * 3.06, d = 1800 + i * 150;
    const g = glass(x, 6.08, 2.9, 0.5, { fill: imp[i][2], ft: 88, line: imp[i][2], lt: 50, noShadow: true, r: 0.25 }); anim(g, 'fade', d);
    text([{ text: imp[i][0] + '  ', options: { bold: true, color: imp[i][2] } }, { text: imp[i][1], options: { color: C.text } }], { x: x + 0.1, y: 6.08, w: 2.7, h: 0.5, fontSize: 12, align: 'center', valign: 'middle' }, 'fade', d + 40);
  }
  source('Qualitative improvements, to be measured in the beta and the pilot.', 6.68);

  // 13 ── Use cases
  newSlide(13, { orbSet: 3 });
  eyebrow('12  ·  USE CASES  (ILLUSTRATIVE)');
  title('Three creators, three jobs, one studio');
  const cases = [
    ['LuPlay', C.marigold, 'Short-form creator', 'Needs a mood-matched track for a monsoon travel Reel.', 'Monsoon evening lofi with flute and soft tabla', ['Describe a vibe', 'Keep take 2 of 2', 'Download with a licence', 'Certificate ready if a claim arrives']],
    ['LuPenLine', C.rani, 'Lyricist', 'Wants to hear their Hindi lyrics as a demo to pitch.', 'रात का सफ़र, तेरे बिना… (Raat Ka Safar)', ['Use my lyrics, female voice', 'Compare two takes', 'Extend the end in the editor', 'Share a private link']],
    ['LuDisc3', C.iris, 'Beat maker', 'Wants a Bhangra beat to build a track on.', 'Bhangra · 100 BPM · A minor · dhol, tabla', ['Make a beat', 'Keep the take that grooves', 'Separate into four stems', 'Mix the stems in their own DAW']],
  ];
  for (let i = 0; i < 3; i++) {
    const x = M + i * 4.1, y = 1.95, d = 300 + i * 220, col = cases[i][1];
    const g = glass(x, y, 3.88, 4.25); anim(g, 'float', d);
    circle(x + 0.3, y + 0.3, 0.6, col, '', { delay: d + 60, ft: 80, lc: col, lw: 1 });
    await ico(cases[i][0], col, x + 0.44, y + 0.44, 0.32, d + 80);
    text(cases[i][2], { x: x + 1.08, y: y + 0.32, w: 2.6, h: 0.55, fontSize: 17, bold: true, color: C.text, fontFace: F.head, valign: 'middle' }, 'fade', d + 80);
    text(cases[i][3], { x: x + 0.3, y: y + 1.08, w: 3.3, h: 0.62, fontSize: 12.5, color: C.muted, lineSpacingMultiple: 1.05 }, 'fade', d + 100);
    const pc = glass(x + 0.3, y + 1.78, 3.28, 0.6, { fill: col, ft: 88, line: col, lt: 50, noShadow: true, r: 0.1 }); anim(pc, 'fade', d + 120);
    text('“' + cases[i][4] + '”', { x: x + 0.42, y: y + 1.8, w: 3.05, h: 0.56, fontSize: 11.5, color: C.text, italic: true, valign: 'middle', fontFace: i === 1 ? 'Noto Sans Devanagari' : F.body }, 'fade', d + 140);
    const st = cases[i][5].map((t, j) => ({ text: `${j + 1}   ${t}`, options: { breakLine: j < 3, paraSpaceAfter: 6 } }));
    text(st, { x: x + 0.3, y: y + 2.6, w: 3.3, h: 1.7, fontSize: 12.5, color: C.text }, 'fade', d + 180);
  }
  source('Illustrative scenarios built from prompts used in the Sprint 6 build and the Raat Ka Safar demo song. Not customer case studies.', 6.68);

  // 14 ── Business model
  newSlide(14, { bg: 'bg_b.jpg', orbSet: 0, section: 'Act 4 · Business value' });
  eyebrow('13  ·  BUSINESS MODEL');
  title('Open models make a ₹149 plan viable, if costs hold');
  const th = (t, al = 'left') => ({ text: t, options: { bold: true, color: C.marigold, fontSize: 10.5, fill: { color: C.surface2 }, align: al, charSpacing: 1 } });
  const td = (t, o = {}) => ({ text: t, options: { color: C.text, fontSize: 12, fill: { color: C.surface }, ...o } });
  const hi = { fill: { color: '3A2A12' }, bold: true, color: 'FFD27A' };
  const tbn = nm('table');
  cur.slide.addTable([
    [th('BAND'), th('EXAMPLE MARKETS'), th('CREATOR / MO', 'right'), th('PRO / MO', 'right')],
    [td('B1'), td('US, UK, EU, Australia, Japan'), td('US$8', { align: 'right' }), td('US$18', { align: 'right' })],
    [td('B2'), td('Mexico, South Korea, Gulf'), td('≈ US$4.80', { align: 'right' }), td('≈ US$10.80', { align: 'right' })],
    [td('B3'), td('Brazil, Indonesia'), td('≈ US$2.80', { align: 'right' }), td('≈ US$6.30', { align: 'right' })],
    [td('B4', hi), td('India, Philippines, Nepal, Turkey', hi), td('₹149', { ...hi, align: 'right' }), td('₹349', { ...hi, align: 'right' })],
    [td('B5'), td('Pakistan, Bangladesh, Egypt, Nigeria'), td('≈ US$0.96', { align: 'right' }), td('≈ US$2.16', { align: 'right' })],
  ], { x: M, y: 2.0, w: 6.4, colW: [0.78, 2.72, 1.45, 1.45], rowH: 0.48, fontFace: F.body, border: { type: 'solid', pt: 0.75, color: C.line }, valign: 'middle', margin: [0.04, 0.1, 0.04, 0.1], objectName: tbn });
  anim(tbn, 'fade', 300, 800);
  text('Free plan: daily credits for drafts and up to 5 commercial-use songs a month with credit to BeatMind. Indian prices include GST; band factors are hypotheses tested on the waitlist.',
    { x: M, y: 5.0, w: 6.4, h: 0.75, fontSize: 11.5, color: C.muted, lineSpacingMultiple: 1.1 }, 'fade', 900);
  const ch2 = nm('chart');
  cur.slide.addChart(pres.ChartType.bar, [{ name: 'Contribution margin', labels: ['India Creator', 'India Pro', 'B1 Creator', 'B1 Pro'], values: [61, 56, 92, 91] }], {
    x: 7.35, y: 1.95, w: 5.4, h: 3.25, barDir: 'col', barGapWidthPct: 60, chartColors: [C.marigold], showLegend: false,
    showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0"%"', dataLabelColor: C.text, dataLabelFontSize: 13, dataLabelFontBold: true, dataLabelFontFace: F.body,
    catAxisLabelColor: C.muted, catAxisLabelFontSize: 11.5, catAxisLabelFontFace: F.body, catAxisLineShow: false,
    valAxisHidden: true, valAxisMaxVal: 110, valAxisMinVal: 0, valGridLine: { style: 'none' }, catGridLine: { style: 'none' },
    showTitle: true, title: 'Modelled contribution margin per paying user', titleColor: C.muted, titleFontSize: 12, titleFontFace: F.body,
    objectName: ch2,
  });
  anim(ch2, 'zoom', 600, 900);
  const facts = [['US$0.004', 'modelled GPU cost a take'], ['US$0.08', 'compute per free user a month'], ['≈40%', 'India Pro margin at US$0.006 a take']];
  for (let i = 0; i < 3; i++) {
    const x = 7.35 + i * 1.83, d = 1200 + i * 150;
    const g = glass(x, 5.3, 1.72, 1.05, { noShadow: true }); anim(g, 'float', d);
    text(facts[i][0], { x: x + 0.12, y: 5.38, w: 1.5, h: 0.42, fontSize: 17, bold: true, color: C.marigold, fontFace: F.head }, 'fade', d + 40);
    text(facts[i][1], { x: x + 0.12, y: 5.8, w: 1.52, h: 0.5, fontSize: 10, color: C.muted, lineSpacingMultiple: 1.0 }, 'fade', d + 60);
  }
  source('Modelled from hypotheses in the BeatMind pricing and financial model (Oct 2026), not measured results. GPU cost per take is the key unknown; the benchmark and the beta replace it.');

  // 15 ── Business case for a partner
  newSlide(15, { orbSet: 1 });
  eyebrow('14  ·  BUSINESS CASE FOR A PARTNER');
  title('A transparent way to judge the pilot before you scale it');
  const fw = [
    ['LuUpload', C.marigold, 'WHAT YOU BRING', ['Number of creators in the pilot', 'Tracks they need each month', 'Today’s cost per track (stock, freelance, AI tools)', 'Hours spent on music and claims']],
    ['LuTarget', C.rani, 'WHAT WE MEASURE', ['Time from idea to a usable track', 'Keep rate: takes kept vs made', 'Claims or disputes on BeatMind songs', 'Cost per usable track', 'Creator satisfaction']],
    ['LuListChecks', C.iris, 'HOW YOU DECIDE', ['Scale, adjust or stop', 'Against criteria agreed in the discovery workshop', 'Before any wider rollout']],
  ];
  for (let i = 0; i < 3; i++) {
    const x = M + i * 4.1, y = 1.95, d = 300 + i * 280, col = fw[i][1];
    const g = glass(x, y, 3.75, 2.95); anim(g, 'float', d);
    await ico(fw[i][0], col, x + 0.3, y + 0.3, 0.44, d + 60);
    text(fw[i][2], { x: x + 0.9, y: y + 0.35, w: 2.7, h: 0.36, fontSize: 11, bold: true, color: col, charSpacing: 2, valign: 'middle' }, 'fade', d + 60);
    text(fw[i][3].map((t, j, a) => ({ text: t, options: { bullet: { indent: 14 }, breakLine: j < a.length - 1, paraSpaceAfter: 5 } })),
      { x: x + 0.3, y: y + 0.95, w: 3.25, h: 1.9, fontSize: 12.5, color: C.text }, 'fade', d + 100);
    if (i < 2) arrow(x + 3.79, y + 1.47, 0.27, C.iris, d + 200);
  }
  const fb = glass(M, 5.1, 12.13, 1.35, { fill: C.violet, ft: 82, line: C.violet, lt: 35 }); anim(fb, 'wipe', 1400, 900);
  text([{ text: 'VALUE FORMULA, FILLED IN WITH YOUR NUMBERS\n', options: { bold: true, color: C.iris, fontSize: 10.5, charSpacing: 2 } },
    { text: 'Monthly value  =  (today’s cost per track − BeatMind cost per track) × tracks  +  hours saved × hourly cost', options: { color: C.text, fontSize: 15, fontFace: F.head, bold: true } }],
  { x: 0.9, y: 5.17, w: 11.6, h: 0.75, lineSpacingMultiple: 1.15 }, 'fade', 1700);
  text('Illustrative cost only: 100 creators on the Creator plan = ₹14,900 a month at the planned ₹149 price, each with 600 credits (about 300 songs) a month. We do not assume the value side; we measure it.',
    { x: 0.9, y: 5.9, w: 11.6, h: 0.5, fontSize: 11.5, color: C.muted, italic: true }, 'fade', 1900);

  // 16 ── Why different
  newSlide(16, { bg: 'bg_c.jpg', orbSet: 2 });
  eyebrow('15  ·  WHY BEATMIND IS DIFFERENT');
  title('We compete on price, language, ownership and proof, not raw model power', { w: 11.6 });
  const glow = glass(2.92, 2.03, 2.15, 4.1, { fill: C.marigold, ft: 88, line: C.marigold, lt: 25, lw: 1.25 });
  anim(glow, 'zoom', 900);
  const hc = (t, c = C.muted) => ({ text: t, options: { bold: true, color: c, fontSize: 12, align: 'center', fontFace: F.head } });
  const rc = (t) => ({ text: t, options: { bold: true, color: C.text, fontSize: 12 } });
  const y_ = (t) => ({ text: t, options: { color: C.leaf, fontSize: 11.5, align: 'center', bold: true } });
  const n_ = (t) => ({ text: t, options: { color: C.muted, fontSize: 11, align: 'center' } });
  const t16 = nm('table');
  cur.slide.addTable([
    [{ text: '', options: {} }, hc('BeatMind AI', C.marigold), hc('Suno'), hc('Udio'), hc('Google Lyria 3')],
    [rc('Price in India, per month'), y_('₹149 / ₹349 (planned)'), n_('₹999 / ₹2,999'), n_('Not checked'), n_('Not reported')],
    [rc('Native Hindi prompts'), y_('Yes (G1 test pending)'), n_('No (Hinglish works)'), n_('No'), n_('Yes')],
    [rc('Download and own paid songs'), y_('Yes'), n_('Capped; users “generally not” owners'), n_('No downloads'), n_('Not reported')],
    [rc('AI label and certificate per song'), y_('Yes'), n_('Watermark; no certificate'), n_('Not reported'), n_('SynthID watermark')],
    [rc('Public verify page'), y_('Yes'), n_('Not reported'), n_('Not reported'), n_('Not reported')],
  ], { x: M, y: 2.1, w: 8.6, colW: [2.32, 2.15, 1.55, 1.25, 1.33], rowH: 0.66, fontFace: F.body, border: { type: 'solid', pt: 0.5, color: C.line }, valign: 'middle', margin: [0.04, 0.08, 0.04, 0.1], objectName: t16 });
  anim(t16, 'fade', 300, 900);
  const sn = glass(9.55, 2.1, 3.18, 3.98, { line: C.rani, lt: 60 }); anim(sn, 'float', 1300);
  await ico('LuEye', C.rani, 9.8, 2.32, 0.4, 1400);
  text([{ text: 'Where we do not compete at launch', options: { bold: true, color: C.text, fontSize: 14, fontFace: F.head, breakLine: true, paraSpaceAfter: 10 } },
    { text: 'Raw audio quality against Suno and Lyria 3: open models are unlikely to match them at launch.', options: { breakLine: true, paraSpaceAfter: 10 } },
    { text: 'Social feeds and remix communities.', options: { breakLine: true, paraSpaceAfter: 10 } },
    { text: 'Native mobile apps (mobile web first).', options: {} }],
  { x: 9.8, y: 2.85, w: 2.75, h: 3.1, fontSize: 12, color: C.muted, lineSpacingMultiple: 1.05 }, 'fade', 1450);
  source('Competitor facts as of Oct 2026: OpenTheRank; Free Press Journal (Jul 2026); Music Business Worldwide (Apr 2026); Suno help centre; BeatMind market research. G1 = blind Hindi listening test with 30 listeners.', 6.42);

  // 17 ── Technology
  newSlide(17, { bg: 'bg_b.jpg', orbSet: 3, section: 'Act 5 · Technology, execution and trust' });
  eyebrow('16  ·  TECHNOLOGY AND ARCHITECTURE');
  title('A modular, low-cost stack that leaves a provable trail');
  const layers = [
    ['LuSmartphone', C.marigold, 'EXPERIENCE', 'Web app on Next.js 16 and React 19  ·  English and Hindi  ·  phone first  ·  public verify page  ·  admin console'],
    ['LuCloud', C.cyan, 'EDGE', 'Cloudflare CDN, WAF and bot check  ·  signed, 15-minute download links'],
    ['LuServer', C.iris, 'CORE API', 'Java 21, Spring Boot, Spring Modulith  ·  accounts, songs, credits, licences, payments, takedowns'],
    ['LuCpu', C.rani, 'GPU WORKERS', 'Python  ·  ACE-Step 1.5 open music model  ·  Demucs stems  ·  similarity screen  ·  AudioSeal watermark  ·  C2PA signing'],
    ['LuDatabase', C.leaf, 'DATA', 'PostgreSQL 16 (data, job queue, provenance ledger)  ·  Cloudflare R2 object storage'],
  ];
  for (let i = 0; i < 5; i++) {
    const y = 1.95 + i * 0.86, d = 300 + i * 160, col = layers[i][1];
    const g = glass(M, y, 8.35, 0.74, { fill: col, ft: 91, line: col, lt: 55 }); anim(g, 'float', d);
    await ico(layers[i][0], col, 0.82, y + 0.17, 0.4, d + 40);
    text(layers[i][2], { x: 1.38, y: y + 0.05, w: 1.45, h: 0.64, fontSize: 10.5, bold: true, color: col, charSpacing: 2, valign: 'middle' }, 'fade', d + 50);
    text(layers[i][3], { x: 2.85, y: y + 0.04, w: 5.95, h: 0.66, fontSize: 11.5, color: C.text, valign: 'middle', lineSpacingMultiple: 1.0 }, 'fade', d + 70);
  }
  const ig = glass(9.2, 1.95, 3.53, 4.18); anim(ig, 'float', 1200);
  text('INTEGRATIONS', { x: 9.45, y: 2.15, w: 3.1, h: 0.3, fontSize: 10.5, bold: true, color: C.marigold, charSpacing: 2 }, 'fade', 1250);
  const integ = [['Payments', 'Razorpay (UPI, cards) in India; Paddle as merchant of record elsewhere'], ['Fallback model', 'Google Lyria 3 API under a daily spend cap'], ['Observability', 'Prometheus, Grafana, Jaeger tracing'], ['Infrastructure', 'OpenTofu on Oracle Cloud and Cloudflare']];
  text(integ.map((r, j) => [{ text: r[0] + '\n', options: { bold: true, color: C.text, fontSize: 12.5 } }, { text: r[1], options: { fontSize: 11.5, breakLine: j < 3, paraSpaceAfter: 9 } }]).flat(),
    { x: 9.45, y: 2.55, w: 3.1, h: 3.5, color: C.muted, lineSpacingMultiple: 1.0 }, 'fade', 1350);
  const sb = glass(M, 6.3, 12.13, 0.42, { fill: C.violet, ft: 85, line: C.violet, lt: 50, noShadow: true, r: 0.2 }); anim(sb, 'wipe', 1700);
  text([{ text: 'SECURITY  ', options: { bold: true, color: C.iris, charSpacing: 2 } }, { text: 'Admin MFA and audit log  ·  secret scanning and dependency review in CI  ·  signed links  ·  data export and deletion in the app', options: { color: C.text } }],
    { x: 0.85, y: 6.3, w: 11.7, h: 0.42, fontSize: 11.5, valign: 'middle' }, 'fade', 1900);

  // 18 ── Trust and safety
  newSlide(18, { orbSet: 0 });
  eyebrow('17  ·  TRUST AND SAFETY');
  title('Safety and rights are product features, not footnotes');
  const b18 = await img(G('card_blocked_flat.png'), 0.75, 1.9, 4.9, { effect: 'zoom', delay: 200 });
  await img(G('card_history_flat.png'), 0.75, 1.9 + b18.h + 0.2, 4.9, { effect: 'zoom', delay: 450 });
  const sf = [
    ['LuBan', C.rani, 'No borrowed voices', 'Prompts naming real artists or copying their voice or style are refused; a person reviews within 72 hours.'],
    ['LuSearchCheck', C.marigold, 'Similarity screen', 'Takes too close to known works are withheld before the user hears them.'],
    ['LuFlag', C.iris, 'Report a song', 'Anyone can report a song; reports are acknowledged within 24 hours.'],
    ['LuGavel', C.cyan, 'Takedowns on a clock', 'Court and government orders acted on within 3 hours, as India’s IT Rules require.'],
    ['LuHistory', C.leaf, 'Tamper-evident history', 'Every step, by the person or the AI, is recorded and checkable.'],
    ['LuFingerprint', C.iris, 'Your data, your call', 'Export or delete your data from the app; deletion purges versions and stems.'],
  ];
  for (let i = 0; i < 6; i++) {
    const x = 6.45 + (i % 2) * 3.17, y = 1.9 + Math.floor(i / 2) * 1.55, d = 800 + Math.floor(i / 2) * 200 + (i % 2) * 80;
    const g = glass(x, y, 3.0, 1.4); anim(g, 'float', d);
    await ico(sf[i][0], sf[i][1], x + 0.2, y + 0.2, 0.36, d + 40);
    text(sf[i][2], { x: x + 0.68, y: y + 0.18, w: 2.2, h: 0.4, fontSize: 13, bold: true, color: C.text, fontFace: F.head, valign: 'middle' }, 'fade', d + 60);
    text(sf[i][3], { x: x + 0.2, y: y + 0.65, w: 2.65, h: 0.72, fontSize: 10.5, color: C.muted, lineSpacingMultiple: 1.0 }, 'fade', d + 80);
  }
  source('Real screens from the Sprint 6 build. Takedown and rights policies are drafts pending review by legal counsel.', 6.68);

  // 19 ── Why work with us
  newSlide(19, { bg: 'bg_c.jpg', orbSet: 1 });
  eyebrow('18  ·  WHY WORK WITH US');
  title('Six sprints of working, tested software, tied to evidence');
  const sprints = [['S0', 'Foundations and model spikes'], ['S1', 'First song end to end'], ['S2', 'Labelled, provable, downloadable'], ['S3', 'Hindi UI, AI lyrics, licences, admin'], ['S4', 'Beat mode, sharing, takedowns'], ['S5', 'UPI payments, ownership, editor, verify'], ['S6', 'International payments, stems']];
  const tx = (i) => 1.45 + i * 1.73;
  const tl = nm('timeline');
  cur.slide.addShape(pres.ShapeType.line, { x: tx(0), y: 2.35, w: tx(6) - tx(0), h: 0, line: { color: C.marigold, width: 2, transparency: 30 }, objectName: tl, });
  anim(tl, 'wipe', 200, 1300);
  for (let i = 0; i < 7; i++) {
    const d = 300 + i * 160;
    circle(tx(i) - 0.27, 2.08, 0.54, i === 6 ? C.marigold : C.surface2, sprints[i][0], { lc: C.marigold, lw: 1.25, tc: i === 6 ? C.night : C.marigold, fs: 12, delay: d });
    text(sprints[i][1], { x: tx(i) - 0.82, y: 2.72, w: 1.64, h: 0.7, fontSize: 11, color: C.text, align: 'center', lineSpacingMultiple: 1.0 }, 'fade', d + 60);
  }
  const kp = [['238', 'API tests passing', C.marigold], ['71', 'worker tests passing', C.rani], ['67', 'screens checked in the browser, no serious accessibility issues', C.iris], ['45 / 79', 'requirements traced to test cases', C.cyan]];
  for (let i = 0; i < 4; i++) {
    const x = M + i * 3.07, d = 1500 + i * 150;
    const g = glass(x, 3.75, 2.9, 1.65); anim(g, 'zoom', d);
    text(kp[i][0], { x: x + 0.25, y: 3.9, w: 2.5, h: 0.75, fontSize: 34, bold: true, color: kp[i][2], fontFace: F.head }, 'fade', d + 60);
    text(kp[i][1], { x: x + 0.25, y: 4.65, w: 2.5, h: 0.65, fontSize: 11.5, color: C.muted, lineSpacingMultiple: 1.0 }, 'fade', d + 80);
  }
  text([{ text: 'How we work:  ', options: { bold: true, color: C.marigold } }, { text: 'evidence before claims  ·  every decision logged  ·  tests, accessibility and security checks on every change', options: { color: C.text } }],
    { x: M, y: 5.6, w: 12.1, h: 0.4, fontSize: 13 }, 'fade', 2200);
  chip('[ TEAM: add names, roles and relevant experience before external use ]', M, 6.12, 7.0, C.marigold, { delay: 2300, fs: 10.5 });
  source('Source: Sprint 6 report (November 2026) and the BeatMind repository.', 6.68);

  // 20 ── Roadmap
  newSlide(20, { bg: 'bg_b.jpg', orbSet: 2 });
  eyebrow('19  ·  ROADMAP');
  title('Built today, proven in the beta, expanded gate by gate');
  const rb = nm('roadbar');
  cur.slide.addShape(pres.ShapeType.roundRect, { x: M, y: 1.92, w: 12.13, h: 0.12, rectRadius: 0.06, fill: { color: C.marigold }, line: { color: C.marigold, width: 0 }, objectName: rb + '__GRAD' });
  anim(rb + '__GRAD', 'wipe', 200, 1400);
  const road = [
    ['BUILT NOW', 'Sprints 0–6', C.leaf, false, ['Songs from a vibe, lyrics or beat', 'AI lyric writer; Hindi and English interface', 'Editor: trim, fade, extend, remake', 'Licences, certificates, verify page', 'Moderation, reports, takedowns', 'Payments (test mode); stems (stub)']],
    ['CLOSED BETA', 'January 2027 · planned', C.marigold, true, ['GPU model live', 'Hindi listening test (gate G1)', 'Razorpay and Paddle accounts live', 'Founding creators and partners', 'Measure keep rate, cost per song, conversion']],
    ['PHASE 2', 'After public launch · planned', C.rani, true, ['Mixer and DJ deck for your own songs', '“Inspired by” mode: mood and tempo only', 'Release to Spotify via a distributor', 'More Indian languages', 'Team workspaces; melody input; MIDI']],
    ['PHASE 3', 'Later · planned', C.iris, true, ['Own-voice models with identity checks', 'Licensed artist voice marketplace', 'Public API and SDKs', 'Model fine-tuned on licensed data']],
  ];
  for (let i = 0; i < 4; i++) {
    const x = M + i * 3.07, d = 400 + i * 260, col = road[i][2];
    circle(x + 0.05, 1.83, 0.3, col, '', { delay: d });
    const g = glass(x, 2.35, 2.9, 4.2, { line: col, lt: road[i][3] ? 45 : 30, dash: road[i][3] ? 'dash' : undefined, fill: road[i][3] ? 'FFFFFF' : C.leaf, ft: road[i][3] ? 95 : 90 });
    anim(g, 'float', d + 60);
    text(road[i][0], { x: x + 0.22, y: 2.5, w: 2.5, h: 0.32, fontSize: 12, bold: true, color: col, charSpacing: 2 }, 'fade', d + 80);
    text(road[i][1], { x: x + 0.22, y: 2.82, w: 2.5, h: 0.3, fontSize: 10.5, color: C.muted }, 'fade', d + 90);
    text(road[i][4].map((t, j, a) => ({ text: t, options: { bullet: { indent: 12 }, breakLine: j < a.length - 1, paraSpaceAfter: 5 } })),
      { x: x + 0.22, y: 3.25, w: 2.55, h: 3.2, fontSize: 11.5, color: C.text, lineSpacingMultiple: 1.0 }, 'fade', d + 120);
  }
  source('Solid outline = built in the current product. Dashed outline = planned; each phase opens only after its quality and business gates.', 6.68);

  // 21 ── Risks
  newSlide(21, { orbSet: 3 });
  eyebrow('20  ·  RISKS AND ASSUMPTIONS');
  title('What we still have to prove, and how we will prove it');
  const rh = (t) => ({ text: t, options: { bold: true, color: C.marigold, fontSize: 10.5, fill: { color: C.surface2 }, charSpacing: 1 } });
  const rd = (t, b) => ({ text: t, options: { color: b ? C.text : C.muted, bold: !!b, fontSize: 11.5, fill: { color: C.surface } } });
  const risks = [
    ['Hindi song quality', 'Google’s Lyria 3 already sings Hindi', 'Blind listening test, 30 listeners, against Suno and Lyria 3 (gate G1)', 'Before the beta'],
    ['Cost per song', 'Margins rest on a modelled US$0.004 a take', 'GPU benchmark, then measured beta costs', 'Before final pricing'],
    ['Training data of open models', 'Publishers’ data claims are not independently verified', 'Similarity screening now; licensed regional data after funding', 'Ongoing'],
    ['Payments live', 'Checkout runs in test mode today', 'Open Razorpay and Paddle accounts; run sandbox notifications', 'Before paid launch'],
    ['Legal terms', 'Rights and takedown terms are drafts', 'Counsel review of the ownership grant and policies', 'Before the beta'],
    ['Willingness to pay', 'Demand at local prices is a hypothesis', '20 creator interviews, waitlist price test, beta conversion', 'Dec 2026 – Feb 2027'],
  ];
  const t21 = nm('table');
  cur.slide.addTable([[rh('OPEN QUESTION'), rh('WHY IT MATTERS'), rh('HOW WE PROVE IT'), rh('WHEN')], ...risks.map((r) => [rd(r[0], true), rd(r[1]), rd(r[2]), rd(r[3])])],
    { x: M, y: 1.95, w: 12.13, colW: [2.6, 3.4, 4.43, 1.7], rowH: 0.62, fontFace: F.body, border: { type: 'solid', pt: 0.75, color: C.line }, valign: 'middle', margin: [0.04, 0.12, 0.04, 0.12], objectName: t21 });
  anim(t21, 'float', 300, 900);
  text('Naming these openly is deliberate: each one has a test, an owner and a date in the BeatMind risk register.', { x: M, y: 6.45, w: 12.1, h: 0.35, fontSize: 12.5, italic: true, color: C.text }, 'fade', 1200);

  // 22 ── Pilot
  newSlide(22, { bg: 'bg_c.jpg', orbSet: 0, section: 'Act 6 · The next step' });
  eyebrow('21  ·  IMPLEMENTATION AND ADOPTION');
  title('A 12-week pilot, from workshop to decision');
  const ph = [
    ['WEEKS 0–2', 'Discover', C.marigold, 'Workshop; choose the creator cohort; agree success criteria and the data to share.'],
    ['WEEKS 3–4', 'Onboard', C.rani, 'Invite codes; a 45-minute training in English or Hindi; help centre; support channel.'],
    ['WEEKS 5–11', 'Run', C.iris, 'Creators make songs for real content; weekly check-ins; fixes shipped weekly.'],
    ['WEEK 12', 'Decide', C.leaf, 'Read-out on the agreed measures; scale, adjust or stop.'],
  ];
  const pb = nm('pilotbar');
  cur.slide.addShape(pres.ShapeType.roundRect, { x: M, y: 2.05, w: 12.13, h: 0.1, rectRadius: 0.05, fill: { color: C.marigold }, line: { color: C.marigold, width: 0 }, objectName: pb + '__GRAD' });
  anim(pb + '__GRAD', 'wipe', 200, 1300);
  for (let i = 0; i < 4; i++) {
    const x = M + i * 3.07, d = 400 + i * 220, col = ph[i][2];
    circle(x, 1.95, 0.3, col, '', { delay: d });
    text(ph[i][0], { x, y: 2.4, w: 2.9, h: 0.3, fontSize: 11, bold: true, color: col, charSpacing: 2 }, 'fade', d + 40);
    text(ph[i][1], { x, y: 2.7, w: 2.9, h: 0.45, fontSize: 20, bold: true, color: C.text, fontFace: F.head }, 'fade', d + 60);
    text(ph[i][3], { x, y: 3.2, w: 2.85, h: 0.95, fontSize: 12, color: C.muted, lineSpacingMultiple: 1.05 }, 'fade', d + 80);
  }
  const sup = [
    ['LuLifeBuoy', C.marigold, 'Support', 'A named contact for the pilot; response times agreed in the workshop.'],
    ['LuGraduationCap', C.rani, 'Training', 'Live walkthrough plus the help centre and guides in English and Hindi.'],
    ['LuUsers', C.iris, 'Change management', 'Start with volunteer creators, share early wins, keep today’s workflow until BeatMind is proven.'],
  ];
  for (let i = 0; i < 3; i++) {
    const x = M + i * 4.1, d = 1400 + i * 180;
    const g = glass(x, 4.45, 3.9, 1.75); anim(g, 'float', d);
    await ico(sup[i][0], sup[i][1], x + 0.25, y_0(4.45) + 0.25, 0.4, d + 40);
    text(sup[i][2], { x: x + 0.8, y: 4.68, w: 2.9, h: 0.4, fontSize: 15, bold: true, color: C.text, fontFace: F.head, valign: 'middle' }, 'fade', d + 60);
    text(sup[i][3], { x: x + 0.25, y: 5.2, w: 3.45, h: 0.9, fontSize: 12, color: C.muted, lineSpacingMultiple: 1.05 }, 'fade', d + 80);
  }
  source('Illustrative plan. Dates, cohort size and success criteria are agreed together in the discovery workshop.', 6.55);

  // 23 ── Close
  newSlide(23, { master: 'BM_FULL', bg: 'cover_poster.jpg', orbSet: null });
  const veil = nm('veil');
  cur.slide.addShape(pres.ShapeType.rect, { x: 0, y: 0, w: W, h: H, fill: { color: C.night, transparency: 30 }, line: { color: C.night, width: 0 }, objectName: veil });
  cur.slide.addImage({ path: LOGO, x: M, y: 0.6, w: 0.5, h: 0.5, objectName: 'logo', altText: 'BeatMind AI logo' });
  text([{ text: 'BeatMind ', options: { color: C.text } }, { text: 'AI', options: { color: C.marigold } }], { x: 1.22, y: 0.6, w: 3, h: 0.5, fontFace: F.head, fontSize: 18, bold: true, valign: 'middle' }, null);
  text('THE NEXT STEP', { x: M, y: 1.55, w: 6, h: 0.3, fontSize: 11, bold: true, color: C.marigold, charSpacing: 3 }, 'fade', 100);
  text('Anyone, in any language, can make an original song they own and use anywhere, at a price that fits where they live.',
    { x: M, y: 1.9, w: 7.4, h: 1.65, fontFace: F.head, fontSize: 26, bold: true, color: C.text, lineSpacingMultiple: 1.0 }, 'float', 300);
  text('Let’s prove it with your creators.', { x: M, y: 3.65, w: 7.4, h: 0.6, fontFace: F.head, fontSize: 24, bold: true, color: C.marigold }, 'float', 800);
  const opts = [['1', 'Product walkthrough', '60 minutes, live'], ['2', 'Discovery workshop', 'Half a day to shape the pilot'], ['3', 'Launch partner', 'Closed beta, January 2027']];
  for (let i = 0; i < 3; i++) {
    const x = M + i * 2.8, d = 1200 + i * 200;
    const g = glass(x, 4.55, 2.65, 1.3, { fill: '151125', ft: 25, line: C.marigold, lt: 55 }); anim(g, 'zoom', d);
    circle(x + 0.2, 4.73, 0.38, C.marigold, opts[i][0], { delay: d + 40, fs: 12 });
    text(opts[i][1], { x: x + 0.7, y: 4.7, w: 1.9, h: 0.45, fontSize: 13.5, bold: true, color: C.text, fontFace: F.head, valign: 'middle' }, 'fade', d + 60);
    text(opts[i][2], { x: x + 0.2, y: 5.22, w: 2.1, h: 0.5, fontSize: 11.5, color: C.muted }, 'fade', d + 80);
  }
  text('[Name]  ·  [email]  ·  [phone]  ·  [website]', { x: M, y: 6.45, w: 8.5, h: 0.35, fontSize: 12, color: C.muted }, 'fade', 2000);

  fs.writeFileSync(path.join(OUTDIR, 'anim_plan.json'), JSON.stringify(PLAN.map((p, i) => ({ slide: i + 1, transition: p.transition, video: p.video || null, anims: p.anims })), null, 1));
  await pres.writeFile({ fileName: path.join(OUTDIR, '_raw.pptx') });
  console.log('wrote out/_raw.pptx with', PLAN.length, 'slides');
})();

function y_0(v) { return v; }
