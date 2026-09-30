package dev.privacyagent.api;

import dev.privacyagent.api.ApiDtos.BulkApproveRequest;
import dev.privacyagent.api.ApiDtos.BulkApproveResponse;
import dev.privacyagent.api.ApiDtos.EntityResponse;
import dev.privacyagent.api.ApiDtos.ManualRedactionRequest;
import dev.privacyagent.api.ApiDtos.UpdateEntityRequest;
import dev.privacyagent.review.DetectedEntity;
import dev.privacyagent.review.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Review", description = "Human-in-the-loop review of detected entities")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService review;

    @Operation(summary = "Detected entities with surrounding context, in document order")
    @GetMapping("/documents/{id}/entities")
    public List<EntityResponse> entities(@PathVariable UUID id) {
        return review.list(id).stream().map(EntityResponse::from).toList();
    }

    @Operation(summary = "Approve, reject, reset (PENDING) or re-categorise an entity")
    @PatchMapping("/entities/{entityId}")
    public EntityResponse update(@PathVariable UUID entityId, @RequestBody UpdateEntityRequest request) {
        return EntityResponse.from(review.update(entityId, request.reviewStatus(), request.type()));
    }

    @Operation(summary = "Add a manual redaction by offsets or by text (optionally every occurrence)")
    @PostMapping("/documents/{id}/entities/manual")
    public ResponseEntity<List<EntityResponse>> manual(@PathVariable UUID id,
                                                       @Valid @RequestBody ManualRedactionRequest request) {
        var created = review.addManual(id, new ReviewService.ManualRedaction(request.startOffset(),
                request.endOffset(), request.text(), request.type(), Boolean.TRUE.equals(request.allOccurrences())));
        return ResponseEntity.status(HttpStatus.CREATED).body(created.stream().map(EntityResponse::from).toList());
    }

    @Operation(summary = "Approve all pending HIGH-confidence items recommended for redaction (requires confirm=true)")
    @PostMapping("/documents/{id}/entities/approve-high-confidence")
    public BulkApproveResponse approveHighConfidence(@PathVariable UUID id, @RequestBody BulkApproveRequest request) {
        var approved = review.approveAllHighConfidence(id, request.confirm());
        return new BulkApproveResponse(approved.size(), approved.stream().map(DetectedEntity::getId).toList());
    }
}
