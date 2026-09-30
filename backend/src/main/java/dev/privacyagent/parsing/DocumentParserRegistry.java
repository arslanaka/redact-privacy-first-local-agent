package dev.privacyagent.parsing;

import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.document.DocumentFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Selects the parser for a format. New formats register themselves by adding a DocumentParser bean. */
@Component
public class DocumentParserRegistry {

    private final Map<DocumentFormat, DocumentParser> parsers = new EnumMap<>(DocumentFormat.class);

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            if (this.parsers.putIfAbsent(parser.format(), parser) != null) {
                throw new IllegalStateException("Two parsers registered for " + parser.format());
            }
        }
    }

    public DocumentParser forFormat(DocumentFormat format) {
        DocumentParser parser = parsers.get(format);
        if (parser == null) {
            throw PrivacyAgentException.unsupportedType("No parser available for " + format);
        }
        return parser;
    }
}
