package dev.privacyagent.api;

import dev.privacyagent.api.ApiDtos.DocumentResponse;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.redaction.RedactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Redaction", description = "Generate, preview and download the sanitized document")
@RestController
@RequestMapping("/api/documents/{id}")
@RequiredArgsConstructor
public class RedactionController {

    private final RedactionService redaction;
    private final PrivacyProperties properties;

    @Operation(summary = "Generate the redacted document from approved entities (all entities must be reviewed)")
    @PostMapping("/redact")
    public DocumentResponse redact(@PathVariable UUID id) {
        return DocumentResponse.from(redaction.generate(id), properties.retention().documentHours());
    }

    @Operation(summary = "Original vs redacted text, using the current approved entities")
    @GetMapping("/preview")
    public RedactionService.Preview preview(@PathVariable UUID id) {
        return redaction.preview(id);
    }

    @Operation(summary = "Download the generated redacted file")
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var file = redaction.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename())
                        .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
