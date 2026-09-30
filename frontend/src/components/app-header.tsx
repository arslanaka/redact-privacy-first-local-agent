import Link from "next/link";
import { cookies } from "next/headers";
import { ShieldCheck } from "lucide-react";
import { ModelStatus } from "@/components/model-status";
import { SignOutButton } from "@/components/sign-out-button";
import { readAuthConfig } from "@/lib/auth/config";
import { SESSION_COOKIE, verifySessionToken } from "@/lib/auth/session";

/** Who is signed in: a username, "anonymous" when sign-in is disabled locally, or null on the sign-in page. */
async function currentUser(): Promise<string | null> {
  // Read cookies first and outside the try: it is what marks the header as per-request (dynamic).
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  let config;
  try {
    config = readAuthConfig();
  } catch {
    return null; // misconfigured: the proxy already answers 503
  }
  return config.disabled ? "anonymous" : verifySessionToken(token, config.sessionSecret);
}

export async function AppHeader() {
  const user = await currentUser();
  return (
    <header className="border-b bg-white">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
        <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
          <ShieldCheck className="size-5" />
          Local Privacy Agent
        </Link>
        {user && (
          <div className="flex items-center gap-4">
            <ModelStatus />
            {user !== "anonymous" && <SignOutButton user={user} />}
          </div>
        )}
      </div>
    </header>
  );
}
