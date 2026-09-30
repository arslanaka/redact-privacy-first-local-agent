package dev.privacyagent.parsing;

import dev.privacyagent.document.DocumentFormat;
import org.springframework.stereotype.Component;

@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.TXT;
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
