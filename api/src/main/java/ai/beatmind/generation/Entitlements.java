package ai.beatmind.generation;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plan limits from the entitlement matrix (docs/02-product/entitlement-matrix.csv), with the
 * numbers the product has not fixed yet kept in settings ({@code beatmind.entitlements.*}).
 *
 * @param freeDailyCredits default daily allowance on the free plan; an admin can change it at run
 *     time (REQ-F-55) and per account
 * @param creatorMonthlyCredits monthly allowance on Creator (to be fixed with pricing)
 * @param proMonthlyCredits monthly allowance on Pro (to be fixed with pricing)
 * @param userRequestsPerTenMinutes velocity rule per account (REQ-SEC-09)
 * @param ipRequestsPerHour velocity rule per IP address across accounts (TM-12). Indian mobile
 *     networks share addresses, so keep this generous.
 * @param dayZone the time zone a "day" resets in
 * @param downloadLinkTtl how long a signed download link works (REQ-F-20, 15 minutes)
 * @param downloadSecret key for signing download links; blank means a random one per start
 */
@ConfigurationProperties("beatmind.entitlements")
record Entitlements(
        int freeDailyCredits,
        int creatorMonthlyCredits,
        int proMonthlyCredits,
        int userRequestsPerTenMinutes,
        int ipRequestsPerHour,
        ZoneId dayZone,
        Duration downloadLinkTtl,
        String downloadSecret) {

    /** One credit is one take; every request makes two takes (REQ-F-05). */
    static final int TAKES_PER_REQUEST = 2;
    static final int CREDITS_PER_REQUEST = TAKES_PER_REQUEST;

    Entitlements {
        freeDailyCredits = freeDailyCredits <= 0 ? 20 : freeDailyCredits;
        creatorMonthlyCredits = creatorMonthlyCredits <= 0 ? 600 : creatorMonthlyCredits;
        proMonthlyCredits = proMonthlyCredits <= 0 ? 2000 : proMonthlyCredits;
        userRequestsPerTenMinutes = userRequestsPerTenMinutes <= 0 ? 6 : userRequestsPerTenMinutes;
        ipRequestsPerHour = ipRequestsPerHour <= 0 ? 60 : ipRequestsPerHour;
        dayZone = dayZone == null ? ZoneId.of("Asia/Kolkata") : dayZone;
        downloadLinkTtl = downloadLinkTtl == null ? Duration.ofMinutes(15) : downloadLinkTtl;
        downloadSecret = downloadSecret == null ? "" : downloadSecret;
    }

    static boolean paid(String plan) {
        return "creator".equals(plan) || "pro".equals(plan);
    }

    /** Songs that may be in progress at once: 1, 2 or 4 (REQ-F-55). */
    static int concurrency(String plan) {
        return switch (plan) {
            case "pro" -> 4;
            case "creator" -> 2;
            default -> 1;
        };
    }

    /**
     * Download formats (REQ-F-20, REQ-F-23): free gets MP3 at 192 kbps for personal listening;
     * paid plans get MP3 at 320 kbps and WAV. Maps the format the user asks for to the stored file.
     */
    static List<String> formats(String plan) {
        return paid(plan) ? List.of("mp3", "wav") : List.of("mp3");
    }

    static String assetFor(String plan, String format) {
        if ("wav".equals(format)) {
            return paid(plan) ? "wav" : null;
        }
        if ("mp3".equals(format)) {
            return paid(plan) ? "mp3_320" : "mp3_192";
        }
        return null;
    }
}
