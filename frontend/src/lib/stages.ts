import type { AnalysisStage, DocumentStatus } from "./types";

export type StepState = "done" | "active" | "pending" | "failed";

export interface ProcessingStep {
  key: string;
  label: string;
  state: StepState;
}

const STEPS: { key: string; label: string; stage?: AnalysisStage }[] = [
  { key: "upload", label: "Uploading" },
  { key: "extract", label: "Extracting text", stage: "EXTRACTING_TEXT" },
  { key: "regex", label: "Running deterministic checks", stage: "REGEX_DETECTION" },
  { key: "model", label: "Running local PII classifier", stage: "MODEL_DETECTION" },
  { key: "merge", label: "Merging detections", stage: "MERGING" },
  { key: "review", label: "Preparing review", stage: "PREPARING_REVIEW" },
];

/** Derives the processing checklist from the document status and the backend's current stage. */
export function processingSteps(status: DocumentStatus, stage?: AnalysisStage): ProcessingStep[] {
  const finished = !["UPLOADED", "EXTRACTING", "ANALYSING", "FAILED"].includes(status);
  const activeIndex = finished
    ? STEPS.length
    : stage === undefined || stage === "QUEUED"
      ? 1
      : Math.max(1, STEPS.findIndex((s) => s.stage === stage));
  return STEPS.map((step, i) => {
    let state: StepState;
    if (i < activeIndex) state = "done";
    else if (i === activeIndex) state = status === "FAILED" ? "failed" : "active";
    else state = "pending";
    return { key: step.key, label: step.label, state };
  });
}

export function isProcessing(status: DocumentStatus): boolean {
  return status === "EXTRACTING" || status === "ANALYSING";
}
