import { describe, expect, it } from "vitest";
import {
  analysisCostUsd,
  baselineMonthlyUsd,
  estimateSeconds,
  estimateSpans,
  formatUsd,
  maxCharsPerDocument,
} from "@/lib/estimate";
import type { Capacity, CostRates } from "@/lib/types";

const capacity: Capacity = {
  spansPerSecond: 5,
  cpuThreads: 16,
  maxTextChars: 200_000,
  aiTimeoutSeconds: 600,
  analysisWorkers: 2,
  analysisQueueCapacity: 50,
};
const railway: CostRates = {
  host: "Railway",
  vcpuUsdPerSecond: 0.00000772,
  memoryGbUsdPerSecond: 0.00000386,
  modelMemoryGb: 3.2,
  otherServicesMemoryGb: 0.8,
};

describe("estimates", () => {
  it("scales time with candidate spans", () => {
    expect(estimateSpans(2108)).toBe(253); // the real onboarding PDF had 228
    expect(estimateSeconds(2108, capacity)).toBeCloseTo(52.6, 0);
    expect(estimateSeconds(2108, { ...capacity, spansPerSecond: null })).toBeNull();
  });

  it("prices model time at the configured rates", () => {
    // 45 s x (16 threads x vCPU + 3.2 GB x RAM) ~ $0.0061
    expect(analysisCostUsd(45, capacity, railway)).toBeCloseTo(0.0061, 4);
    expect(formatUsd(analysisCostUsd(45, capacity, railway))).toBe("$0.006");
  });

  it("finds the ceiling set by the AI time-out", () => {
    // (600 - 2) s x 5 spans/s x 0.9 / 0.12 spans per char ~ 22k characters
    expect(maxCharsPerDocument(capacity)).toBe(22425);
    expect(maxCharsPerDocument({ ...capacity, aiTimeoutSeconds: 100_000 })).toBe(200_000);
  });

  it("estimates the always-on baseline", () => {
    expect(baselineMonthlyUsd(railway)).toBeCloseTo(40, 0);
  });
});
