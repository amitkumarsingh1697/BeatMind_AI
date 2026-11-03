package ai.beatmind.generation;

import java.io.InputStream;
import java.util.Optional;

/** Reads and removes take audio in object storage (ARCH-08). */
interface AudioStore {

    record Audio(InputStream body, String contentType, long length) {}

    Optional<Audio> open(String key);

    /** Stores an object (verify uploads, US-021). */
    void put(String key, byte[] data, String contentType);

    /** Removes an object. Removing one that is already gone is not an error. */
    void delete(String key);
}
