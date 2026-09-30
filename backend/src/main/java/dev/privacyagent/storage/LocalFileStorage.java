package dev.privacyagent.storage;

import dev.privacyagent.config.PrivacyProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Filesystem storage. Keys are generated UUIDs plus a whitelisted extension; any key that does not
 * match that shape, or that resolves outside the storage root, is rejected (path traversal guard).
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Pattern KEY = Pattern.compile("[0-9a-f\\-]{36}\\.(pdf|txt|md)");
    private static final Pattern EXTENSION = Pattern.compile("pdf|txt|md");

    private final Path root;

    public LocalFileStorage(PrivacyProperties properties) {
        this.root = Path.of(properties.storage().directory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory", e);
        }
    }

    @Override
    public String store(byte[] content, String extension) {
        if (!EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("Unsupported storage extension");
        }
        String key = UUID.randomUUID() + "." + extension;
        try {
            Files.write(resolve(key), content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store file", e);
        }
        return key;
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read stored file", e);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open stored file", e);
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete stored file", e);
        }
    }

    Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
