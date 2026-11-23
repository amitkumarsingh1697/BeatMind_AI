// Renders every generated visual asset for the BeatMind AI client deck.
const FONT_DIR = 'file://' + (process.env.FONT_DIR || require('path').join(require('os').homedir(), '.fonts'));
// Real product screenshots (assets/crops) are placed into browser / phone frames
// with CSS 3D perspective, soft shadows and glow, then exported as PNG.
// Also renders the cinematic cover loop (frames -> MP4 via ffmpeg).
//
// Usage: node src/render_assets.js [--video]
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const CROPS = path.join(ROOT, 'assets', 'crops');
const OUT = path.join(ROOT, 'assets', 'generated');
fs.mkdirSync(OUT, { recursive: true });
const url = (f) => 'file://' + path.join(CROPS, f);
// Pages are loaded from a file:// URL so local images and fonts are allowed to load.
async function load(page, html) {
  const tmp = path.join(OUT, '_page.html');
  fs.writeFileSync(tmp, html);
  await page.goto('file://' + tmp, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(() => Promise.all([...document.images].map((i) => i.complete ? 0 : new Promise((r) => { i.onload = i.onerror = r; }))));
}

// ---- Design tokens (mirrors the deck theme) ----
const T = {
  night: '#07050F', night2: '#0E0A1C', surface: '#151125',
  violet: '#8B7BFF', violetDeep: '#5B3DF5', rani: '#FF5C93', marigold: '#FFB020', cyan: '#5CE1E6',
};

const FONT = `
@font-face{font-family:Manrope;src:url(${FONT_DIR}/Manrope_800ExtraBold.ttf);font-weight:800}
@font-face{font-family:Manrope;src:url(${FONT_DIR}/Manrope_600SemiBold.ttf);font-weight:600}
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:transparent;font-family:Manrope,Inter,sans-serif}
`;

// ---------- device frames ----------
function browser(img, w, opts = {}) {
  return `<div class="browser" style="width:${w}px">
    <div class="bar"><i></i><i></i><i></i><span class="url">&#128274;&nbsp; BeatMind AI${opts.title ? ' · ' + opts.title : ''}</span></div>
    <img src="${url(img)}" style="width:100%;display:block">
    <div class="gloss"></div></div>`;
}
function phone(img, w) {
  return `<div class="phone" style="width:${w}px"><div class="screen"><img src="${url(img)}" style="width:100%;display:block"><div class="island"></div></div><div class="gloss"></div></div>`;
}
function card(img, w) {
  return `<div class="card" style="width:${w}px"><img src="${url(img)}" style="width:100%;display:block"><div class="gloss"></div></div>`;
}
const DEVICE_CSS = `
.browser{border-radius:18px;background:#120E1E;overflow:hidden;position:relative;
  box-shadow:0 0 0 1.5px rgba(180,168,255,.28),0 50px 90px rgba(0,0,0,.65),0 0 120px rgba(139,123,255,.28)}
.browser .bar{height:40px;background:linear-gradient(#1C1730,#151125);display:flex;align-items:center;gap:9px;padding:0 16px;border-bottom:1px solid rgba(255,255,255,.06)}
.browser .bar i{width:12px;height:12px;border-radius:50%;background:#3A3350;display:block}
.browser .bar i:nth-child(1){background:#FF5C93}.browser .bar i:nth-child(2){background:#FFB020}.browser .bar i:nth-child(3){background:#56D38A}
.browser .url{margin-left:18px;flex:1;max-width:46%;height:24px;border-radius:12px;background:#0C0916;color:#9E97B8;font:600 12px Manrope;display:flex;align-items:center;padding:0 12px}
.phone{border-radius:58px;padding:12px;background:linear-gradient(145deg,#3B3550,#14111D 40%,#2A2538);position:relative;
  box-shadow:0 0 0 1.5px rgba(255,255,255,.12),0 50px 90px rgba(0,0,0,.7),0 0 110px rgba(255,92,147,.25)}
.phone .screen{border-radius:46px;overflow:hidden;position:relative;background:#0B0814;padding-top:46px}
.phone .island{position:absolute;top:12px;left:50%;width:30%;height:28px;margin-left:-15%;border-radius:16px;background:#000}
.card{border-radius:22px;overflow:hidden;position:relative;background:#120E1E;
  box-shadow:0 0 0 1.5px rgba(180,168,255,.30),0 40px 80px rgba(0,0,0,.6),0 0 90px rgba(139,123,255,.25)}
.gloss{position:absolute;inset:0;pointer-events:none;border-radius:inherit;
  background:linear-gradient(115deg,rgba(255,255,255,.13) 0%,rgba(255,255,255,.03) 28%,rgba(255,255,255,0) 45%)}
.stage{perspective:2200px;display:flex;align-items:center;justify-content:center;padding:140px}
`;

async function shot(page, html, out, { w = 2400, h = 1800, scale = 1, trim = true } = {}) {
  await page.setViewportSize({ width: w, height: h });
  await load(page, `<html><head><style>${FONT}${DEVICE_CSS}</style></head><body>${html}</body></html>`);
  await page.waitForTimeout(100);
  const p = path.join(OUT, out);
  await page.screenshot({ path: p, omitBackground: true, fullPage: false });
  if (trim) execSync(`convert "${p}" -trim +repage "${p}"`);
  console.log('wrote', out);
}

// One tilted device on a transparent stage
function tilted(inner, { ry = 0, rx = 0, rz = 0 } = {}) {
  return `<div class="stage" style="width:100%;height:100%"><div style="transform:rotateX(${rx}deg) rotateY(${ry}deg) rotateZ(${rz}deg);transform-style:preserve-3d">${inner}</div></div>`;
}

// ---------- backgrounds ----------
function bgBase(variant) {
  // A quiet night-sky surface: deep gradient, faint dot grid, vignette, subtle grain
  const glows = {
    a: `radial-gradient(1100px 700px at 88% 8%, rgba(91,61,245,.30), transparent 60%),radial-gradient(900px 600px at 5% 105%, rgba(255,92,147,.10), transparent 60%)`,
    b: `radial-gradient(1000px 700px at 10% 0%, rgba(91,61,245,.26), transparent 60%),radial-gradient(900px 700px at 100% 100%, rgba(255,176,32,.08), transparent 60%)`,
    c: `radial-gradient(1400px 800px at 50% 120%, rgba(91,61,245,.30), transparent 60%)`,
  }[variant];
  return `<div style="position:fixed;inset:0;background:${glows},linear-gradient(160deg,#0B0819 0%,#07050F 55%,#06040C 100%)"></div>
  <div style="position:fixed;inset:0;background-image:radial-gradient(rgba(255,255,255,.07) 1.2px, transparent 1.4px);background-size:44px 44px;
     -webkit-mask-image:radial-gradient(ellipse at 50% 40%, #000 20%, transparent 75%)"></div>
  <div style="position:fixed;inset:0;box-shadow:inset 0 0 260px rgba(0,0,0,.75)"></div>
  <canvas id="grain" style="position:fixed;inset:0;opacity:.05"></canvas>
  <script>const c=document.getElementById('grain');c.width=innerWidth;c.height=innerHeight;const x=c.getContext('2d');const d=x.createImageData(c.width,c.height);let s=7;for(let i=0;i<d.data.length;i+=4){s=(s*16807)%2147483647;const v=s%255;d.data[i]=d.data[i+1]=d.data[i+2]=v;d.data[i+3]=255}x.putImageData(d,0,0)</script>`;
}

function orb(color, alpha = 0.55) {
  return `<div style="position:fixed;inset:0;background:radial-gradient(circle at 50% 50%, ${color} 0%, ${color.replace('1)', alpha * 0.6 + ')')} 18%, rgba(0,0,0,0) 62%)"></div>`;
}

// ---------- cover hero scene (used for still + video) ----------
function heroScene() {
  const bars = Array.from({ length: 64 }, (_, i) => {
    const d = (i * 0.125).toFixed(3);
    const amp = (0.35 + 0.65 * Math.abs(Math.sin(i * 0.37)) * (1 - Math.abs(i - 32) / 40)).toFixed(3);
    return `<b style="--a:${amp};animation-delay:-${d}s"></b>`;
  }).join('');
  return `
<style>
@keyframes floatA{0%,100%{transform:translate3d(0,0,0)}50%{transform:translate3d(0,-16px,0)}}
@keyframes floatB{0%,100%{transform:translate3d(0,0,0)}50%{transform:translate3d(0,14px,0)}}
@keyframes eq{0%,100%{transform:scaleY(calc(var(--a)*.35))}25%{transform:scaleY(var(--a))}50%{transform:scaleY(calc(var(--a)*.6))}75%{transform:scaleY(calc(var(--a)*.9))}}
@keyframes drift{0%,100%{transform:translate(0,0) scale(1)}50%{transform:translate(-60px,30px) scale(1.08)}}
@keyframes grid{from{background-position:0 0}to{background-position:0 120px}}
@keyframes ring{0%{transform:translate(-50%,-50%) scale(.6);opacity:.0}20%{opacity:.55}100%{transform:translate(-50%,-50%) scale(1.6);opacity:0}}
.scene{position:fixed;inset:0;overflow:hidden;background:linear-gradient(160deg,#0B0819 0%,#07050F 55%,#05030B 100%)}
.aur{position:absolute;border-radius:50%;filter:blur(70px);animation:drift 8s ease-in-out infinite}
.floor{position:absolute;left:-20%;right:-20%;bottom:-8%;height:52%;transform:perspective(900px) rotateX(72deg);transform-origin:50% 100%;
  background-image:linear-gradient(rgba(139,123,255,.35) 2px,transparent 2px),linear-gradient(90deg,rgba(139,123,255,.30) 2px,transparent 2px);
  background-size:120px 120px;animation:grid 8s linear infinite;-webkit-mask-image:linear-gradient(to top,#000 10%,transparent 85%)}
.eq{position:absolute;left:40%;right:0%;bottom:16%;height:190px;display:flex;align-items:flex-end;gap:7px;opacity:.85;
  -webkit-mask-image:linear-gradient(90deg,transparent,#000 15%,#000 85%,transparent)}
.eq b{flex:1;height:100%;border-radius:6px;transform-origin:50% 100%;background:linear-gradient(to top,#FFB020,#FF5C93 50%,#8B7BFF);animation:eq 2s ease-in-out infinite;box-shadow:0 0 18px rgba(255,92,147,.45)}
.eqr{position:absolute;left:40%;right:0%;bottom:calc(16% - 192px);height:190px;transform:scaleY(-1);opacity:.18;filter:blur(3px);display:flex;align-items:flex-end;gap:7px;
  -webkit-mask-image:linear-gradient(to bottom, transparent 30%, #000)}
.eqr b{flex:1;height:100%;border-radius:6px;transform-origin:50% 100%;background:linear-gradient(to top,#FFB020,#FF5C93 50%,#8B7BFF);animation:eq 2s ease-in-out infinite}
.dev{position:absolute;transform-style:preserve-3d}
.pill{position:absolute;padding:12px 22px;border-radius:999px;font:700 22px Manrope;color:#fff;background:rgba(21,17,37,.72);backdrop-filter:blur(8px);
  border:1.5px solid rgba(180,168,255,.45);box-shadow:0 18px 40px rgba(0,0,0,.5),0 0 40px rgba(139,123,255,.35);white-space:nowrap}
.pill.ok{border-color:rgba(86,211,138,.6);color:#9DF0C0;box-shadow:0 18px 40px rgba(0,0,0,.5),0 0 40px rgba(86,211,138,.25)}
.pill.gold{border-color:rgba(255,176,32,.7);color:#FFD27A;box-shadow:0 18px 40px rgba(0,0,0,.5),0 0 40px rgba(255,176,32,.3)}
.rng{position:absolute;left:73%;top:52%;width:900px;height:900px;border-radius:50%;border:2px solid rgba(139,123,255,.5);animation:ring 4s linear infinite}
</style>
<div class="scene">
  <div class="aur" style="width:900px;height:700px;right:-120px;top:-160px;background:rgba(91,61,245,.55)"></div>
  <div class="aur" style="width:700px;height:600px;right:260px;top:300px;background:rgba(255,92,147,.30);animation-delay:-3s"></div>
  <div class="aur" style="width:600px;height:400px;right:-100px;bottom:-60px;background:rgba(255,176,32,.20);animation-delay:-5s"></div>
  <div class="floor"></div>
  <div class="rng"></div><div class="rng" style="animation-delay:-2s"></div>
  <div class="eq">${bars}</div><div class="eqr">${bars}</div>
  <div style="position:absolute;left:0;right:0;top:0;bottom:0;perspective:1600px;perspective-origin:70% 45%">
    <div class="dev" style="left:76%;top:7%;transform:rotateY(-34deg) rotateX(4deg) translateZ(-300px)"><div style="animation:floatB 8s ease-in-out infinite;animation-delay:-2s">${card('certificate-card.png', 440)}</div></div>
    <div class="dev" style="left:43%;top:15%;transform:rotateY(-30deg) rotateX(6deg) rotateZ(1deg)"><div style="animation:floatA 8s ease-in-out infinite">${browser('create-vibe.png', 820, { title: 'Create' })}</div></div>
    <div class="dev" style="left:72%;top:31%;transform:rotateY(-22deg) rotateX(5deg) rotateZ(-2deg) translateZ(120px)"><div style="animation:floatB 8s ease-in-out infinite;animation-delay:-4s">${phone('ph-13-phone-song-hi.png', 290)}</div></div>
  </div>
  <div class="pill" style="left:47%;top:79%"><div style="animation:floatA 8s ease-in-out infinite;animation-delay:-1s">✦&nbsp; AI-generated music</div></div>
  <div class="pill ok" style="left:63%;top:89%">✓&nbsp; Licensed for commercial use</div>
  <div class="pill gold" style="left:88%;top:66%">₹149 / month</div>
  <div style="position:absolute;inset:0;background:linear-gradient(90deg,rgba(7,5,15,.97) 0%,rgba(7,5,15,.88) 32%,rgba(7,5,15,0) 50%)"></div>
  <div style="position:absolute;inset:0;box-shadow:inset 0 0 240px rgba(0,0,0,.8)"></div>
</div>`;
}

async function renderHero(page, { video }) {
  const W = 1920, H = 1080;
  await page.setViewportSize({ width: W, height: H });
  await load(page, `<html><head><style>${FONT}${DEVICE_CSS}</style></head><body>${heroScene()}</body></html>`);
  const setT = (t) => page.evaluate((ms) => { document.getAnimations().forEach((a) => { a.pause(); a.currentTime = ms; }); }, t);
  await setT(1500);
  // Poster still (also the PDF / no-video fallback)
  await page.screenshot({ path: path.join(OUT, 'cover_poster.jpg'), type: 'jpeg', quality: 90 });
  console.log('wrote cover_poster.jpg');
  if (!video) return;
  const fdir = path.join(ROOT, 'assets', '_frames');
  fs.rmSync(fdir, { recursive: true, force: true });
  fs.mkdirSync(fdir, { recursive: true });
  const FPS = 30, SECS = 8;
  for (let f = 0; f < FPS * SECS; f++) {
    await setT(1500 + (f * 1000) / FPS);
    await page.screenshot({ path: path.join(fdir, `f${String(f).padStart(4, '0')}.jpg`), type: 'jpeg', quality: 92 });
  }
  execSync(`ffmpeg -y -loglevel error -framerate ${FPS} -i "${fdir}/f%04d.jpg" -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 21 -preset slow -movflags +faststart "${path.join(OUT, 'cover_loop.mp4')}"`);
  fs.rmSync(fdir, { recursive: true, force: true });
  console.log('wrote cover_loop.mp4');
}

(async () => {
  const video = process.argv.includes('--video');
  const browserApp = await chromium.launch();
  const page = await browserApp.newPage({ deviceScaleFactor: 1 });
  if (process.argv.includes('--hero-only')) { await renderHero(page, { video }); await browserApp.close(); return; }

  // Backgrounds (full-bleed, 2560x1440)
  for (const v of ['a', 'b', 'c']) {
    await page.setViewportSize({ width: 2560, height: 1440 });
    await load(page, `<html><body style="margin:0">${bgBase(v)}</body></html>`);
    await page.waitForTimeout(200);
    await page.screenshot({ path: path.join(OUT, `bg_${v}.jpg`), type: 'jpeg', quality: 88 });
    console.log('wrote bg_' + v);
  }
  // Glow orbs (morph objects)
  const orbs = { violet: 'rgba(110,84,255,1)', rani: 'rgba(255,92,147,1)', marigold: 'rgba(255,176,32,1)', cyan: 'rgba(92,225,230,1)' };
  for (const [n, c] of Object.entries(orbs)) {
    await page.setViewportSize({ width: 900, height: 900 });
    await load(page, `<html><body style="margin:0;background:transparent">${orb(c)}</body></html>`);
    await page.screenshot({ path: path.join(OUT, `orb_${n}.png`), omitBackground: true });
    console.log('wrote orb_' + n);
  }

  const P2 = await browserApp.newPage({ deviceScaleFactor: 2 });
  // Tilted product screens (2x for crisp projection)
  const jobs = [
    ['dev_create_left.png', browser('create-vibe.png', 760, { title: 'Create' }), { ry: 20, rx: 4 }],
    ['dev_create_right.png', browser('create-vibe.png', 760, { title: 'Create' }), { ry: -20, rx: 4 }],
    ['dev_song_right.png', browser('song-licensed.png', 760, { title: 'Song' }), { ry: -18, rx: 4 }],
    ['dev_song_flat.png', browser('song-hi.png', 760, { title: 'Song' }), {}],
    ['dev_library_left.png', browser('library.png', 760, { title: 'Library' }), { ry: 18, rx: 4 }],
    ['dev_admin_right.png', browser('admin.png', 760, { title: 'Admin console' }), { ry: -18, rx: 4 }],
    ['dev_admin_flat.png', browser('admin.png', 760, { title: 'Admin console' }), {}],
    ['dev_pricing_left.png', browser('pricing.png', 760, { title: 'Plans' }), { ry: 18, rx: 4 }],
    ['dev_pricing_flat.png', browser('pricing.png', 760, { title: 'Plans' }), {}],
    ['dev_verify_flat.png', browser('verify.png', 760, { title: 'Verify' }), {}],
    ['dev_verify_right.png', browser('verify.png', 760, { title: 'Verify' }), { ry: -18, rx: 4 }],
    ['dev_waitlist_left.png', browser('waitlist.png', 760, { title: '' }), { ry: 18, rx: 4 }],
    ['card_certificate_left.png', card('certificate-card.png', 520), { ry: 16, rx: 3 }],
    ['card_certificate_flat.png', card('certificate-card.png', 520), {}],
    ['card_history_flat.png', card('history.png', 640), {}],
    ['card_history_right.png', card('history.png', 640), { ry: -14, rx: 3 }],
    ['card_editor_flat.png', card('editor.png', 560), {}],
    ['card_editor_left.png', card('editor.png', 560), { ry: 16, rx: 3 }],
    ['card_stems_flat.png', card('stems.png', 600), {}],
    ['card_blocked_flat.png', card('blocked.png', 640), {}],
    ['ph_create_hi.png', phone('ph-14-phone-create-hi.png', 300), { ry: 14, rx: 3 }],
    ['ph_song_hi.png', phone('ph-13-phone-song-hi.png', 300), { ry: -14, rx: 3 }],
    ['ph_song_hi_flat.png', phone('ph-13-phone-song-hi.png', 300), {}],
    ['ph_create_hi_flat.png', phone('ph-14-phone-create-hi.png', 300), {}],
    ['ph_library_hi_flat.png', phone('ph-15-phone-library-hi.png', 300), {}],
    ['ph_pricing_hi_flat.png', phone('ph-19c-phone-pricing-hi.png', 300), {}],
    ['ph_verify_hi_flat.png', phone('ph-22b-phone-verify-hi.png', 300), {}],
    ['ph_shared_hi_flat.png', phone('ph-18-phone-shared-hi.png', 300), {}],
    ['ph_create_en_flat.png', phone('ph-10-phone-create.png', 300), {}],
    ['ph_song_en_flat.png', phone('ph-11-phone-song.png', 300), {}],
  ];
  for (const [out, inner, rot] of jobs) {
    await shot(P2, tilted(inner, rot), out, { w: 1200, h: 1100 });
  }
  await renderHero(page, { video });
  await browserApp.close();
})();
