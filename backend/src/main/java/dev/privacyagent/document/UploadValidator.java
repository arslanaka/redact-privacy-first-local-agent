package dev.privacyagent.document;

import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.parsing.DocumentParserRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates an upload in layers: size, extension whitelist, declared MIME type against the
 * extension, then the actual bytes (PDF magic header, or text without NUL bytes).
 */
@Component
public class UploadValidator {

    public record ValidatedUpload(byte[] content, String filename, String contentType, DocumentFormat format) {}

    private final long maxBytes;
    private final DocumentParserRegistry parsers;

    public UploadValidator(PrivacyProperties properties, DocumentParserRegistry parsers) {
        this.maxBytes = properties.upload().maxFileSize().toBytes();
        this.parsers = parsers;
    }

    public ValidatedUpload validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw PrivacyAgentException.validation("The uploaded file is empty");
        }
        if (file.getSize() > maxBytes) {
            throw PrivacyAgentException.tooLarge(maxBytes);
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        DocumentFormat format = DocumentFormat.fromExtension(extensionOf(filename))
                .orElseThrow(() -> PrivacyAgentException.unsupportedType(
                        "Unsupported file type. Supported: .pdf, .txt, .md"));
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        if (!format.acceptsMimeType(contentType)) {
            throw PrivacyAgentException.unsupportedType(
                    "Content type '%s' does not match a %s file".formatted(contentType, format));
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read upload", e);
        }
        if (content.length > maxBytes) {
            throw PrivacyAgentException.tooLarge(maxBytes);
        }
        if (!parsers.forFormat(format).looksValid(content)) {
            throw PrivacyAgentException.unsupportedType("File content does not match its %s extension".formatted(format));
        }
        String storedType = contentType.isBlank() || DocumentFormat.GENERIC_MIME_TYPES.contains(contentType)
                ? format.outputContentType() : contentType;
        return new ValidatedUpload(content, filename, storedType, format);
    }

    /** Strips directories and anything outside a conservative character set; never returns an empty name. */
    public static String sanitizeFilename(String original) {
        if (original == null) {
            return "document";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("[^A-Za-z0-9._ \\-]", "_")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("^[.\\s]+", "")
                .trim();
        if (name.length() > 120) {
            String ext = extensionOf(name);
            name = name.substring(0, 120 - ext.length() - 1) + "." + ext;
        }
        return name.isBlank() ? "document" : name;
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }
}
