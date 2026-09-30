import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { safeNext } from "@/app/login/login-form";
import { AuthConfigError, parseUsers, readAuthConfig } from "@/lib/auth/config";
import { hashPassword, verifyPassword } from "@/lib/auth/password";
import { FailureLimiter } from "@/lib/auth/rate-limit";
import { clientIp, isSameOrigin } from "@/lib/auth/request";
import { createSessionToken, verifySessionToken } from "@/lib/auth/session";

const SECRET = "x".repeat(40);

describe("password hashing", () => {
  it("verifies the right password and rejects others", () => {
    const hash = hashPassword("correct horse battery", 1024);
    expect(verifyPassword("correct horse battery", hash)).toBe(true);
    expect(verifyPassword("correct horse batter", hash)).toBe(false);
  });

  it("rejects malformed hashes instead of throwing", () => {
    expect(verifyPassword("pw", "plain")).toBe(false);
    expect(verifyPassword("pw", "scrypt:1000:abc:def")).toBe(false);
  });

  it("accepts hashes produced by scripts/hash-password.mjs", () => {
    const script = resolve("scripts/hash-password.mjs"); // vitest runs from frontend/
    const line = execFileSync("node", [script, "alice"], { input: "a long test passphrase\n" }).toString().trim();
    const users = parseUsers(line);
    expect(verifyPassword("a long test passphrase", users.get("alice")!)).toBe(true);
  });
});

describe("session tokens", () => {
  it("round-trips a user until it expires", () => {
    const token = createSessionToken("alice", SECRET, 60, 1_000_000);
    expect(verifySessionToken(token, SECRET, 1_030_000)).toBe("alice");
    expect(verifySessionToken(token, SECRET, 1_061_000)).toBeNull();
  });

  it("rejects tampering and a different secret", () => {
    const token = createSessionToken("alice", SECRET, 60);
    const [body, sig] = token.split(".");
    const forged = Buffer.from(JSON.stringify({ sub: "bob", iat: 0, exp: 9e9 })).toString("base64url");
    expect(verifySessionToken(`${forged}.${sig}`, SECRET)).toBeNull();
    expect(verifySessionToken(`${body}.${sig}x`, SECRET)).toBeNull();
    expect(verifySessionToken(token, "y".repeat(40))).toBeNull();
    expect(verifySessionToken(undefined, SECRET)).toBeNull();
  });
});

describe("auth configuration", () => {
  const users = `alice=${hashPassword("pw-pw-pw-pw-pw", 1024)}`;

  it("parses users and fails closed on bad config", () => {
    expect(readAuthConfig({ APP_USERS: users, SESSION_SECRET: SECRET }).users.has("alice")).toBe(true);
    expect(() => readAuthConfig({ APP_USERS: users, SESSION_SECRET: "short" })).toThrow(AuthConfigError);
    expect(() => readAuthConfig({ SESSION_SECRET: SECRET })).toThrow(AuthConfigError);
    expect(() => parseUsers("alice:plaintext")).toThrow(AuthConfigError);
  });

  it("only skips sign-in when explicitly disabled", () => {
    expect(readAuthConfig({ AUTH_DISABLED: "true" }).disabled).toBe(true);
    expect(() => readAuthConfig({ AUTH_DISABLED: "1" })).toThrow(AuthConfigError);
  });
});

describe("request helpers", () => {
  it("accepts only same-origin state changes", () => {
    expect(isSameOrigin(new Headers({ origin: "https://lpa.example.com", host: "lpa.example.com" }))).toBe(true);
    expect(isSameOrigin(new Headers({ origin: "https://evil.example", host: "lpa.example.com" }))).toBe(false);
    expect(isSameOrigin(new Headers({ host: "lpa.example.com" }))).toBe(false);
    expect(
      isSameOrigin(new Headers({ origin: "https://lpa.example.com", host: "frontend:3000", "x-forwarded-host": "lpa.example.com" })),
    ).toBe(true);
  });

  it("ignores the spoofable left-most forwarded address", () => {
    expect(clientIp(new Headers({ "x-forwarded-for": "1.1.1.1, 9.9.9.9" }))).toBe("9.9.9.9");
    expect(clientIp(new Headers({ "x-real-ip": "5.5.5.5", "x-forwarded-for": "1.1.1.1" }))).toBe("5.5.5.5");
  });

  it("never redirects off-site after sign-in", () => {
    expect(safeNext("/documents/1/review")).toBe("/documents/1/review");
    expect(safeNext("//evil.example")).toBe("/");
    expect(safeNext("https://evil.example")).toBe("/");
    expect(safeNext(null)).toBe("/");
  });
});

describe("failed sign-in limiter", () => {
  it("blocks after the limit and forgets after the window", () => {
    const limiter = new FailureLimiter(3, 1000);
    for (let i = 0; i < 3; i++) limiter.recordFailure("k", 0);
    expect(limiter.isBlocked("k", 10)).toBe(true);
    expect(limiter.isBlocked("k", 1500)).toBe(false);
  });
});
