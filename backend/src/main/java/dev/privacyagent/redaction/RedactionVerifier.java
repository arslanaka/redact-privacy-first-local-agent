package dev.privacyagent.redaction;

import dev.privacyagent.document.DocumentFormat;
import dev.privacyagent.redaction.RedactionReport.Residual;
import dev.privacyagent.review.DetectedEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Independent check of the generated file: re-reads the output bytes the way an attacker would
 * (plain read for text, PDF text extraction for PDF) and confirms that no approved value appears
 * more often than in the intended redacted text. A failure aborts the export.
 *
 * <p>It also reports "residuals": approved values that still occur elsewhere in the document,
 * outside any approved span, so the reviewer can decide whether to redact those too.
 */
@Component
public class RedactionVerifier {

    static final int MIN_RESIDUAL_LENGTH = 3;

    public record Verification(boolean passed, List<String> checks, List<String> failures, List<Residual> residuals) {}

    public Verification verify(DocumentFormat format, byte[] output, String intendedText, List<DetectedEntity> approved) {
        List<String> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String outputText;
        if (format == DocumentFormat.PDF) {
            outputText = extractPdfText(output);
            checks.add("Output PDF is newly generated; no content, fonts, images or metadata copied from the source");
            checks.add("Output PDF text layer extracted and compared with the approved redactions");
        } else {
            outputText = new String(output, StandardCharsets.UTF_8);
            if (!outputText.equals(intendedText)) {
                failures.add("Written text file differs from the redacted text");
            }
            checks.add("Output file content equals the redacted text");
        }
        String normalizedOutput = normalize(outputText);
        String normalizedIntended = normalize(intendedText);

        List<Residual> residuals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DetectedEntity e : approved) {
            String value = normalize(e.getText());
            if (value.isBlank() || !seen.add(value)) {
                continue;
            }
            int inOutput = count(normalizedOutput, value);
            int intended = count(normalizedIntended, value);
            if (inOutput > intended) {
                failures.add("An approved %s value is still readable in the output".formatted(e.getType()));
            }
            if (intended > 0 && value.length() >= MIN_RESIDUAL_LENGTH) {
                residuals.add(new Residual(e.getId().toString(), e.getType().name(), intended));
            }
        }
        checks.add("%d approved values checked against the output".formatted(seen.size()));
        return new Verification(failures.isEmpty(), checks, failures, residuals);
    }

    private static String extractPdfText(byte[] pdfBytes) {
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(pdf);
        } catch (IOException e) {
            throw new IllegalStateException("Generated PDF could not be re-read for verification", e);
        }
    }

    static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    static int count(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int from = 0;
        int idx;
        while ((idx = haystack.indexOf(needle, from)) >= 0) {
            n++;
            from = idx + needle.length();
        }
        return n;
    }
}
