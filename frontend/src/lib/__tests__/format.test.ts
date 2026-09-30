import { describe, expect, it } from "vitest";
import { formatDuration } from "@/lib/format";

describe("formatDuration", () => {
  it("picks a readable unit", () => {
    expect(formatDuration(340)).toBe("340 ms");
    expect(formatDuration(2345)).toBe("2.3 s");
    expect(formatDuration(42_400)).toBe("42 s");
    expect(formatDuration(225_000)).toBe("3 min 45 s");
    expect(formatDuration(120_000)).toBe("2 min");
    expect(formatDuration(-1)).toBe("–");
  });
});
