# Architecture decision records

Short ADRs. Each records the context, the decision and what it costs.

---

## ADR-001: Java (Spring Boot) for the orchestration layer

**Context.** The system is mostly workflow: upload, state transitions, persistence, review decisions,
audit, file generation. Only one step needs machine learning.

**Decision.** The backend and workflow orchestrator are Spring Boot 3.5 on Java 21, with JPA,
Flyway, Bean Validation and springdoc for OpenAPI.

**Consequences.** Strong typing for the domain model and state machine, mature transaction handling,
PDFBox for PDF work, and a stack that mirrors how many enterprise privacy tools are built. The cost is
a second language in the repo and an HTTP hop to the model.

---

## ADR-002: Python isolated in its own service for ML inference

**Context.** The Laya model and its tooling are Python (PyTorch, `laya` package). Embedding Python
in the JVM or porting the model is not practical.

**Decision.** A small FastAPI service owns inference behind one endpoint (`POST /v1/pii/detect`) and a
`PiiClassifier` abstraction (`LayaPiiClassifier`, `MockPiiClassifier`). It is stateless and never
persists or logs text.

**Consequences.** The model can be swapped, scaled or run natively (for Apple GPU acceleration)
without touching the backend. The backend treats it as untrusted: it validates offsets and types and
turns unavailability into a visible `MODEL_UNAVAILABLE` failure. The cost is one more container and a
~3 GB memory footprint for the model.

---

## ADR-003: Local model instead of external cloud inference

**Context.** The documents are exactly the kind of data that should not be sent to third parties to
find out whether they contain personal data.

**Decision.** Inference runs locally with open weights (Laya PII flavour, Apache-2.0). No document
content is sent to OpenAI, Anthropic, Google or any other external API. The only network access is the
one-time model download.

**Consequences.** Privacy by construction and zero per-call cost. The price is latency (roughly 0.2 to
0.6 s per candidate span on CPU, so tens of seconds per document in Docker on a laptop) and model
quality bounded by what runs on a laptop. Details are in the evaluation report.

---

## ADR-004: Human review before redaction

**Context.** Classifiers produce false positives ("Jordan" the country, "Georgia" the company) and
false negatives. Confidence is a model signal, not a guarantee.

**Decision.** Every detection starts `PENDING`. Redaction is only possible once every item is approved
or rejected. Bulk approval exists, but only for high-confidence items recommended for redaction, and
only after an explicit confirmation. Reviewers can add manual redactions and re-categorise items.
Changing a decision after export discards the exported file.

**Consequences.** Slower than fully automatic redaction, but the output reflects a person's decision,
and the audit trail can show who decided what (per document, masked). This is the core idea of the project.

---

## ADR-005: Hybrid deterministic + ML detection

**Context.** Regexes are precise and explainable for structured data (IBANs with checksums, Luhn-valid
cards) but blind to names and addresses. The model covers names, addresses and context but has no IBAN
label, occasionally mislabels numbers (a card number as a "tax number") and draws loose boundaries.

**Decision.** Run both. `DetectionMerger` clusters overlapping detections; `ConflictResolver` makes
agreeing detections `HYBRID` with noisy-OR confidence, keeps the rule's span and type on
disagreement and flags the conflict for the reviewer. Risk scoring is deterministic and documented.

**Consequences.** Better coverage than either alone (measured in `evaluation/REPORT.md`) and
explainable provenance per entity. The cost is merge logic that has to be tested carefully, and
latency that is dominated by the model.

---

## ADR-006: Synthetic data for evaluation and demo

**Context.** Evaluating a privacy tool on real personal data would defeat the purpose, and real data
must not end up in a public repository.

**Decision.** All samples and benchmark documents are synthetic, use reserved or documentation
ranges (example domains, drama phone numbers, test card numbers, example IBANs) and carry inline
ground-truth annotations with a written labelling policy.

**Consequences.** Safe to publish and reproducible. The numbers are a sanity check of the pipeline
on a small, clean, English dataset, not a claim about real-world performance. The Laya model was
itself trained on synthetic data (ai4privacy), which likely flatters it on synthetic text.

---

## ADR-007: Deterministic "agent" orchestration instead of an LLM planner

**Context.** The brief asks for agent-style tool use, not an autonomous agent.

**Decision.** `PrivacyAnalysisOrchestrator` exposes named tools (`extract_document`, `detect_regex_pii`,
`detect_model_pii`, `merge_detections`, `calculate_privacy_risk`, `prepare_review_queue`) and runs
them in a fixed plan, recording a timed tool trace.

**Consequences.** Reproducible and auditable, with no possibility of a planner "deciding" to skip a
detector. It is easy to plug in a local LLM later for summaries or extra context checks, but
correctness never depends on one.

---

## ADR-008: Re-render PDFs instead of overlaying boxes

**Context.** Drawing black boxes on an existing PDF usually leaves the text underneath in the content
stream, where copy-paste recovers it. True in-place PDF redaction (editing content streams, glyph
positions, fonts, XObjects, metadata) is complex and error-prone.

**Decision.** Produce a brand-new PDF from the redacted text, and verify it by extracting its text layer.

**Consequences.** No recoverable original content and a simple, testable implementation. The cost is
lost layout, images and tables. That is documented in the UI, the redaction report and the README.
