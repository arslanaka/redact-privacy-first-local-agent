"use client";

import { Gauge } from "lucide-react";
import { formatDuration } from "@/lib/format";
import {
  CHARS_PER_PAGE,
  analysisCostUsd,
  baselineMonthlyUsd,
  estimateSeconds,
  formatChars,
  formatUsd,
  maxCharsPerDocument,
} from "@/lib/estimate";
import type { SystemStatus } from "@/lib/types";

/**
 * What this deployment can do and roughly what it costs, from measured model speed and configured prices.
 * Collapsed by default so it informs without getting in the way of uploading.
 */
export function CapacityCard({ status }: { status: SystemStatus | null }) {
  if (!status?.capacity) return null;
  const { capacity, cost } = status;
  const pageSeconds = estimateSeconds(CHARS_PER_PAGE, capacity);
  const pageCost = pageSeconds === null ? null : analysisCostUsd(pageSeconds, capacity, cost);
  const maxChars = maxCharsPerDocument(capacity);
  const baseline = baselineMonthlyUsd(cost);
  const host = cost?.host ?? "this server";

  return (
    <details className="group rounded-lg border bg-white px-4 py-3 text-sm" data-testid="capacity-card">
      <summary className="flex cursor-pointer list-none items-center gap-2 font-medium">
        <Gauge className="size-4 text-muted-foreground" />
        Capacity &amp; cost on this deployment
        <span className="ml-auto text-xs font-normal text-muted-foreground group-open:hidden">
          {pageSeconds !== null ? `~${formatDuration(pageSeconds * 1000)} per page` : "details"}
        </span>
      </summary>
      <dl className="mt-3 grid grid-cols-[9.5rem_1fr] gap-x-4 gap-y-2">
        <dt className="text-muted-foreground">Model speed</dt>
        <dd>
          {capacity.spansPerSecond
            ? `${capacity.spansPerSecond.toFixed(1)} candidate spans/s on ${capacity.cpuThreads ?? "?"} CPU threads (measured at start-up)`
            : status.modelIsMock
              ? "Mock classifier: near-instant, not representative"
              : "Measuring… (the model is warming up)"}
        </dd>

        {pageSeconds !== null && (
          <>
            <dt className="text-muted-foreground">One page of text</dt>
            <dd>
              ~{formatDuration(pageSeconds * 1000)} · est. {formatUsd(pageCost)} compute on {host}
            </dd>
          </>
        )}

        <dt className="text-muted-foreground">Largest document</dt>
        <dd>
          {maxChars !== null
            ? `~${formatChars(maxChars)} characters (~${Math.max(1, Math.floor(maxChars / CHARS_PER_PAGE))} pages of text)`
            : "–"}
          <span className="block text-xs text-muted-foreground">
            Each document is scored in one model call that must finish within{" "}
            {formatDuration(capacity.aiTimeoutSeconds * 1000)}. Files up to{" "}
            {Math.round(status.maxUploadBytes / (1024 * 1024))} MB; PDFs need a text layer (no OCR).
          </span>
        </dd>

        <dt className="text-muted-foreground">Concurrency</dt>
        <dd>
          The model scores one document at a time; up to {capacity.analysisQueueCapacity} more wait in a queue, so a
          long document delays everyone else&apos;s.
        </dd>

        {baseline !== null && (
          <>
            <dt className="text-muted-foreground">Always-on cost</dt>
            <dd>
              ~${Math.round(baseline)}/month on {host}, mostly the model held in memory ({cost?.modelMemoryGb} GB),
              whether or not documents are processed.
            </dd>
          </>
        )}

        <dt className="text-muted-foreground">Retention</dt>
        <dd>Everything is deleted after {status.retentionHours} h, or on demand.</dd>
      </dl>
      <p className="mt-3 text-xs text-muted-foreground">
        Estimates use measured speed and {host} usage prices; your invoice is the source of truth.
      </p>
    </details>
  );
}
