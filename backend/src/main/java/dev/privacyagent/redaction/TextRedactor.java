package dev.privacyagent.redaction;

import dev.privacyagent.config.PrivacyProperties.RedactionStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Replaces approved spans in the extracted text. This is the only place sensitive text is removed;
 * every output format is rendered from the result, so no renderer ever sees the original values.
 */
@Component
public class TextRedactor {

    public static final char BLOCK = '█';

    /** Redacted text plus the regions (in output offsets) that are redaction markers. */
    public record Result(String text, List<int[]> redactedRegions, int redactedCharacters) {}

    public Result redact(String text, List<RedactionSpan> spans, RedactionStyle style) {
        List<RedactionSpan> merged = mergeOverlapping(spans, text.length());
        var out = new StringBuilder(text.length());
        List<int[]> regions = new ArrayList<>();
        int pos = 0;
        int redactedChars = 0;
        for (RedactionSpan span : merged) {
            out.append(text, pos, span.start());
            int regionStart = out.length();
            String original = text.substring(span.start(), span.end());
            redactedChars += original.length();
            if (style == RedactionStyle.LABEL) {
                out.append("[REDACTED ").append(span.type().name().replace('_', ' ')).append(']');
            } else {
                // Keep line breaks so the layout survives; every other character becomes a block.
                for (char c : original.toCharArray()) {
                    out.append(c == '\n' ? '\n' : BLOCK);
                }
            }
            regions.add(new int[] {regionStart, out.length()});
            pos = span.end();
        }
        out.append(text, pos, text.length());
        return new Result(out.toString(), regions, redactedChars);
    }

    /** Unions overlapping or touching spans; the merged span keeps the type of its first part. */
    static List<RedactionSpan> mergeOverlapping(List<RedactionSpan> spans, int textLength) {
        List<RedactionSpan> sorted = spans.stream()
                .filter(s -> s.start() >= 0 && s.end() <= textLength && s.end() > s.start())
                .sorted(Comparator.comparingInt(RedactionSpan::start))
                .toList();
        List<RedactionSpan> merged = new ArrayList<>();
        for (RedactionSpan s : sorted) {
            if (!merged.isEmpty() && s.start() <= merged.getLast().end()) {
                RedactionSpan last = merged.removeLast();
                merged.add(new RedactionSpan(last.start(), Math.max(last.end(), s.end()), last.type()));
            } else {
                merged.add(s);
            }
        }
        return merged;
    }
}
