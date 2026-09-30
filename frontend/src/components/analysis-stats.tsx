"use client";

import { Clock, Coins, ScanSearch } from "lucide-react";
import { useSystemStatus } from "@/hooks/use-system-status";
import { analysisCostUsd, formatUsd } from "@/lib/estimate";
import { formatDuration } from "@/lib/format";
import type { PrivacySummary } from "@/lib/types";

/** How long the analysis took, how much the model did, and what that compute costs at the host's prices. */
export function AnalysisStats({ summary }: { summary: PrivacySummary }) {
  const status = useSystemStatus();
  const totalMs = summary.toolCalls.reduce((sum, t) => sum + t.durationMs, 0);
  const { candidatesScored, inferenceMs, isMock } = summary.model;
  const cost = isMock ? null : analysisCostUsd(inferenceMs / 1000, status?.capacity, status?.cost);

  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-lg border bg-white px-4 py-3 text-sm" data-testid="analysis-stats">
      <span className="flex items-center gap-2">
        <Clock className="size-4 text-muted-foreground" />
        Analysed in <strong className="font-semibold tabular-nums">{formatDuration(totalMs)}</strong>
      </span>
      {candidatesScored > 0 && (
        <span className="flex items-center gap-2 text-muted-foreground">
          <ScanSearch className="size-4" />
          {candidatesScored} candidate spans scored in {formatDuration(inferenceMs)}
          {inferenceMs > 0 && ` (${(candidatesScored / (inferenceMs / 1000)).toFixed(1)}/s)`}
        </span>
      )}
      {cost !== null && (
        <span
          className="flex items-center gap-2 text-muted-foreground"
          title={`Model time × ${status?.capacity?.cpuThreads ?? "?"} CPU threads + ${status?.cost?.modelMemoryGb} GB RAM at ${status?.cost?.host} usage prices. Excludes the always-on baseline.`}
        >
          <Coins className="size-4" />
          est. {formatUsd(cost)} compute on {status?.cost?.host}
        </span>
      )}
    </div>
  );
}
