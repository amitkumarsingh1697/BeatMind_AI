// Which API calls the web app may forward to the core API (app/v1/[...path]/route.ts). Anything
// not listed gets a 404 from the web server and never reaches the API.

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

const ROUTES: [string, RegExp][] = [
  // Waitlist (US-007)
  ["GET", /^pricing$/],
  ["POST", /^waitlist$/],
  ["POST", /^waitlist\/visits$/],
  // Sign-in (US-009)
  ["GET", /^auth\/methods$/],
  ["POST", /^auth\/email\/(start|verify)$/],
  ["POST", /^auth\/logout$/],
  ["GET", /^auth\/google\/(start|callback)$/],
  ["GET", /^me$/],
  ["POST", /^me\/profile$/],
  // Generation (US-012)
  ["GET", /^generations$/],
  ["POST", /^generations$/],
  ["GET", new RegExp(`^generations/${UUID}$`)],
  ["GET", new RegExp(`^generations/${UUID}/events$`)],
  ["GET", new RegExp(`^generations/${UUID}/takes/[1-9][0-9]?/audio$`)],
  // Dead-letter view (US-013); the API checks the admin role
  ["GET", /^admin\/jobs$/],
  ["POST", new RegExp(`^admin/jobs/${UUID}/retry$`)],
];

export function isAllowed(method: string, path: string): boolean {
  return ROUTES.some(([m, pattern]) => m === method && pattern.test(path));
}
