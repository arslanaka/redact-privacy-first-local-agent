package dev.privacyagent.document;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Supported input formats. Adding DOCX means a new constant here plus a DocumentParser implementation. */
public enum DocumentFormat {
    PDF(Set.of("pdf"), Set.of("application/pdf"), "application/pdf", "pdf"),
    TXT(Set.of("txt"), Set.of("text/plain"), "text/plain; charset=utf-8", "txt"),
    MARKDOWN(Set.of("md", "markdown"), Set.of("text/markdown", "text/x-markdown", "text/plain"),
            "text/markdown; charset=utf-8", "md");

    /** Browsers often send these for files they do not recognise; the content sniffing still applies. */
    public static final Set<String> GENERIC_MIME_TYPES = Set.of("", "application/octet-stream");

    private final Set<String> extensions;
    private final Set<String> mimeTypes;
    private final String outputContentType;
    private final String outputExtension;

    DocumentFormat(Set<String> extensions, Set<String> mimeTypes, String outputContentType, String outputExtension) {
        this.extensions = extensions;
        this.mimeTypes = mimeTypes;
        this.outputContentType = outputContentType;
        this.outputExtension = outputExtension;
    }

    public static Optional<DocumentFormat> fromExtension(String extension) {
        String ext = extension.toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (format.extensions.contains(ext)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    public boolean acceptsMimeType(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return mimeTypes.contains(normalized) || GENERIC_MIME_TYPES.contains(normalized);
    }

    public String outputContentType() {
        return outputContentType;
    }

    public String outputExtension() {
        return outputExtension;
    }
}
