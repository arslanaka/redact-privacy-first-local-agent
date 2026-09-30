"use client";

import { LogOut } from "lucide-react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";

export function SignOutButton({ user }: { user: string }) {
  const router = useRouter();

  async function signOut() {
    await fetch("/api/auth/logout", { method: "POST" }).catch(() => undefined);
    router.replace("/login");
    router.refresh(); // re-render the server header without the session
  }

  return (
    <div className="flex items-center gap-2 text-sm text-muted-foreground">
      <span>{user}</span>
      <Button variant="ghost" size="sm" onClick={() => void signOut()} aria-label="Sign out">
        <LogOut /> Sign out
      </Button>
    </div>
  );
}
