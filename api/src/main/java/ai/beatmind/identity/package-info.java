/**
 * Identity module (ARCH-10, ADR-008, US-009): sign-in with an email one-time code or Google,
 * server-side sessions in an HttpOnly cookie, and the 18+ profile step (REQ-F-53).
 *
 * <p>Other modules use only {@link ai.beatmind.identity.CurrentUser}.
 */
package ai.beatmind.identity;
