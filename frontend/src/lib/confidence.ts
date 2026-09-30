import type { ConfidenceLevel } from "./types";

/** Must match the backend's ConfidenceLevel thresholds. */
export const HIGH_THRESHOLD = 0.9;
export const MEDIUM_THRESHOLD = 0.7;

export function confidenceLevel(confidence: number): ConfidenceLevel {
  if (confidence >= HIGH_THRESHOLD) return "HIGH";
  return confidence >= MEDIUM_THRESHOLD ? "MEDIUM" : "LOW";
}

/** 0.9734 -> "97%", 0.999 -> "99.9%" (never shows 100% for a model signal). */
export function formatConfidence(confidence: number): string {
  const pct = confidence * 100;
  if (pct >= 99.95) return "99.9%";
  if (pct >= 99) return `${pct.toFixed(1)}%`;
  return `${Math.round(pct)}%`;
}

export const CONFIDENCE_LABEL: Record<ConfidenceLevel, string> = {
  HIGH: "High",
  MEDIUM: "Medium",
  LOW: "Low",
};

export const CONFIDENCE_EXPLANATION =
  "Classifier confidence is a model signal, not factual certainty. Human review remains necessary.";
