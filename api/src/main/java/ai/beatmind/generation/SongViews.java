package ai.beatmind.generation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON shapes for the library and song page (API-003). */
final class SongViews {

    private SongViews() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code tempoBpm}: what the tempo check measured on a beat-mode take (US-015). */
    record TakeView(int index, String status, String audioUrl, String format, Double tempoBpm) {}

    /**
     * A song as the library and player show it. {@code aiGenerated} and {@code label} are always
     * set: the AI label is part of every song (REQ-F-33). {@code rights} says who owns it in plain
     * words (UX spec, licence panel). {@code beat} holds the beat-mode settings; {@code takedown}
     * is set when a report removed the song, so the owner can see why and appeal (US-032).
     */
    record SongView(UUID id, String title, String titleLatin, String languageTag, String mode, String prompt,
                    String lyrics, String vocalType, boolean instrumental, int durationSeconds, String status,
                    String rightsStatus, String rights, boolean aiGenerated, String label, List<TakeView> takes,
                    List<String> downloadFormats, String certificateId, OffsetDateTime createdAt,
                    Map<String, Object> beat, Takedowns.OwnerView takedown) {}

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
            case "disabled" -> "This song was removed after a report. Only you can see it.";
            default -> "Personal listening only. BeatMind owns this song.";
        };
    }

    static SongView of(SongStore.Song s, List<SongStore.Take> takes, String viewerPlan) {
        return of(s, takes, viewerPlan, null);
    }

    static SongView of(SongStore.Song s, List<SongStore.Take> takes, String viewerPlan, String certificateId) {
        return of(s, takes, viewerPlan, certificateId, null);
    }

    static Map<String, Object> beat(String beatJson) {
        return beatJson == null ? null : JSON.readValue(beatJson, new TypeReference<Map<String, Object>>() {});
    }

    static SongView of(SongStore.Song s, List<SongStore.Take> takes, String viewerPlan, String certificateId,
                       Takedowns.OwnerView takedown) {
        String status = s.status();
        List<TakeView> views = takes.stream()
                .map(t -> new TakeView(t.index(), t.status(),
                        "ready".equals(t.status()) && preview(t) != null ? audioUrl(s.id(), t.index()) : null,
                        previewFormat(t), t.tempoBpm()))
                .toList();
        if ("succeeded".equals(status) && views.stream().noneMatch(t -> t.audioUrl() != null)) {
            status = "failed";
        }
        return new SongView(s.id(), s.title(), s.titleLatin(), s.languageTag(), s.mode(), s.prompt(), s.lyrics(),
                s.vocalType(), s.instrumental(), s.durationSeconds(), status, s.rightsStatus(),
                rights(s.rightsStatus()), true, AI_LABEL, views,
                "disabled".equals(s.rightsStatus()) ? List.of() : Entitlements.formats(viewerPlan), certificateId,
                s.createdAt(), beat(s.beatJson()), takedown);
    }
}
