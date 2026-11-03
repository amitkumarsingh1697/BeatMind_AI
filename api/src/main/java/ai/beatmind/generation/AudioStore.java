package ai.beatmind.generation;

import java.io.InputStream;
import java.util.Optional;

/** Reads and removes take audio in object storage (ARCH-08). */
interface AudioStore {

    record Audio(InputStream body, String contentType, long length) {}

    Optional<Audio> open(String key);

    /** Removes an object. Removing one that is already gone is not an error. */
    void delete(String key);
}
