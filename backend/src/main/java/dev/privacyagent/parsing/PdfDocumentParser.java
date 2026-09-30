package dev.privacyagent.parsing;

import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.document.DocumentFormat;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Extracts the text layer with PDFBox. Scanned PDFs (no text layer) are rejected: OCR is out of scope. */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final byte[] MAGIC = {'%', 'P', 'D', 'F', '-'};

    @Override
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    @Override
    public boolean looksValid(byte[] content) {
        // The header may be preceded by a little junk; the spec allows it within the first 1024 bytes.
        int limit = Math.min(content.length - MAGIC.length, 1024);
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < MAGIC.length; j++) {
                if (content[i + j] != MAGIC[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public String extractText(byte[] content) {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            String text = stripper.getText(pdf).replace("\r\n", "\n").replace('\r', '\n');
            if (text.isBlank()) {
                throw PrivacyAgentException.extractionFailed(
                        "The PDF has no extractable text layer (scanned PDFs require OCR, which is not supported)", null);
            }
            return text;
        } catch (InvalidPasswordException e) {
            throw PrivacyAgentException.extractionFailed("Encrypted PDFs are not supported", e);
        } catch (IOException e) {
            throw PrivacyAgentException.extractionFailed("The PDF could not be read", e);
        }
    }
}
