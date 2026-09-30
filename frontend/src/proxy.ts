import { NextResponse, type NextRequest } from "next/server";
import { AuthConfigError, readAuthConfig } from "@/lib/auth/config";
import { isSameOrigin } from "@/lib/auth/request";
import { SESSION_COOKIE, verifySessionToken } from "@/lib/auth/session";

/**
 * The only way in. Every page and /api call needs a valid session cookie (unless AUTH_DISABLED=true locally).
 * /api/* is then forwarded to the backend on the private network with the internal token and the username;
 * the browser never talks to the backend directly.
 */
const STATE_CHANGING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const isApi = pathname.startsWith("/api/");

  let config;
  try {
    config = readAuthConfig();
  } catch (e) {
    // Fail closed: a misconfigured deployment serves nothing rather than serving it unprotected.
    const message = e instanceof AuthConfigError ? e.message : "Authentication is misconfigured";
    console.error(`[auth] ${message}`);
    return NextResponse.json({ error: "AUTH_MISCONFIGURED", message: "Sign-in is not configured on this server." }, { status: 503 });
  }

  if (pathname.startsWith("/api/auth/")) return NextResponse.next();

  const user = config.disabled
    ? null
    : verifySessionToken(request.cookies.get(SESSION_COOKIE)?.value, config.sessionSecret);

  if (pathname === "/login") {
    return user ? NextResponse.redirect(new URL("/", request.url)) : NextResponse.next();
  }

  if (!config.disabled && !user) {
    if (isApi) {
      return NextResponse.json({ error: "UNAUTHENTICATED", message: "Please sign in again." }, { status: 401 });
    }
    const login = new URL("/login", request.url);
    if (pathname !== "/") login.searchParams.set("next", pathname + search);
    return NextResponse.redirect(login);
  }

  if (!isApi) return NextResponse.next();

  if (STATE_CHANGING.has(request.method) && !isSameOrigin(request.headers)) {
    return NextResponse.json({ error: "FORBIDDEN", message: "Cross-site request blocked." }, { status: 403 });
  }

  const headers = new Headers(request.headers);
  headers.delete("cookie"); // the session never leaves the frontend
  headers.delete("x-user");
  headers.delete("x-internal-token");
  if (config.internalToken) headers.set("x-internal-token", config.internalToken);
  if (user) headers.set("x-user", user);
  return NextResponse.rewrite(new URL(pathname + search, config.backendUrl), { request: { headers } });
}

export const config = {
  // Everything except build assets. Samples in /public stay behind sign-in too.
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
