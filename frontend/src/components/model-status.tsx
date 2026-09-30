"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { SystemStatus } from "@/lib/types";
import { cn } from "@/lib/utils";

/** Small live indicator of the local model. Makes a mock or unavailable model impossible to miss. */
export function ModelStatus() {
  const [status, setStatus] = useState<SystemStatus | null | undefined>(undefined);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const s = await api.systemStatus();
        setStatus(s);
        timer = setTimeout(tick, s.aiService === "ready" ? 30000 : 5000);
      } catch {
        setStatus(null);
        timer = setTimeout(tick, 5000);
      }
    };
    void tick();
    return () => clearTimeout(timer);
  }, []);

  let dot = "bg-zinc-300";
  let label = "Checking model…";
  if (status === null) {
    dot = "bg-red-500";
    label = "Backend unreachable";
  } else if (status) {
    if (status.aiService === "ready") {
      dot = status.modelIsMock ? "bg-amber-500" : "bg-emerald-500";
      label = status.modelIsMock ? "MOCK model (not Laya)" : `Local model: ${status.model ?? "laya"}`;
    } else if (status.aiService === "loading") {
      dot = "bg-amber-400 animate-pulse";
      label = "Local model loading…";
    } else {
      dot = "bg-red-500";
      label = "Local model unavailable";
    }
  }
  return (
    <span
      className="inline-flex items-center gap-2 rounded-full border px-2.5 py-1 text-xs text-muted-foreground"
      title={status?.detail ?? undefined}
      data-testid="model-status"
    >
      <span className={cn("size-2 rounded-full", dot)} />
      {label}
    </span>
  );
}
