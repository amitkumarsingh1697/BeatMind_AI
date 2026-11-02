package ai.beatmind.generation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** JSON shapes for the library and song page (API-003). */
final class SongViews {

    private SongViews() {}

    record TakeView(int index, String status, String audioUrl, String format) {}

    /**
     * A song as the library and player show it. {@code aiGenerated} and {@code label} are always
     * set: the AI label is part of every song (REQ-F-33). {@code rights} says who owns it in plain
     * words (UX spec, licence panel).
     */
    record SongView(UUID id, String title, String titleLatin, String languageTag, String mode, String prompt,
                    String lyrics, String vocalType, boolean instrumental, int durationSeconds, String status,
                    String rightsStatus, String rights, boolean aiGenerated, String label, List<TakeView> takes,
                    List<String> downloadFormats, OffsetDateTime createdAt) {}

    static final String AI_LABEL = "AI-generated";

    static String audioUrl(UUID songId, int index) {
        return "/v1/songs/" + songId + "/takes/" + index + "/audio";
    }

    /** The asset streamed in the app: the light MP3 when there is one. */
    static SongStore.Asset preview(SongStore.Take take) {
        for (String name : List.of("mp3_192", "preview", "mp3_320", "wav")) {
            SongStore.Asset a = take.assets().get(name);
            if (a != null && !a.key().isEmpty()) {
                return a;
            }
        }
        return null;
    }

    static String previewFormat(SongStore.Take take) {
        SongStore.Asset a = preview(take);
        if (a == null) {
            return null;
        }
        return a.contentType().contains("mpeg") ? "mp3" : "wav";
    }

    static String rights(String rightsStatus) {
        return switch (rightsStatus) {
            case "user_owned" -> "You own this song.";
            case "free_licensed" -> "BeatMind owns this song; you may use it commercially with attribution.";
            case "disabled" -> "This song is unavailable.";
            default -> "Personal listening only. BeatMind owns this song.";
        };
    }

    static SongView of(SongStore.Song s, List<SongStore.Take> takes, String viewerPlan) {
        String status = s.status();
        List<TakeView> views = takes.stream()
                .map(t -> new TakeView(t.index(), t.status(),
                        "ready".equals(t.status()) && preview(t) != null ? audioUrl(s.id(), t.index()) : null,
                        previewFormat(t)))
                .toList();
        if ("succeeded".equals(status) && views.stream().noneMatch(t -> t.audioUrl() != null)) {
            status = "failed";
        }
        return new SongView(s.id(), s.title(), s.titleLatin(), s.languageTag(), s.mode(), s.prompt(), s.lyrics(),
                s.vocalType(), s.instrumental(), s.durationSeconds(), status, s.rightsStatus(),
                rights(s.rightsStatus()), true, AI_LABEL, views, Entitlements.formats(viewerPlan), s.createdAt());
    }
}
