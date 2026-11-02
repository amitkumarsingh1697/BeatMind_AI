// Walks the Sprint 2 screens in a real browser against a running local stack, runs axe-core on
// each (TC-REQ-NFR-08-01: no serious or critical violations) and saves screenshots.
//
//   node e2e/ui-check.mjs [base-url] [screenshot-dir]
//
// Needs Playwright with Chromium installed (npx playwright install chromium). Not part of CI yet;
// see docs/09-delivery/sprint-02-report.md.
import { createRequire } from "node:module";
import { mkdirSync, readFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const axeSource = readFileSync(require.resolve("axe-core/axe.min.js"), "utf8");

const BASE = process.argv[2] ?? "http://localhost:3000";
const OUT = process.argv[3] ?? "e2e/screenshots";
mkdirSync(OUT, { recursive: true });
const failures = [];

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
  await page.getByLabel("I accept the beta terms and privacy policy.").check();
  await page.getByRole("button", { name: "Continue" }).click();

  // Vibe mode first, wait for the takes.
  await page.getByRole("heading", { name: "Make a song" }).waitFor();
  await page.getByLabel("Vibe, genre, mood and instruments").fill("Monsoon evening lofi with flute and soft tabla");
  await shot(page, `03-create-vibe${suffix}`);
  await audit(page, `create (${scheme})`);
  await page.getByRole("button", { name: /Make two takes/ }).click();
  await page.getByText("Your takes are ready.").first().waitFor({ timeout: 30000 });

  // Then a Hindi lyrics song.
  await page.getByRole("radio", { name: "Use my lyrics" }).check();
  await page.getByLabel("Vocal language").selectOption("hi");
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
    await phone.close();
  }
}

// Admin, signed in as the local admin account.
const admin = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: "dark" });
const a = await admin.newPage();
await a.goto(`${BASE}/en/admin/jobs`);
await a.getByLabel("Email").fill("admin@example.com");
await a.getByRole("button", { name: "Email me a sign-in code" }).click();
await a.getByRole("button", { name: "Sign in", exact: true }).click();
await a.getByRole("heading", { name: /Operations|One more step/ }).waitFor();
if (await a.getByLabel("Date of birth").isVisible().catch(() => false)) {
  await a.getByLabel("Date of birth").fill("1990-01-01");
  await a.getByLabel("I accept the beta terms and privacy policy.").check();
  await a.getByRole("button", { name: "Continue" }).click();
}
await a.getByRole("heading", { name: "Operations" }).waitFor();
await a.getByRole("button", { name: "Verify every chain" }).click();
await a.getByText(/song chains verify/).waitFor();
await shot(a, "12-admin");
await audit(a, "admin");
await browser.close();

if (failures.length) {
  console.error(`\nAccessibility or layout failures:\n- ${failures.join("\n- ")}`);
  process.exit(1);
}
console.log("\nAll screens pass: no serious or critical axe violations.");
