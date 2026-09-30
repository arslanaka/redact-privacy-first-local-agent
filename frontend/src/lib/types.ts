// Mirrors the backend DTOs (dev.privacyagent.api.ApiDtos). Keep in sync.

export type DocumentStatus =
  | "UPLOADED"
  | "EXTRACTING"
  | "ANALYSING"
  | "REVIEW_REQUIRED"
  | "APPROVED"
  | "REDACTING"
  | "COMPLETED"
  | "FAILED";

export type AnalysisStage =
  | "QUEUED"
  | "EXTRACTING_TEXT"
  | "REGEX_DETECTION"
  | "MODEL_DETECTION"
  | "MERGING"
  | "PREPARING_REVIEW"
  | "DONE";

export const PII_TYPES = [
  "PERSON",
  "EMAIL",
  "PHONE",
  "ADDRESS",
  "IBAN",
  "CREDIT_CARD",
  "ID_NUMBER",
  "DATE_OF_BIRTH",
  "ORGANIZATION",
  "LOCATION",
  "OTHER",
] as const;
export type PiiType = (typeof PII_TYPES)[number];

export type DetectionSource = "LAYA" | "REGEX" | "HYBRID" | "MANUAL";
export type ReviewStatus = "PENDING" | "APPROVED" | "REJECTED";
export type Recommendation = "REDACT" | "REVIEW" | "LIKELY_KEEP";
export type ConfidenceLevel = "HIGH" | "MEDIUM" | "LOW";
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

export interface ToolCall {
  tool: string;
  status: "OK" | "FAILED";
  durationMs: number;
  summary: string;
}

export interface PrivacySummary {
  totalDetections: number;
  highConfidence: number;
  needsReview: number;
  lowConfidence: number;
  categories: Record<string, number>;
  sources: Record<string, number>;
  riskLevel: RiskLevel;
  riskReasons: string[];
  recommendation: string;
  model: { name: string; isMock: boolean; candidatesScored: number; inferenceMs: number; rejectedOutputs: number };
  toolCalls: ToolCall[];
  generatedAt: string;
}

export interface Residual {
  entityId: string;
  type: string;
  remainingOccurrences: number;
}

export interface RedactionReport {
  generatedAt: string;
  style: "BLOCK" | "LABEL";
  outputFormat: string;
  approvedEntities: number;
  redactedRegions: number;
  redactedCharacters: number;
  outputBytes: number;
  verificationPassed: boolean;
  verificationChecks: string[];
  residuals: Residual[];
  limitations: string[];
}

export interface DocumentDto {
  id: string;
  originalFilename: string;
  contentType: string;
  format: "PDF" | "TXT" | "MARKDOWN";
  size: number;
  status: DocumentStatus;
  currentStage?: AnalysisStage;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  hasRedactedOutput: boolean;
  model?: { name: string; isMock: boolean };
  error?: { code: string; message: string };
  summary?: PrivacySummary;
  redaction?: RedactionReport;
  /** Length of the extracted text, once extraction has finished. */
  textCharacters?: number;
}

export interface EntityDto {
  id: string;
  documentId: string;
  text: string;
  type: PiiType;
  confidence: number;
  confidenceLevel: ConfidenceLevel;
  startOffset: number;
  endOffset: number;
  source: DetectionSource;
  reviewStatus: ReviewStatus;
  recommendation: Recommendation;
  recommendationReason: string;
  regexConfidence?: number;
  modelConfidence?: number;
  modelLabels?: string;
  detectorNote?: string;
  contextBefore?: string;
  contextAfter?: string;
  createdAt: string;
  reviewedAt?: string;
}

export interface ReviewProgress {
  total: number;
  pending: number;
  approved: number;
  rejected: number;
  reviewed: number;
}

export interface AnalysisView {
  documentId: string;
  status: DocumentStatus;
  stage?: AnalysisStage;
  summary?: PrivacySummary;
  review: ReviewProgress;
  error?: { code: string; message: string };
}

export interface PreviewSpan {
  entityId: string;
  type: PiiType;
  start: number;
  end: number;
}

export interface PreviewDto {
  originalText: string;
  redactedText: string;
  approvedSpans: PreviewSpan[];
  redactedRegions: [number, number][];
  outputGenerated: boolean;
  style: "BLOCK" | "LABEL";
  report?: RedactionReport;
}

export interface AuditEventDto {
  id: string;
  eventType: string;
  message: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
  /** Signed-in user who triggered the event; absent for system events. */
  actor?: string;
}

export interface SystemStatus {
  backend: string;
  aiService: "ready" | "loading" | "error" | "unavailable";
  model?: string;
  modelIsMock: boolean;
  detail?: string;
  maxUploadBytes: number;
  supportedExtensions: string[];
  retentionHours: number;
  redactionStyle: string;
  capacity?: Capacity;
  cost?: CostRates;
}

/** Measured model throughput and hard limits of this deployment. */
export interface Capacity {
  /** Candidate spans the model scores per second, measured at start-up; null for the mock or during warm-up. */
  spansPerSecond?: number | null;
  cpuThreads?: number | null;
  maxTextChars?: number | null;
  aiTimeoutSeconds: number;
  analysisWorkers: number;
  analysisQueueCapacity: number;
}

/** Hosting prices for estimates only (defaults: Railway usage pricing). */
export interface CostRates {
  host: string;
  vcpuUsdPerSecond: number;
  memoryGbUsdPerSecond: number;
  modelMemoryGb: number;
  otherServicesMemoryGb: number;
}
