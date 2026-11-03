// Walks the app screens (Sprints 2 to 5, English and Hindi) in a real browser against a running local stack, runs axe-core on
// each (TC-REQ-NFR-08-01: no serious or critical violations) and saves screenshots.
//
//   node e2e/ui-check.mjs [base-url] [screenshot-dir]
//
// Needs Playwright with Chromium installed (npx playwright install chromium). CI runs it against
// the Docker Compose stack (US-043, .github/workflows/ci.yml, job "Accessibility").
import { createRequire } from "node:module";
import { createHmac } from "node:crypto";
import { mkdirSync, readFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const axeSource = readFileSync(require.resolve("axe-core/axe.min.js"), "utf8");

const BASE = process.argv[2] ?? "http://localhost:3000";
const OUT = process.argv[3] ?? "e2e/screenshots";
mkdirSync(OUT, { recursive: true });
const failures = [];
// Carried from the dark-scheme walk to the admin part: the song that gets reported and its owner.
let reported = { email: "", songUrl: "", shareUrl: "" };

/** RFC 6238 code for the admin second factor (SHA-1, 6 digits, 30 s). */
function totp(base32) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of base32.replace(/[\s=]/g, "").toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 15;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1000000).padStart(6, "0");
}

async function audit(page, name) {
  await page.addScriptTag({ content: axeSource });
  const result = await page.evaluate(async () =>
    // eslint-disable-next-line no-undef
    axe.run(document, { runOnly: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] }),
  );
  const bad = result.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
  for (const v of result.violations) {
    console.log(`  [${v.impact}] ${v.id}: ${v.help} (${v.nodes.length})`);
    for (const n of v.nodes.slice(0, 3)) console.log(`      ${n.target.join(" ")} ${n.failureSummary?.split("\n")[1] ?? ""}`);
  }
  if (bad.length) failures.push(`${name}: ${bad.map((v) => v.id).join(", ")}`);
  console.log(`${name}: ${result.violations.length} violations (${bad.length} serious or critical)`);
}

async function shot(page, name, opts = {}) {
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true, ...opts });
}

const browser = await chromium.launch();
for (const scheme of ["dark", "light"]) {
  const desktop = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: scheme });
  const page = await desktop.newPage();
  const suffix = scheme === "dark" ? "" : "-light";
  const email = `ui-${scheme}-${Date.now()}@example.com`;

  await page.goto(`${BASE}/en`);
  await page.getByRole("heading", { name: "Planned prices" }).waitFor();
  await page.waitForTimeout(800);
  await shot(page, `01-waitlist${suffix}`);
  await audit(page, `waitlist (${scheme})`);

  await page.goto(`${BASE}/en/create`);
  await page.getByLabel("Email").fill(email);
  await shot(page, `02-sign-in${suffix}`);
  await audit(page, `sign-in (${scheme})`);
  await page.getByRole("button", { name: "Email me a sign-in code" }).click();
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByLabel("Date of birth").fill("1995-04-12");
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Continue" }).click();

  // A blocked request says why and offers a review (US-028).
  await page.getByRole("heading", { name: "Make a song" }).waitFor();
  await page.getByLabel("Vibe, genre, mood and instruments").fill("A sad song in the voice of Arijit Singh");
  await page.getByRole("button", { name: /Make two takes/ }).click();
  await page.getByText("We can't make this").waitFor();
  await page.getByRole("button", { name: "Ask a person to review" }).click();
  await page.getByLabel("Anything we should know? (optional)").fill("It is my own tribute song, no imitation.");
  await page.getByRole("button", { name: "Send for review" }).click();
  await page.getByText(/A person will look at it/).waitFor();
  await shot(page, `03a-blocked${suffix}`);
  await audit(page, `blocked request (${scheme})`);

  // Vibe mode first, wait for the takes.
  await page.getByRole("heading", { name: "Make a song" }).waitFor();
  await page.getByLabel("Vibe, genre, mood and instruments").fill("Monsoon evening lofi with flute and soft tabla");
  await shot(page, `03-create-vibe${suffix}`);
  await audit(page, `create (${scheme})`);
  await page.getByRole("button", { name: /Make two takes/ }).click();
  await page.getByText("Your takes are ready.").first().waitFor({ timeout: 30000 });

  // Then a Hindi song with lyrics from the AI lyric writer, edited (US-018).
  await page.getByRole("radio", { name: "Use my lyrics" }).check();
  await page.getByLabel("Vocal language").selectOption("hi");
  await page.getByRole("button", { name: "Help me write lyrics" }).click();
  await page.getByLabel("What is the song about?").fill("मुंबई की बारिश");
  await page.getByLabel("Mood (optional)").fill("सुकून भरा");
  await page.getByRole("button", { name: "Write lyrics", exact: true }).click();
  await page.getByRole("button", { name: "Use these lyrics" }).waitFor({ timeout: 30000 });
  await shot(page, `03b-lyric-writer${suffix}`);
  await audit(page, `lyric writer (${scheme})`);
  await page.getByRole("button", { name: "Use these lyrics" }).click();
  await page.getByLabel("Your lyrics").fill("[Verse]\nरात का सफ़र, तेरे बिना\nचलते रहे हम, चाँद के संग\n\n[Chorus]\nये रात का सफ़र");
  await page.getByLabel("Style").fill("Slow acoustic ballad, guitar and strings");
  await page.getByLabel("Title (optional)").fill("रात का सफ़र");
  await page.getByLabel("Voice").selectOption("female");
  await page.getByRole("button", { name: /Make two takes/ }).click();
  await page.locator("li", { hasText: "रात का सफ़र" }).getByText("Your takes are ready.").waitFor({ timeout: 30000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await shot(page, `04-create-lyrics-done${suffix}`);
  await audit(page, `create with takes (${scheme})`);

  await page.goto(`${BASE}/en/library`);
  await page.getByRole("link", { name: "रात का सफ़र" }).waitFor();
  await shot(page, `05-library${suffix}`);
  await audit(page, `library (${scheme})`);
  await page.getByLabel("Search your songs").fill("raat ka safar");
  await page.waitForTimeout(800);
  await shot(page, `06-library-search${suffix}`);

  await page.getByRole("link", { name: "रात का सफ़र" }).click();
  await page.getByRole("heading", { name: "Listen" }).waitFor();
  await page.getByRole("heading", { name: "History" }).waitFor();
  await shot(page, `07-song${suffix}`);
  await audit(page, `song page (${scheme})`);
  await page.getByRole("button", { name: /^MP3/ }).click();
  await page.getByRole("heading", { name: "Your file is ready" }).waitFor();
  await shot(page, `08-download-dialog${suffix}`, { fullPage: false });
  await audit(page, `download dialog (${scheme})`);
  await page.keyboard.press("Escape");

  // Free commercial licence and its public certificate (US-025).
  await page.getByRole("button", { name: "Licence for commercial use" }).click();
  await page.getByRole("heading", { name: "Licence this song for commercial use?" }).waitFor();
  await shot(page, `08a-licence-confirm${suffix}`, { fullPage: false });
  await audit(page, `licence dialog (${scheme})`);
  await page.getByRole("button", { name: "Licence it" }).click();
  await page.getByRole("link", { name: "View certificate" }).waitFor();
  await shot(page, `08b-licensed${suffix}`);
  await page.getByRole("link", { name: "View certificate" }).click();
  await page.getByRole("heading", { name: "Song licence and rights certificate" }).waitFor();
  await shot(page, `08c-certificate${suffix}`);
  const certificateId = decodeURIComponent(page.url().split("/certificates/")[1] ?? "");
  await audit(page, `certificate (${scheme})`);
  await page.goBack();

  // Share a public link (US-024), open the public page signed out, and report it (US-032).
  await page.getByRole("heading", { name: "Share", exact: true }).waitFor();
  const songUrl = page.url();
  await page.getByRole("radio", { name: /Public page/ }).check();
  await page.getByRole("button", { name: /^Make a link/ }).click();
  const shareUrl = await page.locator("#share-url").inputValue();
  await page.getByText(/Link ending/).first().waitFor();
  await shot(page, `08d-share${suffix}`);
  await audit(page, `share panel (${scheme})`);
  const visitor = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: scheme });
  const v = await visitor.newPage();
  await v.goto(shareUrl);
  await v.getByText("Shared song").waitFor();
  await shot(v, `08e-shared-page${suffix}`);
  await audit(v, `shared song page (${scheme})`);
  await v.getByRole("link", { name: "Report this song" }).click();
  await v.getByRole("heading", { name: "Report a song" }).waitFor();
  await v.getByLabel("Your name or organisation").fill(`Example Records ${scheme}`);
  await v.getByLabel("Email for our reply").fill("legal@records.example");
  await v.getByLabel("What is wrong, and what is the original work?").fill("The melody copies our 2019 release.");
  await v.getByRole("checkbox").check();
  await shot(v, `08f-report-form${suffix}`);
  await audit(v, `report form (${scheme})`);
  await v.getByRole("button", { name: "Send report" }).click();
  await v.getByText(/received\. We will acknowledge it by/).waitFor();
  await shot(v, `08g-report-sent${suffix}`);
  await visitor.close();
  if (scheme === "dark") reported = { email, songUrl, shareUrl };

  // Beat mode (US-015): genre, tempo, key and instruments; the live region gives the queue position (US-014).
  await page.goto(`${BASE}/en/create`);
  await page.getByRole("radio", { name: "Make a beat" }).check();
  await page.getByLabel("Genre").selectOption("bhangra");
  await page.getByLabel("Tempo (BPM)").fill("128");
  await page.getByRole("combobox", { name: "Key", exact: true }).selectOption("A");
  await page.getByRole("group", { name: "Instruments to feature" }).getByLabel("Dhol", { exact: true }).check();
  await page.getByRole("group", { name: "Instruments to feature" }).getByLabel("Tabla", { exact: true }).check();
  await page.getByRole("group", { name: "Instruments to leave out" }).getByLabel("Guitar", { exact: true }).check();
  await shot(page, `03c-create-beat${suffix}`);
  await audit(page, `create beat (${scheme})`);
  await page.getByRole("button", { name: /Make two takes/ }).click();
  await page.locator("li", { hasText: "Bhangra beat, 128 BPM" }).getByText("Your takes are ready.").waitFor({ timeout: 30000 });
  await page.getByRole("link", { name: "Bhangra beat, 128 BPM" }).first().click();
  await page.getByText(/Measured tempo 12\d BPM/).waitFor();
  await shot(page, `07a-beat-song${suffix}`);
  await audit(page, `beat song page (${scheme})`);

  // Sprint 5: plans by billing country and a test payment (US-033, US-034), a song owned on the
  // paid plan (US-036), the editor (US-038), the three-step cancel (US-037) and verify (US-021).
  await page.goto(`${BASE}/en/pricing`);
  await page.getByRole("heading", { name: "Choose a plan" }).waitFor();
  await page.getByLabel("Billing country").selectOption("IN");
  await page.getByText("including GST").first().waitFor();
  await shot(page, `19-pricing${suffix}`);
  await audit(page, `pricing (${scheme})`);
  await page.getByRole("button", { name: "Choose Creator" }).click();
  await page.getByRole("button", { name: "Pay", exact: true }).click();
  const testPay = page.getByRole("dialog");
  await testPay.getByRole("heading", { name: "Test payment" }).waitFor();
  await testPay.getByRole("radio", { name: "Pay by UPI (succeeds)" }).check();
  await shot(page, `19a-test-payment${suffix}`, { fullPage: false });
  await audit(page, `test payment dialog (${scheme})`);
  await testPay.getByRole("button", { name: "Pay", exact: true }).click();
  await page.getByText("You are on Creator. Thank you.").waitFor();
  await shot(page, `19b-paid${suffix}`);

  await page.goto(`${BASE}/en/create`);
  await page.getByRole("radio", { name: "Make a beat" }).check();
  await page.getByLabel("Genre").selectOption("bhangra");
  await page.getByLabel("Tempo (BPM)").fill("100");
  await page.getByRole("button", { name: /Make two takes/ }).click();
  await page.locator("li", { hasText: "Bhangra beat, 100 BPM" }).getByText("Your takes are ready.").waitFor({ timeout: 30000 });
  await page.getByRole("link", { name: "Bhangra beat, 100 BPM" }).first().click();
  await page.getByText("You own this song", { exact: true }).waitFor();
  await page.getByRole("heading", { name: "Edit this take" }).waitFor();
  await page.getByRole("radio", { name: "Fade in or out" }).check();
  await page.getByRole("button", { name: "Make new version" }).click();
  await page.locator("li", { hasText: "Version 2" }).getByText("Ready").waitFor({ timeout: 30000 });
  await page.getByRole("radio", { name: "Extend the end" }).check();
  await page.getByLabel("Seconds to add").fill("10");
  await page.getByRole("button", { name: "Make new version" }).click();
  await page.locator("li", { hasText: "Version 3" }).getByText(/^Ready/).waitFor({ timeout: 30000 });
  await page.locator("li", { hasText: "Version 3" }).getByText("Seams checked: no clicks or jumps").waitFor();
  await shot(page, `20-editor${suffix}`);
  await audit(page, `editor (${scheme})`);
  await page.locator("li", { hasText: "Version 2" }).getByRole("button", { name: "Use this version" }).click();
  await page.locator("li", { hasText: "Version 2" }).getByText("current").waitFor();

  await page.goto(`${BASE}/en/settings`);
  await page.getByRole("heading", { name: "Your plan" }).waitFor();
  await page.getByText(/^Renews on/).waitFor();
  await page.getByRole("button", { name: "Cancel plan" }).click();
  await page.getByRole("radio", { name: "I made what I needed" }).check();
  await page.getByRole("button", { name: "Next" }).click();
  await page.getByRole("heading", { name: "What happens when you cancel" }).waitFor();
  await shot(page, `21-cancel-step${suffix}`);
  await audit(page, `cancel plan (${scheme})`);
  await page.getByRole("button", { name: "Cancel my plan" }).click();
  await page.getByText(/^Your plan is cancelled/).waitFor();
  await shot(page, `21a-cancelled${suffix}`);

  const checker = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: scheme });
  const c = await checker.newPage();
  await c.goto(`${BASE}/en/verify`);
  await c.getByRole("heading", { name: "Was this song made with BeatMind?" }).waitFor();
  await c.getByLabel("Certificate ID").fill(certificateId);
  await c.getByRole("button", { name: "Check" }).first().click();
  await c.getByRole("heading", { name: "Made with BeatMind AI" }).waitFor();
  await c.getByText("Certificate valid").waitFor();
  await shot(c, `22-verify-certificate${suffix}`);
  await audit(c, `verify page (${scheme})`);
  await c.getByLabel(/Audio file/).setInputFiles({ name: "clip.wav", mimeType: "audio/wav", buffer: Buffer.alloc(4096, 7) });
  await c.getByRole("button", { name: "Check" }).nth(1).click();
  await c.getByRole("heading", { name: /Not found|Could not read the file/ }).waitFor({ timeout: 30000 });
  await shot(c, `22a-verify-file${suffix}`);
  await checker.close();

  // Your data (US-011): export link and the delete dialog (not confirmed here).
  await page.goto(`${BASE}/en/settings`);
  await page.getByRole("heading", { name: "Your data", level: 1 }).waitFor();
  await shot(page, `09a-your-data${suffix}`);
  await audit(page, `your data (${scheme})`);
  await page.getByRole("button", { name: "Delete my account" }).click();
  await page.getByRole("heading", { name: "Delete your account for good?" }).waitFor();
  await shot(page, `09b-delete-dialog${suffix}`, { fullPage: false });
  await audit(page, `delete dialog (${scheme})`);
  await page.getByRole("button", { name: "Cancel" }).click();

  await page.goto(`${BASE}/en/terms`);
  await audit(page, `terms (${scheme})`);
  await page.goto(`${BASE}/en/help`);
  await audit(page, `help (${scheme})`);
  await page.goto(`${BASE}/en/design`);
  await shot(page, `09-design-system${suffix}`);
  await audit(page, `design system (${scheme})`);
  await desktop.close();

  if (scheme === "dark") {
    const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, deviceScaleFactor: 2 });
    const p = await phone.newPage();
    // Reuse the session cookie by signing in again with the same email.
    await p.goto(`${BASE}/en/create`);
    await p.getByLabel("Email").fill(email);
    await p.getByRole("button", { name: "Email me a sign-in code" }).click();
    await p.getByRole("button", { name: "Sign in", exact: true }).click();
    await p.getByRole("heading", { name: "Make a song" }).waitFor();
    await shot(p, "10-phone-create");
    await audit(p, "create (phone)");
    await p.goto(`${BASE}/en/library`);
    await p.getByRole("link", { name: "रात का सफ़र" }).click();
    await p.getByRole("heading", { name: "Listen" }).waitFor();
    await shot(p, "11-phone-song");
    await audit(p, "song page (phone)");
    const overflow = await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    if (overflow) failures.push("song page (phone): horizontal scroll at 390 px");

    // The same screens in Hindi (US-010): switch language from the header and stay on the page.
    const songUrl = p.url();
    await p.getByRole("link", { name: "हिन्दी" }).click();
    await p.waitForURL(/\/hi\/songs\//);
    await p.getByRole("heading", { name: "सुनें" }).waitFor();
    await shot(p, "13-phone-song-hi");
    await audit(p, "song page (Hindi, phone)");
    await p.goto(`${BASE}/hi/create`);
    await p.getByRole("heading", { name: "गाना बनाएँ" }).waitFor();
    await shot(p, "14-phone-create-hi");
    await audit(p, "create (Hindi, phone)");
    await p.goto(`${BASE}/hi/library`);
    await p.getByRole("link", { name: "रात का सफ़र" }).waitFor();
    await shot(p, "15-phone-library-hi");
    await audit(p, "library (Hindi, phone)");
    for (const [path, heading, name] of [
      ["pricing", "प्लान चुनें", "19c-phone-pricing-hi"],
      ["verify", "क्या यह गाना BeatMind से बना है?", "22b-phone-verify-hi"],
    ]) {
      await p.goto(`${BASE}/hi/${path}`);
      await p.getByRole("heading", { name: heading }).waitFor();
      await p.waitForTimeout(500);
      await shot(p, name);
      await audit(p, `${path} (Hindi, phone)`);
      if (await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)) {
        failures.push(`${path} (Hindi, phone): horizontal scroll at 390 px`);
      }
    }
    await p.goto(`${BASE}/hi/terms`);
    await shot(p, "16-phone-terms-hi");
    await audit(p, "terms (Hindi, phone)");
    const hiOverflow = await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    if (hiOverflow) failures.push("terms (Hindi, phone): horizontal scroll at 390 px");
    if (!songUrl.includes("/en/")) failures.push("language switch did not start from an English page");
    // The public page in Hindi on a phone.
    await p.goto(reported.shareUrl.replace("/en/s/", "/hi/s/"));
    await p.getByText("शेयर किया गया गाना").waitFor();
    await shot(p, "18-phone-shared-hi");
    await audit(p, "shared song page (Hindi, phone)");
    if (await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)) {
      failures.push("shared song page (Hindi, phone): horizontal scroll at 390 px");
    }
    await phone.close();
  }
}

// Admin, signed in as the local admin account.
const admin = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: "dark" });
const a = await admin.newPage();
await a.goto(`${BASE}/en/admin`);
await a.getByLabel("Email").fill("admin@example.com");
await a.getByRole("button", { name: "Email me a sign-in code" }).click();
await a.getByRole("button", { name: "Sign in", exact: true }).click();
const second = /Set up a second factor|Enter your second-factor code|One more step/;
await a.getByRole("heading", { name: second }).waitFor();
if (await a.getByLabel("Date of birth").isVisible().catch(() => false)) {
  await a.getByLabel("Date of birth").fill("1990-01-01");
  await a.getByRole("checkbox").check();
  await a.getByRole("button", { name: "Continue" }).click();
}
// Second factor (US-031): enrol on first visit; later runs need ADMIN_TOTP_SECRET.
await a.getByRole("heading", { name: /Set up a second factor|Enter your second-factor code/ }).waitFor();
let secret = process.env.ADMIN_TOTP_SECRET ?? "";
if (await a.getByRole("button", { name: "Start setup" }).isVisible().catch(() => false)) {
  await a.getByRole("button", { name: "Start setup" }).click();
  secret = (await a.getByLabel("Setup key").innerText()).replace(/\s/g, "");
  console.log(`admin second factor enrolled; rerun with ADMIN_TOTP_SECRET=${secret}`);
  await shot(a, "12a-admin-mfa-setup");
  await audit(a, "admin second factor");
  await a.getByLabel("6-digit code").fill(totp(secret));
  await a.getByRole("button", { name: "Confirm and get recovery codes" }).click();
  await a.getByRole("heading", { name: "Save your recovery codes" }).waitFor();
  await a.getByRole("button", { name: /open the console/ }).click();
} else {
  if (!secret) throw new Error("the admin already has a second factor: set ADMIN_TOTP_SECRET");
  await a.getByLabel(/Code from your app/).fill(totp(secret));
  await a.getByRole("button", { name: "Verify" }).click();
}
await a.getByRole("heading", { name: "Console" }).waitFor();
await a.getByRole("heading", { name: "Moderation queue" }).waitFor();
await a.getByText(/Blocked request/).first().waitFor();
await shot(a, "12-admin");
await audit(a, "admin");

// Takedown queue (US-032): remove the reported song, the owner appeals, an admin restores it.
const card = a.locator("#takedowns li", { hasText: "Example Records dark" }).first();
await card.waitFor();
await shot(a, "12b-admin-takedowns");
await card.getByRole("button", { name: "Acknowledge" }).click();
await a.getByText(/acknowledged\./).waitFor();
await card.getByLabel("Note (the owner sees it)").fill("The report matches a registered work.");
await card.getByRole("button", { name: "Remove the song" }).click();
await a.getByText(/song removed\./).waitFor();
const owner = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: "dark" });
const o = await owner.newPage();
await o.goto(reported.songUrl);
await o.getByLabel("Email").fill(reported.email);
await o.getByRole("button", { name: "Email me a sign-in code" }).click();
await o.getByRole("button", { name: "Sign in", exact: true }).click();
await o.getByRole("heading", { name: "This song was removed after a report" }).waitFor();
await o.getByLabel("Why should this song come back?").fill("I wrote and recorded the melody myself last month.");
await shot(o, "17-removed-song");
await audit(o, "removed song with appeal");
await o.getByRole("button", { name: "Send appeal" }).click();
await o.getByText(/Appeal sent\. A person will answer by/).waitFor();
await a.getByLabel("Show", { exact: true }).first().selectOption("appealed");
const appealed = a.locator("#takedowns li", { hasText: "Example Records dark" }).first();
await appealed.getByRole("button", { name: "Restore the song" }).click();
await a.getByText(/song restored\./).waitFor();
await o.reload();
await o.getByText("Your appeal succeeded. The song is back as it was.").waitFor();
await shot(o, "17a-restored-song");
await owner.close();
await a.getByRole("button", { name: "Verify every chain" }).click();
await a.getByText(/song chains verify/).waitFor();
await browser.close();

if (failures.length) {
  console.error(`\nAccessibility or layout failures:\n- ${failures.join("\n- ")}`);
  process.exit(1);
}
console.log("\nAll screens pass: no serious or critical axe violations.");
