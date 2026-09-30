import { describe, expect, it } from "vitest";
import { confidenceLevel, formatConfidence } from "@/lib/confidence";
import { nextPendingIndex } from "@/lib/review";
import { segmentText } from "@/lib/segments";
import { processingSteps } from "@/lib/stages";
import { validateFile } from "@/lib/upload";

describe("confidenceLevel", () => {
  it("uses the documented thresholds (>=0.90 high, 0.70-0.89 medium, <0.70 low)", () => {
    expect(confidenceLevel(0.9)).toBe("HIGH");
    expect(confidenceLevel(0.8999)).toBe("MEDIUM");
    expect(confidenceLevel(0.7)).toBe("MEDIUM");
    expect(confidenceLevel(0.6999)).toBe("LOW");
    expect(confidenceLevel(0)).toBe("LOW");
  });

  it("formats percentages without ever claiming 100%", () => {
    expect(formatConfidence(0.9734)).toBe("97%");
    expect(formatConfidence(0.994)).toBe("99.4%");
    expect(formatConfidence(1)).toBe("99.9%");
    expect(formatConfidence(0.05)).toBe("5%");
  });
});

describe("processingSteps", () => {
  it("marks earlier steps done and the current stage active", () => {
    const steps = processingSteps("ANALYSING", "MODEL_DETECTION");
    expect(steps.map((s) => s.state)).toEqual(["done", "done", "done", "active", "pending", "pending"]);
    expect(steps[3].label).toBe("Running local PII classifier");
  });

  it("starts at text extraction while queued", () => {
    expect(processingSteps("EXTRACTING", "QUEUED").map((s) => s.state)).toEqual([
      "done", "active", "pending", "pending", "pending", "pending",
    ]);
  });

  it("marks the failing step on failure and everything done on success", () => {
    const failed = processingSteps("FAILED", "MODEL_DETECTION");
    expect(failed[3].state).toBe("failed");
    expect(failed[4].state).toBe("pending");
    expect(processingSteps("REVIEW_REQUIRED", "DONE").every((s) => s.state === "done")).toBe(true);
  });
});

describe("segmentText", () => {
  it("splits text into plain and highlighted segments that reassemble the original", () => {
    const text = "Customer Markus Weber at markus@example.com.";
    const segs = segmentText(text, [
      { start: 25, end: 43, id: "email" },
      { start: 9, end: 21, id: "name" },
    ]);
    expect(segs.map((s) => s.text).join("")).toBe(text);
    expect(segs.filter((s) => s.span).map((s) => s.text)).toEqual(["Markus Weber", "markus@example.com"]);
  });

  it("trims overlapping spans and clips out-of-range spans", () => {
    const text = "abcdefghij";
    const segs = segmentText(text, [
      { start: 2, end: 6, id: "a" },
      { start: 4, end: 8, id: "b" },
      { start: 8, end: 50, id: "c" },
    ]);
    expect(segs.map((s) => [s.text, s.span?.id])).toEqual([
      ["ab", undefined],
      ["cdef", "a"],
      ["gh", "b"],
      ["ij", "c"],
    ]);
  });
});

describe("nextPendingIndex", () => {
  it("moves to the next pending entity, wrapping around", () => {
    const list = [{ reviewStatus: "PENDING" }, { reviewStatus: "APPROVED" }, { reviewStatus: "REJECTED" }] as const;
    expect(nextPendingIndex([...list], 1)).toBe(0);
    expect(nextPendingIndex([{ reviewStatus: "APPROVED" }, { reviewStatus: "PENDING" }], 0)).toBe(1);
  });

  it("stays put when nothing is pending", () => {
    expect(nextPendingIndex([{ reviewStatus: "APPROVED" }, { reviewStatus: "REJECTED" }], 1)).toBe(1);
  });
});

describe("validateFile", () => {
  it("accepts supported types within the size limit", () => {
    expect(validateFile({ name: "a.PDF", size: 100 })).toBeNull();
    expect(validateFile({ name: "notes.md", size: 100 })).toBeNull();
  });

  it("rejects unsupported, empty and oversized files", () => {
    expect(validateFile({ name: "a.docx", size: 100 })).toMatch(/Unsupported/);
    expect(validateFile({ name: "noext", size: 100 })).toMatch(/Unsupported/);
    expect(validateFile({ name: "a.txt", size: 0 })).toMatch(/empty/);
    expect(validateFile({ name: "a.txt", size: 10 * 1024 * 1024 + 1 })).toMatch(/10 MB/);
  });
});
