/** Helpers shared by the proxy and the auth route handlers. They only read headers. */

/** The host the browser used, as seen behind the platform's TLS proxy. */
export function publicHost(headers: Headers): string | null {
  return headers.get("x-forwarded-host") ?? headers.get("host");
}

/** True when the request's Origin matches the host it was sent to (CSRF check for state-changing calls). */
export function isSameOrigin(headers: Headers): boolean {
  const origin = headers.get("origin");
  const host = publicHost(headers);
  if (!origin || !host) return false;
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

/** Secure cookies whenever the browser is on HTTPS (Railway terminates TLS and sets x-forwarded-proto). */
export function isHttps(headers: Headers, url: string): boolean {
  return headers.get("x-forwarded-proto") === "https" || url.startsWith("https:");
}

/**
 * Client address for rate limiting. Prefers the platform's X-Real-IP, then the right-most X-Forwarded-For entry
 * (added by the nearest proxy; the left-most one is client-controlled and easy to spoof).
 */
export function clientIp(headers: Headers): string {
  const real = headers.get("x-real-ip")?.trim();
  if (real) return real;
  const forwarded = headers.get("x-forwarded-for")?.split(",").map((s) => s.trim()).filter(Boolean);
  return forwarded?.at(-1) ?? "unknown";
}
