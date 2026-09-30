import { NextResponse, type NextRequest } from "next/server";
import { AuthConfigError, readAuthConfig } from "@/lib/auth/config";
import { DUMMY_HASH, verifyPassword } from "@/lib/auth/password";
import { FailureLimiter } from "@/lib/auth/rate-limit";
import { clientIp, isHttps, isSameOrigin } from "@/lib/auth/request";
import { SESSION_COOKIE, createSessionToken } from "@/lib/auth/session";

// Per address and username, plus a looser per-address cap across usernames. There is deliberately no
// per-username-only lock, so nobody can lock a tester out just by knowing their name.
const perUser = new FailureLimiter(5);
const perIp = new FailureLimiter(20);

function fail(status: number, error: string, message: string) {
  return NextResponse.json({ error, message }, { status });
}

export async function POST(request: NextRequest) {
  let config;
  try {
    config = readAuthConfig();
  } catch (e) {
    console.error(`[auth] ${e instanceof AuthConfigError ? e.message : "misconfigured"}`);
    return fail(503, "AUTH_MISCONFIGURED", "Sign-in is not configured on this server.");
  }
  if (config.disabled) return fail(400, "AUTH_DISABLED", "Sign-in is disabled on this server.");
  if (!isSameOrigin(request.headers)) return fail(403, "FORBIDDEN", "Cross-site request blocked.");

  let body: { username?: unknown; password?: unknown };
  try {
    body = await request.json();
  } catch {
    return fail(400, "BAD_REQUEST", "Invalid request.");
  }
  const username = typeof body.username === "string" ? body.username.trim().toLowerCase().slice(0, 64) : "";
  const password = typeof body.password === "string" ? body.password.slice(0, 256) : "";

  const ip = clientIp(request.headers);
  const pairKey = `${ip}|${username}`;
  if (perUser.isBlocked(pairKey) || perIp.isBlocked(ip)) {
    return fail(429, "TOO_MANY_ATTEMPTS", "Too many failed sign-ins. Try again in 15 minutes.");
  }

  const stored = config.users.get(username);
  // Always run scrypt, so unknown usernames take as long as wrong passwords.
  const ok = verifyPassword(password, stored ?? DUMMY_HASH) && stored !== undefined;
  if (!ok) {
    perUser.recordFailure(pairKey);
    perIp.recordFailure(ip);
    console.warn(`[auth] failed sign-in user=${JSON.stringify(username.slice(0, 32))} ip=${ip}`);
    return fail(401, "INVALID_CREDENTIALS", "Wrong username or password.");
  }

  perUser.reset(pairKey);
  console.info(`[auth] signed in user=${username}`);
  const response = NextResponse.json({ user: username });
  response.cookies.set(SESSION_COOKIE, createSessionToken(username, config.sessionSecret, config.sessionTtlSeconds), {
    httpOnly: true,
    secure: isHttps(request.headers, request.url),
    sameSite: "lax",
    path: "/",
    maxAge: config.sessionTtlSeconds,
  });
  return response;
}
