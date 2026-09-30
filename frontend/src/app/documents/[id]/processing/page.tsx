"use client";

import { AlertTriangle, Bookmark, CheckCircle2, Circle, Clock, Loader2, XCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { ErrorAlert } from "@/components/error-alert";
import { LinkButton } from "@/components/link-button";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { useDocument, useDocumentId } from "@/hooks/use-document";
import { useSystemStatus } from "@/hooks/use-system-status";
import { api, ApiError, friendlyMessage } from "@/lib/api";
import { analysisCostUsd, estimateSeconds, estimateSpans, formatUsd } from "@/lib/estimate";
import { formatDuration } from "@/lib/format";
import { processingSteps, type StepState } from "@/lib/stages";

const ICON: Record<StepState, React.ReactNode> = {
  done: <CheckCircle2 className="size-5 text-emerald-600" />,
  active: <Loader2 className="size-5 animate-spin text-zinc-900" />,
  pending: <Circle className="size-5 text-zinc-300" />,
  failed: <XCircle className="size-5 text-red-600" />,
};

export default function ProcessingPage() {
  const id = useDocumentId();
  const router = useRouter();
  const { data: doc, error, restart } = useDocument(id);
  const status = useSystemStatus();
  const [retrying, setRetrying] = useState(false);
  // Counted from the upload, so a page reload doesn't reset the timer; a retry restarts it.
  const [retriedAt, setRetriedAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const startedAt = retriedAt ?? (doc ? Date.parse(doc.createdAt) : now);
  const elapsedMs = Math.max(0, now - startedAt);

  const done = doc && ["REVIEW_REQUIRED", "APPROVED", "COMPLETED"].includes(doc.status);
  const failed = doc?.status === "FAILED";

  useEffect(() => {
    if (done) {
      const t = setTimeout(() => router.push(`/documents/${id}`), 900);
      return () => clearTimeout(t);
    }
  }, [done, id, router]);

  useEffect(() => {
    if (done || failed) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [done, failed]);

  async function retry() {
    setRetrying(true);
    try {
      await api.analyse(id);
      setRetriedAt(Date.now());
      restart();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Retry failed");
    } finally {
      setRetrying(false);
    }
  }

  if (error) return <ErrorAlert message={error.message} />;
  if (!doc) return <p className="text-muted-foreground">Loading…</p>;

  const steps = processingSteps(doc.status, doc.currentStage);
  // Known once the text is extracted: expected time from its length and the model's measured speed.
  const estimateSec =
    doc.textCharacters !== undefined ? (estimateSeconds(doc.textCharacters, status?.capacity) ?? null) : null;
  const estimate = estimateSec === null ? null : estimateSec * 1000;
  const slow = estimate !== null && elapsedMs > estimate * 1.5 + 10_000;
  const pct = estimate ? Math.min(95, Math.round((elapsedMs / estimate) * 100)) : null;
  return (
    <div className="mx-auto max-w-xl space-y-6 py-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Analysing {doc.originalFilename}</h1>
        <p className="text-sm text-muted-foreground">
          Everything runs on this application&apos;s own server: deterministic rules first, then the local Laya
          classifier.
        </p>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Privacy analysis</CardTitle>
        </CardHeader>
        <CardContent>
          <ol className="space-y-3">
            {steps.map((s) => (
              <li key={s.key} className="flex items-center gap-3">
                {ICON[s.state]}
                <span className={s.state === "pending" ? "text-muted-foreground" : ""}>{s.label}</span>
                {s.key === "model" && s.state === "active" && (
                  <span className="ml-auto text-xs text-muted-foreground tabular-nums">
                    {estimate !== null
                      ? `${formatDuration(elapsedMs)} of ~${formatDuration(estimate)}`
                      : `${formatDuration(elapsedMs)} · long documents can take a few minutes on CPU`}
                  </span>
                )}
              </li>
            ))}
          </ol>
          {!done && !failed && pct !== null && doc.textCharacters !== undefined && (
            <div className="mt-5 space-y-2" data-testid="eta">
              <Progress value={pct} />
              <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Clock className="size-3.5" />
                About {formatDuration(estimate!)} for {doc.textCharacters.toLocaleString("en-GB")} characters (~
                {estimateSpans(doc.textCharacters)} candidate spans at {status?.capacity?.spansPerSecond?.toFixed(1)}/s) ·
                est. {formatUsd(analysisCostUsd(estimate! / 1000, status?.capacity, status?.cost))} on{" "}
                {status?.cost?.host ?? "this server"}
              </p>
            </div>
          )}
        </CardContent>
      </Card>

      {slow && !done && !failed && (
        <p className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <Clock className="mt-0.5 size-4 shrink-0" /> Taking longer than estimated. The model scores one document at a
          time, so another document may be ahead in the queue.
        </p>
      )}

      {doc.model?.isMock && (
        <p className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <AlertTriangle className="size-4" /> The AI service is running the MOCK classifier, not Laya.
        </p>
      )}

      {failed && doc.error && (
        <div className="space-y-3">
          <ErrorAlert title="Analysis failed" message={friendlyMessage(doc.error.code, doc.error.message)} />
          <div className="flex gap-2">
            <Button onClick={retry} disabled={retrying}>
              {retrying ? "Retrying…" : "Retry analysis"}
            </Button>
            <LinkButton variant="outline" href="/">
              Upload another document
            </LinkButton>
          </div>
        </div>
      )}

      {!done && !failed && (
        <div className="space-y-1 text-sm text-muted-foreground">
          <p className="tabular-nums" data-testid="elapsed">
            Elapsed: {formatDuration(elapsedMs)}
          </p>
          <p className="flex items-center gap-1.5 text-xs">
            <Bookmark className="size-3.5" /> You can leave this page: the analysis keeps running on the server.
            Bookmark this address to come back to the results.
          </p>
        </div>
      )}
      {done && <p className="text-sm text-emerald-700">Analysis complete. Opening results…</p>}
    </div>
  );
}
