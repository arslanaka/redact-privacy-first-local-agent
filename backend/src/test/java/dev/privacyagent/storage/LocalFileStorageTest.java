package dev.privacyagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.privacyagent.config.PrivacyProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class LocalFileStorageTest {

    @TempDir
    Path dir;

    private LocalFileStorage storage() {
        return new LocalFileStorage(new PrivacyProperties(new PrivacyProperties.Storage(dir.toString()),
                new PrivacyProperties.Upload(DataSize.ofMegabytes(1)), new PrivacyProperties.Retention(1, Duration.ofMinutes(1)),
                new PrivacyProperties.AiService("http://x", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new PrivacyProperties.Redaction(PrivacyProperties.RedactionStyle.BLOCK),
                new PrivacyProperties.Cors(List.of()), true));
    }

    @Test
    void storesUnderGeneratedKeysAndDeletes() {
        var s = storage();
        String key = s.store("hello".getBytes(), "txt");
        assertThat(key).matches("[0-9a-f\\-]{36}\\.txt");
        assertThat(new String(s.read(key))).isEqualTo("hello");
        assertThat(s.delete(key)).isTrue();
        assertThat(s.delete(key)).isFalse();
    }

    @Test
    void rejectsTraversalAndMalformedKeys() {
        var s = storage();
        assertThatThrownBy(() -> s.read("../secret.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.read("/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.store(new byte[1], "sh")).isInstanceOf(IllegalArgumentException.class);
    }
}
