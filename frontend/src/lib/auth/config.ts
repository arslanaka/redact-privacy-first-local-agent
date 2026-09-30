/**
 * Server-side auth configuration, read from the environment at request time (not baked in at build).
 *
 * - APP_USERS: comma-separated `username=scrypt:...` pairs (see scripts/hash-password.mjs). Up to a handful of users, no roles.
 * - SESSION_SECRET: at least 32 characters; signs session cookies.
 * - SESSION_TTL_HOURS: session lifetime (default 8).
 * - BACKEND_URL: where the /api proxy forwards to (private network address in production).
 * - INTERNAL_API_TOKEN: shared secret the backend requires on every /api call.
 * - AUTH_DISABLED=true: skip sign-in entirely. Local docker-compose only; never set it on a hosted deployment.
 *   `next dev` without APP_USERS also skips sign-in; production builds always fail closed instead.
 */
export const USERNAME_PATTERN = /^[a-z0-9._-]{1,32}$/;

export interface AuthConfig {
  disabled: boolean;
  users: Map<string, string>;
  sessionSecret: string;
  sessionTtlSeconds: number;
  backendUrl: string;
  internalToken: string;
}

export class AuthConfigError extends Error {}

export function parseUsers(raw: string | undefined): Map<string, string> {
  const users = new Map<string, string>();
  for (const entry of (raw ?? "").split(",")) {
    const trimmed = entry.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf("=");
    const name = trimmed.slice(0, eq).trim().toLowerCase();
    const hash = trimmed.slice(eq + 1).trim();
    if (eq <= 0 || !USERNAME_PATTERN.test(name) || !hash.startsWith("scrypt:")) {
      throw new AuthConfigError(`APP_USERS entry "${trimmed.slice(0, 20)}…" is not "username=scrypt:…"`);
    }
    users.set(name, hash);
  }
  return users;
}

export function readAuthConfig(env: Record<string, string | undefined> = process.env): AuthConfig {
  const disabled = env.AUTH_DISABLED === "true" || (env.NODE_ENV === "development" && !env.APP_USERS);
  const backendUrl = env.BACKEND_URL || "http://localhost:8080";
  const internalToken = env.INTERNAL_API_TOKEN ?? "";
  if (disabled) {
    return { disabled, users: new Map(), sessionSecret: "", sessionTtlSeconds: 0, backendUrl, internalToken };
  }
  const users = parseUsers(env.APP_USERS);
  if (users.size === 0) throw new AuthConfigError("APP_USERS is empty: add at least one user or set AUTH_DISABLED=true locally");
  const sessionSecret = env.SESSION_SECRET ?? "";
  if (sessionSecret.length < 32) throw new AuthConfigError("SESSION_SECRET must be at least 32 characters");
  const hours = Number(env.SESSION_TTL_HOURS ?? "8");
  const sessionTtlSeconds = Math.round((Number.isFinite(hours) && hours > 0 ? hours : 8) * 3600);
  return { disabled, users, sessionSecret, sessionTtlSeconds, backendUrl, internalToken };
}
