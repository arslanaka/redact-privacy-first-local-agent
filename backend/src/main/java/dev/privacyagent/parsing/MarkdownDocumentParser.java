package dev.privacyagent.parsing;

import dev.privacyagent.document.DocumentFormat;
import org.springframework.stereotype.Component;

/**
 * Markdown is analysed as its raw source, markup included. That keeps offsets identical to the
 * file, so the redacted output is still valid Markdown with only the approved spans replaced.
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.MARKDOWN;
    }

    @Override
    public boolean looksValid(byte[] content) {
        return PlainTextDecoder.looksLikeText(content);
    }

    @Override
    public String extractText(byte[] content) {
        return PlainTextDecoder.decode(content);
    }
}
