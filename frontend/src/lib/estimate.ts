import type { Capacity, CostRates } from "./types";

/**
 * Time and cost estimates for the UI. The model scores every *candidate span* the rule-based finder proposes, so
 * time scales with candidates, not bytes. Measured on real documents: 0.11–0.14 candidates per character.
 */
export const SPANS_PER_CHAR = 0.12;
/** Roughly one dense page of text. */
export const CHARS_PER_PAGE = 3000;
const OVERHEAD_SECONDS = 2; // upload, extraction, rules, merge
const SECONDS_PER_MONTH = 30 * 24 * 3600;

export function estimateSpans(chars: number): number {
  return Math.max(1, Math.round(chars * SPANS_PER_CHAR));
}

/** Seconds for a document of `chars` characters, or null when throughput is unknown (mock model, warming up). */
export function estimateSeconds(chars: number, capacity?: Capacity): number | null {
  const rate = capacity?.spansPerSecond;
  if (!rate || rate <= 0) return null;
  return estimateSpans(chars) / rate + OVERHEAD_SECONDS;
}

/** Compute cost of `seconds` of model time: the CPU threads it keeps busy plus its share of memory. */
export function analysisCostUsd(seconds: number, capacity: Capacity | undefined, cost: CostRates | undefined): number | null {
  if (!cost || !Number.isFinite(seconds) || seconds < 0) return null;
  const threads = capacity?.cpuThreads ?? 1;
  return seconds * (threads * cost.vcpuUsdPerSecond + cost.modelMemoryGb * cost.memoryGbUsdPerSecond);
}

/** Largest text one analysis can finish: bounded by the AI time-out at the measured speed and by the text cap. */
export function maxCharsPerDocument(capacity?: Capacity): number | null {
  const rate = capacity?.spansPerSecond;
  if (!capacity || !rate) return capacity?.maxTextChars ?? null;
  const byTimeout = ((capacity.aiTimeoutSeconds - OVERHEAD_SECONDS) * rate * 0.9) / SPANS_PER_CHAR; // 10% safety margin
  return Math.floor(Math.min(byTimeout, capacity.maxTextChars ?? Infinity));
}

/** Always-on cost of keeping the stack running (mostly the model held in memory), idle CPU excluded. */
export function baselineMonthlyUsd(cost?: CostRates): number | null {
  if (!cost) return null;
  return (cost.modelMemoryGb + cost.otherServicesMemoryGb) * cost.memoryGbUsdPerSecond * SECONDS_PER_MONTH;
}

export function formatUsd(usd: number | null): string {
  if (usd === null) return "–";
  if (usd < 0.001) return "< $0.001";
  if (usd < 1) return `$${usd.toFixed(3)}`;
  return `$${usd.toFixed(usd < 100 ? 2 : 0)}`;
}

export function formatChars(chars: number): string {
  return chars >= 10_000 ? `${Math.round(chars / 1000)}k` : chars.toLocaleString("en-GB");
}
