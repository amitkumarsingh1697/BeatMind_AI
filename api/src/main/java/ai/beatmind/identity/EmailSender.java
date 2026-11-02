package ai.beatmind.identity;

/**
 * Sends sign-in codes (INT-12). The provider is chosen with the release tasks in TODO.md; until
 * then {@link LogEmailSender} stands in.
 */
interface EmailSender {

    void sendSignInCode(String email, String code, String locale);
}
