package ai.beatmind.generation;

import java.io.InputStream;
import java.util.Optional;

/** Reads take audio from object storage (ARCH-08). */
interface AudioStore {

    record Audio(InputStream body, String contentType, long length) {}

    Optional<Audio> open(String key);
}
