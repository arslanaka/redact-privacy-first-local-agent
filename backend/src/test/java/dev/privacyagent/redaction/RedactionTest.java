package dev.privacyagent.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import dev.privacyagent.config.PrivacyProperties.RedactionStyle;
import dev.privacyagent.detection.PiiType;
import dev.privacyagent.document.DocumentFormat;
import dev.privacyagent.review.DetectedEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class RedactionTest {

    private static final String TEXT =
            "Customer Markus Weber can be contacted at markus@example.com.\nAddress: Lindenstrasse 42,\n10969 Berlin";

    private final TextRedactor redactor = new TextRedactor();
    private final PdfRedactedDocumentWriter pdfWriter = new PdfRedactedDocumentWriter();
    private final RedactionVerifier verifier = new RedactionVerifier();

    private static RedactionSpan span(String value, PiiType type) {
        int s = TEXT.indexOf(value);
        return new RedactionSpan(s, s + value.length(), type);
    }

    private static DetectedEntity approved(String value, PiiType type) {
        int s = TEXT.indexOf(value);
        return DetectedEntity.manual(UUID.randomUUID(), value, type, s, s + value.length(), Instant.now());
    }

    @Test
    void blockStyleReplacesEveryCharacterAndKeepsLineBreaks() {
        var r = redactor.redact(TEXT, List.of(span("Markus Weber", PiiType.PERSON),
                span("markus@example.com", PiiType.EMAIL), span("Lindenstrasse 42,\n10969 Berlin", PiiType.ADDRESS)),
                RedactionStyle.BLOCK);
        assertThat(r.text()).startsWith("Customer ████████████ can be contacted at ██████████████████.")
                .doesNotContain("Markus").doesNotContain("markus@").doesNotContain("Lindenstrasse");
        assertThat(r.text()).hasSameSizeAs(TEXT);
        assertThat(r.text().lines()).hasSize(3);
        assertThat(r.redactedRegions()).hasSize(3);
    }

    @Test
    void labelStyleHidesLength() {
        var r = redactor.redact(TEXT, List.of(span("Markus Weber", PiiType.PERSON)), RedactionStyle.LABEL);
        assertThat(r.text()).startsWith("Customer [REDACTED PERSON] can be contacted");
        int[] region = r.redactedRegions().getFirst();
        assertThat(r.text().substring(region[0], region[1])).isEqualTo("[REDACTED PERSON]");
    }

    @Test
    void overlappingSpansAreUnioned() {
        var merged = TextRedactor.mergeOverlapping(List.of(new RedactionSpan(0, 5, PiiType.PERSON),
                new RedactionSpan(3, 9, PiiType.PERSON), new RedactionSpan(9, 12, PiiType.OTHER),
                new RedactionSpan(20, 22, PiiType.EMAIL), new RedactionSpan(30, 999, PiiType.EMAIL)), 25);
        assertThat(merged).containsExactly(new RedactionSpan(0, 12, PiiType.PERSON), new RedactionSpan(20, 22, PiiType.EMAIL));
    }

    @Test
    void noSpansLeavesTextUnchanged() {
        assertThat(redactor.redact(TEXT, List.of(), RedactionStyle.BLOCK).text()).isEqualTo(TEXT);
    }

    @Test
    void generatedPdfContainsNoRecoverableSensitiveText() throws IOException {
        var spans = List.of(span("Markus Weber", PiiType.PERSON), span("markus@example.com", PiiType.EMAIL));
        for (RedactionStyle style : RedactionStyle.values()) {
            var r = redactor.redact(TEXT, spans, style);
            byte[] pdf = pdfWriter.write(r.text(), r.redactedRegions(), style == RedactionStyle.LABEL);
            String extracted;
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                extracted = new PDFTextStripper().getText(doc);
                assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("Redacted document");
            }
            assertThat(extracted).contains("Customer").contains("can be contacted")
                    .doesNotContain("Markus").doesNotContain("Weber").doesNotContain("markus@example.com");
            // the raw bytes must not carry the values either (e.g. in an uncompressed stream or metadata)
            String raw = new String(pdf, StandardCharsets.ISO_8859_1);
            assertThat(raw).doesNotContain("Markus").doesNotContain("markus@example.com");
        }
    }

    @Test
    void pdfWrapsLongTextAcrossPages() throws IOException {
        String longText = "word ".repeat(6000) + "\n" + "x".repeat(400);
        byte[] pdf = pdfWriter.write(longText, List.of(), false);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(3);
        }
    }

    @Test
    void pdfReplacesUnencodableCharacters() throws IOException {
        byte[] pdf = pdfWriter.write("Emoji 😀 and Chinese 中文 are replaced", List.of(), false);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Emoji").contains("are replaced");
        }
    }

    @Test
    void verifierPassesCorrectOutputAndReportsResiduals() {
        String text = "Anna met Anna Keller. Anna Keller left.";
        var first = DetectedEntity.manual(UUID.randomUUID(), "Anna Keller", PiiType.PERSON, 9, 20, Instant.now());
        var r = redactor.redact(text, List.of(new RedactionSpan(9, 20, PiiType.PERSON)), RedactionStyle.BLOCK);
        var v = verifier.verify(DocumentFormat.TXT, r.text().getBytes(StandardCharsets.UTF_8), r.text(), List.of(first));
        assertThat(v.passed()).isTrue();
        assertThat(v.residuals()).singleElement().satisfies(res -> assertThat(res.remainingOccurrences()).isEqualTo(1));
    }

    @Test
    void verifierFailsWhenTheWrittenFileLeaksAnApprovedValue() {
        var entity = approved("Markus Weber", PiiType.PERSON);
        var r = redactor.redact(TEXT, List.of(span("Markus Weber", PiiType.PERSON)), RedactionStyle.BLOCK);
        byte[] leaky = TEXT.getBytes(StandardCharsets.UTF_8); // simulate a writer bug: original text written
        var v = verifier.verify(DocumentFormat.TXT, leaky, r.text(), List.of(entity));
        assertThat(v.passed()).isFalse();
        assertThat(v.failures()).isNotEmpty();
    }

    @Test
    void verifierChecksPdfOutput() {
        var entity = approved("markus@example.com", PiiType.EMAIL);
        var r = redactor.redact(TEXT, List.of(span("markus@example.com", PiiType.EMAIL)), RedactionStyle.BLOCK);
        byte[] pdf = pdfWriter.write(r.text(), r.redactedRegions(), false);
        assertThat(verifier.verify(DocumentFormat.PDF, pdf, r.text(), List.of(entity)).passed()).isTrue();
        byte[] leakyPdf = pdfWriter.write(TEXT, List.of(), false);
        assertThat(verifier.verify(DocumentFormat.PDF, leakyPdf, r.text(), List.of(entity)).passed()).isFalse();
    }
}
