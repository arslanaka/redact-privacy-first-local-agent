import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";

/**
 * Password hashes use scrypt from node:crypto (no extra dependency).
 * Format: `scrypt:<N>:<salt base64url>:<hash base64url>`, with r=8, p=1 and a 32-byte key.
 * `scripts/hash-password.mjs` produces the same format.
 */
const KEY_LENGTH = 32;
const DEFAULT_N = 16384;

export function hashPassword(password: string, n = DEFAULT_N): string {
  const salt = randomBytes(16);
  const hash = scryptSync(password, salt, KEY_LENGTH, { N: n, r: 8, p: 1 });
  return `scrypt:${n}:${salt.toString("base64url")}:${hash.toString("base64url")}`;
}

export function verifyPassword(password: string, stored: string): boolean {
  const parts = stored.split(":");
  if (parts.length !== 4 || parts[0] !== "scrypt") return false;
  const n = Number(parts[1]);
  if (!Number.isInteger(n) || n < 1024 || n > 1 << 20 || (n & (n - 1)) !== 0) return false;
  const salt = Buffer.from(parts[2], "base64url");
  const expected = Buffer.from(parts[3], "base64url");
  if (salt.length < 8 || expected.length !== KEY_LENGTH) return false;
  const actual = scryptSync(password, salt, KEY_LENGTH, { N: n, r: 8, p: 1, maxmem: 256 * n * 8 });
  return timingSafeEqual(actual, expected);
}

/** A valid hash of a random password, used for unknown usernames so timing does not reveal which names exist. */
export const DUMMY_HASH = hashPassword(randomBytes(16).toString("hex"));
