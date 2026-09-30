package dev.privacyagent.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.privacyagent.TestPdfs;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.parsing.DocumentParserRegistry;
import dev.privacyagent.parsing.MarkdownDocumentParser;
import dev.privacyagent.parsing.PdfDocumentParser;
import dev.privacyagent.parsing.TextDocumentParser;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class UploadValidatorTest {

    private final UploadValidator validator = new UploadValidator(props(DataSize.ofKilobytes(64)),
            new DocumentParserRegistry(List.of(new PdfDocumentParser(), new TextDocumentParser(),
                    new MarkdownDocumentParser())));

    static PrivacyProperties props(DataSize max) {
        return new PrivacyProperties(new PrivacyProperties.Storage("x"), new PrivacyProperties.Upload(max),
                new PrivacyProperties.Retention(24, Duration.ofMinutes(15)),
                new PrivacyProperties.AiService("http://localhost", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new PrivacyProperties.Redaction(PrivacyProperties.RedactionStyle.BLOCK),
                new PrivacyProperties.Cors(List.of()), true);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((PrivacyAgentException) t).code();
    }

    @Test
    void acceptsValidFiles() {
        var txt = validator.validate(new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes()));
        assertThat(txt.format()).isEqualTo(DocumentFormat.TXT);
        var md = validator.validate(new MockMultipartFile("file", "a.md", "application/octet-stream", "# hi".getBytes()));
        assertThat(md.format()).isEqualTo(DocumentFormat.MARKDOWN);
        assertThat(md.contentType()).startsWith("text/markdown");
        var pdf = validator.validate(new MockMultipartFile("file", "a.PDF", "application/pdf", TestPdfs.withLines("x")));
        assertThat(pdf.format()).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "a.docx", "application/msword",
                "x".getBytes()))).satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));
    }

    @Test
    void rejectsMimeTypeThatContradictsTheExtension() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "a.txt", "application/pdf",
                "x".getBytes()))).satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));
    }

    @Test
    void rejectsContentThatContradictsTheExtension() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "fake.pdf", "application/pdf",
                "not a pdf".getBytes()))).satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "bin.txt", "text/plain",
                new byte[] {1, 0, 2}))).satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));
    }

    @Test
    void rejectsTooLargeAndEmptyFiles() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "big.txt", "text/plain",
                new byte[65 * 1024]))).satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE));
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "e.txt", "text/plain", new byte[0])))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void sanitizesFilenames() {
        assertThat(UploadValidator.sanitizeFilename("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(UploadValidator.sanitizeFilename("C:\\Users\\me\\report.pdf")).isEqualTo("report.pdf");
        assertThat(UploadValidator.sanitizeFilename("..hidden.md")).isEqualTo("hidden.md");
        assertThat(UploadValidator.sanitizeFilename("<script>.txt")).isEqualTo("_script_.txt");
        assertThat(UploadValidator.sanitizeFilename(null)).isEqualTo("document");
        assertThat(UploadValidator.sanitizeFilename("a".repeat(300) + ".txt")).hasSize(120).endsWith(".txt");
    }
}
