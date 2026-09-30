"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { SystemStatus } from "@/lib/types";

/** Loads the deployment's status (model speed, limits, prices) once per page. */
export function useSystemStatus(): SystemStatus | null {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  useEffect(() => {
    let cancelled = false;
    api.systemStatus().then(
      (s) => !cancelled && setStatus(s),
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, []);
  return status;
}
