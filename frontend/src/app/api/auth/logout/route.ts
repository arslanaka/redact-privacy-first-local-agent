import { NextResponse, type NextRequest } from "next/server";
import { isHttps, isSameOrigin } from "@/lib/auth/request";
import { SESSION_COOKIE } from "@/lib/auth/session";

export async function POST(request: NextRequest) {
  if (!isSameOrigin(request.headers)) {
    return NextResponse.json({ error: "FORBIDDEN", message: "Cross-site request blocked." }, { status: 403 });
  }
  const response = NextResponse.json({ ok: true });
  response.cookies.set(SESSION_COOKIE, "", {
    httpOnly: true,
    secure: isHttps(request.headers, request.url),
    sameSite: "lax",
    path: "/",
    maxAge: 0,
  });
  return response;
}
