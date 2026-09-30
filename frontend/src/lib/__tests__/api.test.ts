import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";

afterEach(() => vi.unstubAllGlobals());

describe("api error handling", () => {
  it("maps MODEL_UNAVAILABLE to the user-facing message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: "MODEL_UNAVAILABLE", message: "x", requestId: "r1" }), { status: 503 }),
      ),
    );
    const err = await api.analyse("id").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe("MODEL_UNAVAILABLE");
    expect(err.message).toBe("Local PII model is currently unavailable.");
    expect(err.requestId).toBe("r1");
  });

  it("keeps the server's message for validation errors", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: "UNSUPPORTED_DOCUMENT_TYPE", message: "Unsupported file type." }), {
          status: 415,
        }),
      ),
    );
    await expect(api.document("id")).rejects.toMatchObject({ message: "Unsupported file type.", status: 415 });
  });

  it("reports an unreachable backend clearly", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    await expect(api.systemStatus()).rejects.toMatchObject({ code: "NETWORK" });
  });
});
