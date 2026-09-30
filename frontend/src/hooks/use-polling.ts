"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Loads data and keeps polling while `shouldPoll(data)` is true.
 * `reload` fetches once; `restart` fetches and resumes polling (e.g. after retrying an analysis).
 */
export function usePolling<T>(
  load: () => Promise<T>,
  { intervalMs = 1000, shouldPoll }: { intervalMs?: number; shouldPoll?: (data: T) => boolean } = {},
) {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<Error | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const loadRef = useRef(load);
  const pollRef = useRef(shouldPoll);
  useEffect(() => {
    loadRef.current = load;
    pollRef.current = shouldPoll;
  });

  const reload = useCallback(async () => {
    try {
      const next = await loadRef.current();
      setData(next);
      setError(undefined);
      return next;
    } catch (e) {
      setError(e as Error);
      return undefined;
    }
  }, []);

  const restart = useCallback(() => setGeneration((g) => g + 1), []);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      const next = await reload();
      if (!cancelled && next !== undefined && pollRef.current?.(next)) {
        timer = setTimeout(tick, intervalMs);
      }
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [reload, intervalMs, generation]);

  return { data, error, reload, restart, setData };
}
