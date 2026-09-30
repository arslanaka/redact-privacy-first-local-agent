"use client";

import { AlertTriangle, CheckCircle2, Download, FileSearch, RotateCcw, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { AuditTimeline } from "@/components/audit-timeline";
import { CategoryBadge, RiskBadge } from "@/components/badges";
import { DocumentNav, DocumentTitle, formatBytes } from "@/components/document-nav";
import { ErrorAlert } from "@/components/error-alert";
import { LinkButton } from "@/components/link-button";
import { MockBanner } from "@/components/mock-banner";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useDocument, useDocumentId } from "@/hooks/use-document";
import { reviewProgress, useEntities } from "@/hooks/use-entities";
import { api, ApiError } from "@/lib/api";
import { segmentText } from "@/lib/segments";
import { cn } from "@/lib/utils";
import type { AuditEventDto, PiiType, PreviewDto } from "@/lib/types";

export default function OutputPage() {
  const id = useDocumentId();
  const router = useRouter();
  const { data: doc, error, reload: reloadDoc } = useDocument(id);
  const { entities, addManual, reload: reloadEntities } = useEntities(id);
  const [preview, setPreview] = useState<PreviewDto | undefined>(undefined);
  const [audit, setAudit] = useState<AuditEventDto[]>([]);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const refresh = useCallback(async () => {
    const [p, a] = await loadOutputData(id);
    setPreview(p);
    setAudit(a);
  }, [id]);

  useEffect(() => {
    let cancelled = false;
    loadOutputData(id).then(
      ([p, a]) => {
        if (!cancelled) {
          setPreview(p);
          setAudit(a);
        }
      },
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, [id]);

  async function generate() {
    setBusy(true);
    try {
      await api.redact(id);
      toast.success("Redacted document generated and verified");
      await Promise.all([reloadDoc(), refresh()]);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Redaction failed");
    } finally {
      setBusy(false);
    }
  }

  async function redactRemaining(entityId: string) {
    const entity = entities?.find((e) => e.id === entityId);
    if (!entity) return;
    await addManual({ text: entity.text, type: entity.type, allOccurrences: true });
    await Promise.all([reloadDoc(), refresh(), reloadEntities()]);
  }

  async function remove() {
    try {
      await api.remove(id);
      toast.success("Document and all derived data deleted");
      router.push("/");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Delete failed");
    }
  }

  if (error) return <ErrorAlert message={error.message} />;
  if (!doc || !preview || !entities) return <p className="text-muted-foreground">Loading…</p>;

  const progress = reviewProgress(entities);
  const completed = doc.status === "COMPLETED";
  const report = doc.redaction;
  return (
    <div className="space-y-6">
      <DocumentTitle doc={doc} />
      <DocumentNav doc={doc} />
      <MockBanner doc={doc} />

      {progress.pending > 0 ? (
        <Card>
          <CardContent className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm">
              <strong>{progress.pending}</strong> detected item(s) still need a decision before a redacted document can
              be generated.
            </p>
            <LinkButton href={`/documents/${id}/review`}>Continue review</LinkButton>
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <Button onClick={generate} disabled={busy} variant={completed ? "outline" : "default"}>
            {completed ? <RotateCcw /> : null}
            {busy ? "Generating…" : completed ? "Regenerate" : "Generate Redacted Document"}
          </Button>
          {completed && (
            <Button nativeButton={false} render={<a href={api.downloadUrl(id)} />}>
              <Download /> Download Redacted File
            </Button>
          )}
          <LinkButton href={`/documents/${id}/audit`} variant="outline">
            <FileSearch /> View Audit Trail
          </LinkButton>
          <LinkButton href="/" variant="ghost">
            Analyse Another Document
          </LinkButton>
          <Button variant="ghost" className="ml-auto text-red-700" onClick={() => setConfirmDelete(true)}>
            <Trash2 /> Delete document
          </Button>
        </div>
      )}

      {report && completed && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CheckCircle2 className="size-5 text-emerald-600" /> Output verified
            </CardTitle>
            <CardDescription>
              {report.redactedRegions} region(s) redacted · {report.outputFormat} · {formatBytes(report.outputBytes)} ·{" "}
              {report.style} style
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <ul className="space-y-1">
              {report.verificationChecks.map((c) => (
                <li key={c} className="flex gap-2">
                  <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-600" /> {c}
                </li>
              ))}
            </ul>
            {report.residuals.length > 0 && (
              <div className="space-y-2 rounded-md border border-amber-200 bg-amber-50 p-3">
                <p className="flex items-center gap-2 font-medium text-amber-900">
                  <AlertTriangle className="size-4" /> Approved values that still appear elsewhere, un-redacted
                </p>
                {report.residuals.map((r) => {
                  const entity = entities.find((e) => e.id === r.entityId);
                  return (
                    <div key={r.entityId} className="flex flex-wrap items-center justify-between gap-2">
                      <span>
                        <span className="font-mono">{entity?.text ?? r.type}</span> appears {r.remainingOccurrences} more
                        time(s)
                      </span>
                      <Button size="sm" variant="outline" onClick={() => void redactRemaining(r.entityId)}>
                        Redact remaining occurrences
                      </Button>
                    </div>
                  );
                })}
              </div>
            )}
            {report.limitations.length > 0 && (
              <ul className="list-disc pl-5 text-xs text-muted-foreground">
                {report.limitations.map((l) => (
                  <li key={l}>{l}</li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <TextPanel
          title="Original"
          description="Approved redactions highlighted"
          text={preview.originalText}
          spans={preview.approvedSpans.map((s) => ({ start: s.start, end: s.end, id: s.entityId }))}
          highlightClass="bg-amber-200"
        />
        <TextPanel
          title="Redacted"
          description={completed ? "Content of the generated file" : "Preview: not generated yet"}
          text={preview.redactedText}
          spans={preview.redactedRegions.map(([start, end]) => ({ start, end }))}
          highlightClass="text-zinc-900"
          testId="redacted-text"
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {doc.summary && (
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-3">
                Privacy analysis summary <RiskBadge level={doc.summary.riskLevel} />
              </CardTitle>
              <CardDescription>{doc.summary.recommendation}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <p>
                {doc.summary.totalDetections} candidates · {progress.total - progress.pending} reviewed ·{" "}
                {entities.filter((e) => e.reviewStatus === "APPROVED").length} redacted ·{" "}
                {entities.filter((e) => e.reviewStatus === "REJECTED").length} kept ·{" "}
                {entities.filter((e) => e.source === "MANUAL").length} added manually
              </p>
              <div className="flex flex-wrap gap-2">
                {Object.keys(doc.summary.categories).map((c) => (
                  <CategoryBadge key={c} type={c as PiiType} />
                ))}
              </div>
            </CardContent>
          </Card>
        )}
        <Card>
          <CardHeader>
            <CardTitle>Audit summary</CardTitle>
            <CardDescription>Latest events. The full trail is on the audit page.</CardDescription>
          </CardHeader>
          <CardContent>
            <AuditTimeline events={audit} limit={6} />
          </CardContent>
        </Card>
      </div>

      <AlertDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this document?</AlertDialogTitle>
            <AlertDialogDescription>
              Removes the original file, the redacted file, the extracted text, all detections and the audit trail. A
              single deletion record is kept. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={() => void remove()}>
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function loadOutputData(id: string) {
  return Promise.all([api.preview(id), api.audit(id)]);
}

function TextPanel(props: {
  title: string;
  description: string;
  text: string;
  spans: { start: number; end: number; id?: string }[];
  highlightClass: string;
  testId?: string;
}) {
  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle>{props.title}</CardTitle>
        <CardDescription>{props.description}</CardDescription>
      </CardHeader>
      <CardContent className="max-h-[60vh] overflow-auto">
        <div className="font-mono text-[13px] leading-6 break-words whitespace-pre-wrap" data-testid={props.testId}>
          {segmentText(props.text, props.spans).map((seg) =>
            seg.span ? (
              <mark key={seg.start} className={cn("rounded-sm bg-transparent", props.highlightClass)}>
                {seg.text}
              </mark>
            ) : (
              <span key={seg.start}>{seg.text}</span>
            ),
          )}
        </div>
      </CardContent>
    </Card>
  );
}
