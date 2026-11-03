/**
 * Identity module (ARCH-10, ADR-008, US-009): sign-in with an email one-time code or Google,
 * server-side sessions in an HttpOnly cookie, and the 18+ profile step (REQ-F-53).
 *
 * <p>Other modules use {@link ai.beatmind.identity.CurrentUser}, {@link ai.beatmind.identity.ClientFingerprint}
 * and, for data export and account closure (US-011), {@link ai.beatmind.identity.Accounts}.
 */
package ai.beatmind.identity;
