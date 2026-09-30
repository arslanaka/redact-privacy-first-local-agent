package dev.privacyagent.api;

import dev.privacyagent.api.ApiDtos.EvaluationEntity;
import dev.privacyagent.api.ApiDtos.EvaluationRequest;
import dev.privacyagent.api.ApiDtos.EvaluationResponse;
import dev.privacyagent.evaluation.EvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Evaluation", description = "Stateless detection for the benchmark suite (nothing is stored)")
@RestController
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluation;

    @Operation(summary = "Run REGEX, MODEL or HYBRID detection on raw text without persisting anything")
    @PostMapping("/api/evaluation/detect")
    public EvaluationResponse detect(@Valid @RequestBody EvaluationRequest request) {
        var result = evaluation.detect(request.text(), request.method());
        return new EvaluationResponse(result.method().name(),
                result.detections().stream().map(EvaluationEntity::from).toList(), result.durationMs(), result.model(),
                result.modelIsMock());
    }
}
