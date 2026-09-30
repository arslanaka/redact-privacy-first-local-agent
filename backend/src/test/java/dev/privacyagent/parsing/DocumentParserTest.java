package dev.privacyagent.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.privacyagent.TestPdfs;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.document.DocumentFormat;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentParserTest {

    private final DocumentParserRegistry registry = new DocumentParserRegistry(
            List.of(new PdfDocumentParser(), new TextDocumentParser(), new MarkdownDocumentParser()));

    @Test
    void selectsParserByFormat() {
        assertThat(registry.forFormat(DocumentFormat.PDF)).isInstanceOf(PdfDocumentParser.class);
        assertThat(registry.forFormat(DocumentFormat.TXT)).isInstanceOf(TextDocumentParser.class);
        assertThat(registry.forFormat(DocumentFormat.MARKDOWN)).isInstanceOf(MarkdownDocumentParser.class);
    }

    @Test
    void rejectsDuplicateRegistrations() {
        assertThatThrownBy(() -> new DocumentParserRegistry(List.of(new TextDocumentParser(), new TextDocumentParser())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingParserIsUnsupportedType() {
        var partial = new DocumentParserRegistry(List.of(new TextDocumentParser()));
        assertThatThrownBy(() -> partial.forFormat(DocumentFormat.PDF))
                .isInstanceOf(PrivacyAgentException.class)
                .extracting(e -> ((PrivacyAgentException) e).code()).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
    }

    @Test
    void extractsPdfTextLayer() {
        byte[] pdf = TestPdfs.withLines("Customer Markus Weber", "markus@example.com");
        var parser = new PdfDocumentParser();
        assertThat(parser.looksValid(pdf)).isTrue();
        assertThat(parser.extractText(pdf)).contains("Customer Markus Weber").contains("markus@example.com");
    }

    @Test
    void pdfWithoutTextLayerIsAnExtractionFailure() {
        byte[] empty = TestPdfs.withLines();
        assertThatThrownBy(() -> new PdfDocumentParser().extractText(empty))
                .isInstanceOf(PrivacyAgentException.class)
                .extracting(e -> ((PrivacyAgentException) e).code()).isEqualTo(ErrorCode.TEXT_EXTRACTION_FAILED);
    }

    @Test
    void corruptPdfIsAnExtractionFailure() {
        byte[] junk = "%PDF-1.7 this is not really a pdf".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> new PdfDocumentParser().extractText(junk))
                .isInstanceOf(PrivacyAgentException.class);
    }

    @Test
    void pdfMagicBytesAreRequired() {
        assertThat(new PdfDocumentParser().looksValid("hello".getBytes())).isFalse();
    }

    @Test
    void textDecodingNormalisesLineEndingsAndBom() {
        byte[] bytes = "\uFEFFline one\r\nline two\rline three".getBytes(StandardCharsets.UTF_8);
        assertThat(new TextDocumentParser().extractText(bytes)).isEqualTo("line one\nline two\nline three");
    }

    @Test
    void nonUtf8TextFallsBackToWindows1252() {
        byte[] bytes = "Müller".getBytes(Charset.forName("windows-1252"));
        assertThat(new TextDocumentParser().extractText(bytes)).isEqualTo("Müller");
    }

    @Test
    void binaryContentIsNotText() {
        assertThat(new TextDocumentParser().looksValid(new byte[] {'a', 0, 'b'})).isFalse();
        assertThat(new MarkdownDocumentParser().looksValid("# Title".getBytes())).isTrue();
    }

    @Test
    void markdownKeepsMarkupSoOffsetsMatchTheFile() {
        String md = "# Record\n\n**Name:** Anna";
        assertThat(new MarkdownDocumentParser().extractText(md.getBytes(StandardCharsets.UTF_8))).isEqualTo(md);
    }
}
