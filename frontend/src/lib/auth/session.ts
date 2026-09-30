import { createHmac, timingSafeEqual } from "node:crypto";

/**
 * Stateless session token: `<base64url(JSON payload)>.<base64url(HMAC-SHA256)>`.
 * Rotating SESSION_SECRET signs everyone out.
 */
export const SESSION_COOKIE = "lpa_session";

interface Payload {
  sub: string;
  iat: number;
  exp: number;
}

function sign(data: string, secret: string): string {
  return createHmac("sha256", secret).update(data).digest("base64url");
}

export function createSessionToken(user: string, secret: string, ttlSeconds: number, now = Date.now()): string {
  const iat = Math.floor(now / 1000);
  const payload: Payload = { sub: user, iat, exp: iat + ttlSeconds };
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  return `${body}.${sign(body, secret)}`;
}

/** Returns the username for a valid, unexpired token, otherwise null. */
export function verifySessionToken(token: string | undefined, secret: string, now = Date.now()): string | null {
  if (!token) return null;
  const dot = token.indexOf(".");
  if (dot <= 0 || dot !== token.lastIndexOf(".")) return null;
  const body = token.slice(0, dot);
  const given = Buffer.from(token.slice(dot + 1), "base64url");
  const expected = Buffer.from(sign(body, secret), "base64url");
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) return null;
  try {
    const payload = JSON.parse(Buffer.from(body, "base64url").toString("utf8")) as Payload;
    if (typeof payload.sub !== "string" || typeof payload.exp !== "number") return null;
    return payload.exp > Math.floor(now / 1000) ? payload.sub : null;
  } catch {
    return null;
  }
}
