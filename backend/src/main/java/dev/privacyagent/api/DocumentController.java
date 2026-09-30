package dev.privacyagent.api;

import dev.privacyagent.analysis.AnalysisService;
import dev.privacyagent.api.ApiDtos.AuditEventResponse;
import dev.privacyagent.api.ApiDtos.DocumentResponse;
import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.audit.AuditService;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.document.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Documents", description = "Upload, lifecycle, analysis and audit")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documents;
    private final AnalysisService analysis;
    private final AuditService audit;
    private final PrivacyProperties properties;

    @Operation(summary = "Upload a document (PDF, TXT or Markdown, max 10 MB)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(documents.upload(file)));
    }

    @Operation(summary = "Get a document's metadata, status, analysis summary and redaction report")
    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return toResponse(documents.get(id));
    }

    @Operation(summary = "Start the privacy analysis (asynchronous; poll GET /documents/{id} or /analysis)")
    @PostMapping("/{id}/analyse")
    public ResponseEntity<DocumentResponse> analyse(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(toResponse(analysis.start(id)));
    }

    @Operation(summary = "Privacy analysis summary plus live review progress")
    @GetMapping("/{id}/analysis")
    public AnalysisService.AnalysisView analysis(@PathVariable UUID id) {
        return analysis.view(id);
    }

    @Operation(summary = "Audit trail of the document, oldest first")
    @GetMapping("/{id}/audit")
    public List<AuditEventResponse> audit(@PathVariable UUID id) {
        documents.get(id);
        return audit.forDocument(id).stream().map(AuditEventResponse::from).toList();
    }

    @Operation(summary = "Delete the original file, output file, extracted text, detections and audit trail")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        return documents.delete(id, AuditEventType.DOCUMENT_DELETED);
    }

    private DocumentResponse toResponse(dev.privacyagent.document.Document d) {
        return DocumentResponse.from(d, properties.retention().documentHours());
    }
}
