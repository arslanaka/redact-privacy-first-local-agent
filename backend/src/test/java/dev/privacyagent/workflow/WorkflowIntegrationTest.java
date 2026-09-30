package dev.privacyagent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.privacyagent.TestPdfs;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.detection.AiServiceClient;
import dev.privacyagent.detection.AiServiceClient.ModelEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end workflow against a real PostgreSQL (Testcontainers) with the AI service mocked at its
 * HTTP-client boundary: upload, analyse, review, manual redaction, redact, download, audit, delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkflowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static Path storageDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storageDir = Files.createTempDirectory("lpa-it");
        registry.add("privacy.storage.directory", storageDir::toString);
    }

    private static final String TEXT = """
            Support ticket 4821
            Customer Markus Weber can be contacted at markus.weber@example.org or +49 30 1234 5678.
            Refund to IBAN DE89 3704 0044 0532 0130 00.
            Note: Jordan works with Amazon in Berlin.
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    AiServiceClient aiService;

    private static ModelEntity model(String value, String type, double confidence) {
        int s = TEXT.indexOf(value);
        return new ModelEntity(value, type, confidence, s, s + value.length(), List.of("label"));
    }

    private void mockModel(ModelEntity... entities) {
        doReturn(new AiServiceClient.DetectResponse("laya", false, List.of(entities), 40, 1234))
                .when(aiService).detect(anyString());
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String upload(String name, String contentType, byte[] content) throws Exception {
        var res = mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", name, contentType, content)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"));
        return body(res).get("id").asText();
    }

    private JsonNode analyseAndWait(String id) throws Exception {
        mvc.perform(post("/api/documents/{id}/analyse", id)).andExpect(status().isAccepted());
        for (int i = 0; i < 100; i++) {
            JsonNode doc = body(mvc.perform(get("/api/documents/{id}", id)));
            String s = doc.get("status").asText();
            if (!s.equals("EXTRACTING") && !s.equals("ANALYSING")) {
                return doc;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("analysis did not finish");
    }

    private JsonNode entities(String id) throws Exception {
        return body(mvc.perform(get("/api/documents/{id}/entities", id)).andExpect(status().isOk()));
    }

    private static long countFiles() throws IOException {
        try (var files = Files.list(storageDir)) {
            return files.count();
        }
    }

    private static JsonNode find(JsonNode entities, String text) {
        for (JsonNode e : entities) {
            if (e.get("text").asText().equals(text)) {
                return e;
            }
        }
        throw new AssertionError("entity not found: " + text);
    }

    private void review(String entityId, String decision) throws Exception {
        mvc.perform(patch("/api/entities/{id}", entityId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewStatus\":\"" + decision + "\"}")).andExpect(status().isOk());
    }

    @Test
    void fullWorkflowFromUploadToDeletion() throws Exception {
        mockModel(model("Markus Weber", "PERSON", 0.99), model("markus.weber@example.org", "EMAIL", 0.99),
                model("Jordan", "PERSON", 0.95), model("Berlin", "LOCATION", 0.93), model("4821", "OTHER", 0.62));
        long filesBefore = countFiles();
        String id = upload("ticket.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8));

        JsonNode doc = analyseAndWait(id);
        assertThat(doc.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(doc.get("summary").get("riskLevel").asText()).isEqualTo("HIGH"); // IBAN present
        assertThat(doc.get("summary").get("toolCalls")).hasSize(5);
        assertThat(doc.get("model").get("isMock").asBoolean()).isFalse();
        assertThat(doc.toString()).doesNotContain("storageKey").doesNotContain(storageDir.toString());

        JsonNode entities = entities(id);
        assertThat(entities).hasSize(7);
        assertThat(entities.findValuesAsText("reviewStatus")).containsOnly("PENDING"); // never auto-approved
        assertThat(find(entities, "markus.weber@example.org").get("source").asText()).isEqualTo("HYBRID");
        assertThat(find(entities, "DE89 3704 0044 0532 0130 00").get("source").asText()).isEqualTo("REGEX");
        assertThat(find(entities, "+49 30 1234 5678").get("source").asText()).isEqualTo("REGEX");
        assertThat(find(entities, "Jordan").get("source").asText()).isEqualTo("LAYA");
        assertThat(find(entities, "Berlin").get("recommendation").asText()).isEqualTo("REVIEW");
        assertThat(find(entities, "4821").get("confidenceLevel").asText()).isEqualTo("LOW");
        assertThat(find(entities, "Jordan").get("contextAfter").asText()).startsWith(" works with Amazon");

        // redaction is refused while items are pending
        mvc.perform(post("/api/documents/{id}/redact", id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));

        // bulk approval needs explicit confirmation, and only takes HIGH + REDACT items
        mvc.perform(post("/api/documents/{id}/entities/approve-high-confidence", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":false}"))
                .andExpect(status().isBadRequest());
        JsonNode bulk = body(mvc.perform(post("/api/documents/{id}/entities/approve-high-confidence", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andExpect(status().isOk()));
        assertThat(bulk.get("approved").asInt()).isEqualTo(5); // name, email, phone, IBAN, Jordan; not Berlin / 4821

        // the reviewer rejects the false positives and re-categorises nothing else
        review(find(entities, "Jordan").get("id").asText(), "REJECTED");
        review(find(entities, "Berlin").get("id").asText(), "REJECTED");
        review(find(entities, "4821").get("id").asText(), "REJECTED");
        mvc.perform(patch("/api/entities/{id}", find(entities, "Markus Weber").get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"PERSON\"}"))
                .andExpect(status().isOk());

        // manual redaction of something the detectors missed
        mvc.perform(post("/api/documents/{id}/entities/manual", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Amazon\",\"type\":\"ORGANIZATION\",\"allOccurrences\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].source").value("MANUAL"))
                .andExpect(jsonPath("$[0].reviewStatus").value("APPROVED"));
        mvc.perform(post("/api/documents/{id}/entities/manual", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startOffset\":5,\"endOffset\":99999,\"type\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());

        JsonNode analysis = body(mvc.perform(get("/api/documents/{id}/analysis", id)));
        assertThat(analysis.get("status").asText()).isEqualTo("APPROVED");
        assertThat(analysis.get("review").get("pending").asInt()).isZero();

        // generate, preview, download
        mvc.perform(post("/api/documents/{id}/redact", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.textCharacters").isNumber())
                .andExpect(jsonPath("$.redaction.verificationPassed").value(true));
        JsonNode preview = body(mvc.perform(get("/api/documents/{id}/preview", id)));
        assertThat(preview.get("outputGenerated").asBoolean()).isTrue();
        assertThat(preview.get("redactedText").asText()).contains("Customer ████████████ can be contacted");

        var download = mvc.perform(get("/api/documents/{id}/download", id)).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getHeader("Content-Disposition")).contains("redacted-ticket.txt");
        String out = download.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(out).doesNotContain("Markus", "markus.weber@example.org", "1234 5678", "DE89", "Amazon")
                .contains("Jordan works with", "in Berlin", "Support ticket 4821");

        // audit trail: complete and free of raw values
        JsonNode audit = body(mvc.perform(get("/api/documents/{id}/audit", id)));
        List<String> types = new ArrayList<>();
        audit.forEach(e -> types.add(e.get("eventType").asText()));
        assertThat(types).contains("DOCUMENT_UPLOADED", "ANALYSIS_STARTED", "TEXT_EXTRACTED", "REGEX_ANALYSIS_COMPLETED",
                "MODEL_ANALYSIS_COMPLETED", "DETECTIONS_MERGED", "PII_DETECTED", "BULK_APPROVED", "ENTITY_REJECTED",
                "MANUAL_ENTITY_ADDED", "REDACTION_STARTED", "REDACTION_COMPLETED", "EXPORT_CREATED");
        assertThat(audit.toString()).doesNotContain("Markus Weber").doesNotContain("markus.weber@example.org")
                .doesNotContain("DE89 3704");

        // changing a decision after export invalidates the file
        review(find(entities, "Berlin").get("id").asText(), "APPROVED");
        mvc.perform(get("/api/documents/{id}/download", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("OUTPUT_NOT_AVAILABLE"));
        assertThat(countFiles()).isEqualTo(filesBefore + 1); // only the original remains

        // deletion removes everything and leaves a single deletion record
        mvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/documents/{id}", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DOCUMENT_NOT_FOUND"));
        assertThat(countFiles()).isEqualTo(filesBefore);
    }

    @Test
    void modelUnavailableFailsTheAnalysisVisiblyAndCanBeRetried() throws Exception {
        when(aiService.detect(anyString())).thenThrow(PrivacyAgentException.modelUnavailable("down", null));
        String id = upload("note.md", "text/markdown", "# Note\nCall Anna on +49 30 1234 5678".getBytes());

        JsonNode doc = analyseAndWait(id);
        assertThat(doc.get("status").asText()).isEqualTo("FAILED");
        assertThat(doc.get("error").get("code").asText()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(doc.get("error").get("message").asText()).isEqualTo("Local PII model is currently unavailable.");
        assertThat(entities(id)).isEmpty(); // no silent regex-only "success"

        mockModel();
        assertThat(analyseAndWait(id).get("status").asText()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void pdfIsRedactedIntoANewDocumentWithoutRecoverableText() throws Exception {
        mockModel();
        byte[] pdf = TestPdfs.withLines("Employee record", "Name: Laura Fischer", "Email: laura.fischer@example.net");
        String id = upload("record.pdf", "application/pdf", pdf);
        JsonNode doc = analyseAndWait(id);
        assertThat(doc.get("status").asText()).isEqualTo("REVIEW_REQUIRED"); // regex found the email
        JsonNode entities = entities(id);
        review(find(entities, "laura.fischer@example.net").get("id").asText(), "APPROVED");
        mvc.perform(post("/api/documents/{id}/entities/manual", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Laura Fischer\",\"type\":\"PERSON\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/documents/{id}/redact", id)).andExpect(status().isOk());

        byte[] out = mvc.perform(get("/api/documents/{id}/download", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument redacted = Loader.loadPDF(out)) {
            String text = new PDFTextStripper().getText(redacted);
            assertThat(text).contains("Employee record").doesNotContain("Laura").doesNotContain("fischer@");
        }
    }

    @Test
    void uploadValidationReturnsStructuredErrors() throws Exception {
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "x.exe", "application/octet-stream",
                        new byte[] {1, 2}))).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_DOCUMENT_TYPE"))
                .andExpect(jsonPath("$.requestId").exists());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "big.txt", "text/plain",
                        new byte[10 * 1024 * 1024 + 1]))).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("DOCUMENT_TOO_LARGE"));
        mvc.perform(post("/api/documents/{id}/analyse", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluationEndpointRunsDetectorsWithoutPersisting() throws Exception {
        mockModel(model("Markus Weber", "PERSON", 0.99));
        var res = mvc.perform(post("/api/evaluation/detect").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("text", TEXT, "method", "HYBRID"))))
                .andExpect(status().isOk());
        JsonNode body = body(res);
        assertThat(body.get("entities").findValuesAsText("text")).contains("Markus Weber", "+49 30 1234 5678");
        var regexOnly = body(mvc.perform(post("/api/evaluation/detect").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("text", TEXT, "method", "REGEX")))));
        assertThat(regexOnly.get("entities").findValuesAsText("source")).containsOnly("REGEX");
    }

    @Test
    void systemStatusReportsCapacityAndCostForEstimates() throws Exception {
        when(aiService.status()).thenReturn(
                new AiServiceClient.ModelStatus("ready", "laya", false, null, 4.8, 16, 200_000));
        mvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiService").value("ready"))
                .andExpect(jsonPath("$.capacity.spansPerSecond").value(4.8))
                .andExpect(jsonPath("$.capacity.cpuThreads").value(16))
                .andExpect(jsonPath("$.capacity.maxTextChars").value(200_000))
                .andExpect(jsonPath("$.capacity.aiTimeoutSeconds").isNumber())
                .andExpect(jsonPath("$.cost.host").value("Railway"))
                .andExpect(jsonPath("$.cost.vcpuUsdPerSecond").isNumber());
    }
}
