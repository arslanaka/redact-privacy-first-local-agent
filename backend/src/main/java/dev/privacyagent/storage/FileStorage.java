package dev.privacyagent.storage;

import java.io.InputStream;

/** Stores files under opaque, generated keys. Callers never deal with filesystem paths. */
public interface FileStorage {

    /** Stores the bytes and returns a generated key such as "3f2c...-9a.pdf". */
    String store(byte[] content, String extension);

    byte[] read(String key);

    InputStream open(String key);

    /** Deletes the file if present. Returns true if something was deleted. */
    boolean delete(String key);
}
