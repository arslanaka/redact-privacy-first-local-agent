"use client";

import { Check, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { CategoryBadge, ReviewStatusBadge, SourceBadge } from "@/components/badges";
import { AnalysisStats } from "@/components/analysis-stats";
import { BulkApproveButton } from "@/components/bulk-approve-button";
import { ConfidenceIndicator } from "@/components/confidence-indicator";
import { DocumentNav, DocumentTitle } from "@/components/document-nav";
import { ErrorAlert } from "@/components/error-alert";
import { LinkButton } from "@/components/link-button";
import { MockBanner } from "@/components/mock-banner";
import { RiskPanel, SummaryCards } from "@/components/summary-cards";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useDocument, useDocumentId } from "@/hooks/use-document";
import { reviewProgress, useEntities } from "@/hooks/use-entities";
import { formatDuration } from "@/lib/format";
import { isProcessing } from "@/lib/stages";

export default function ResultsPage() {
  const id = useDocumentId();
  const router = useRouter();
  const { data: doc, error } = useDocument(id);
  const { entities, update, approveHighConfidence, error: entitiesError } = useEntities(id);

  useEffect(() => {
    if (doc && (isProcessing(doc.status) || doc.status === "FAILED" || doc.status === "UPLOADED")) {
      router.replace(`/documents/${id}/processing`);
    }
  }, [doc, id, router]);

  if (error) return <ErrorAlert message={error.message} />;
  if (!doc || !doc.summary) return <p className="text-muted-foreground">Loading…</p>;

  const progress = reviewProgress(entities);
  return (
    <div className="space-y-6">
      <DocumentTitle doc={doc} />
      <DocumentNav doc={doc} />
      <MockBanner doc={doc} />

      <RiskPanel summary={doc.summary} />
      <AnalysisStats summary={doc.summary} />
      <SummaryCards summary={doc.summary} />

      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3">
          <div>
            <CardTitle>Detected entities</CardTitle>
            <CardDescription>
              {progress.reviewed} / {progress.total} reviewed. Nothing is redacted until a person approves it.
            </CardDescription>
          </div>
          <div className="flex flex-wrap gap-2">
            {entities && <BulkApproveButton entities={entities} onConfirm={approveHighConfidence} />}
            <LinkButton href={`/documents/${id}/review`}>
              {progress.pending > 0 ? "Start review" : "Open review"}
            </LinkButton>
          </div>
        </CardHeader>
        <CardContent>
          {entitiesError && <ErrorAlert message={entitiesError} />}
          {entities && entities.length === 0 && (
            <p className="py-6 text-center text-sm text-muted-foreground">
              No personal data was detected. Detectors can miss things, so skim the document in the review screen and
              add manual redactions if needed.
            </p>
          )}
          {entities && entities.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Entity</TableHead>
                  <TableHead>Category</TableHead>
                  <TableHead>Confidence</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entities.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell className="max-w-64">
                      <span className="block truncate font-mono text-sm" title={e.text}>
                        {e.text}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground" title={e.recommendationReason}>
                        {e.recommendationReason}
                      </span>
                    </TableCell>
                    <TableCell>
                      <CategoryBadge type={e.type} />
                    </TableCell>
                    <TableCell>
                      <ConfidenceIndicator
                        confidence={e.confidence}
                        regexConfidence={e.regexConfidence}
                        modelConfidence={e.modelConfidence}
                      />
                    </TableCell>
                    <TableCell>
                      <SourceBadge source={e.source} />
                    </TableCell>
                    <TableCell>
                      <ReviewStatusBadge status={e.reviewStatus} />
                    </TableCell>
                    <TableCell className="text-right whitespace-nowrap">
                      <Button
                        size="icon-sm"
                        variant={e.reviewStatus === "APPROVED" ? "default" : "outline"}
                        aria-label={`Approve redaction of ${e.text}`}
                        title="Approve redaction"
                        onClick={() => void update(e.id, { reviewStatus: "APPROVED" })}
                      >
                        <Check />
                      </Button>{" "}
                      <Button
                        size="icon-sm"
                        variant={e.reviewStatus === "REJECTED" ? "secondary" : "outline"}
                        aria-label={`Reject redaction of ${e.text}`}
                        title="Reject (keep text)"
                        onClick={() => void update(e.id, { reviewStatus: "REJECTED" })}
                      >
                        <X />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Agent tool trace</CardTitle>
          <CardDescription>
            The orchestrator ran a fixed, auditable plan of local tools. No LLM decided anything.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ol className="space-y-2 text-sm">
            {doc.summary.toolCalls.map((t, i) => (
              <li key={t.tool} className="grid grid-cols-[1.5rem_13rem_5rem_1fr] items-baseline gap-2">
                <span className="text-muted-foreground tabular-nums">{i + 1}.</span>
                <code className="font-mono text-xs">{t.tool}()</code>
                <span className="text-right text-xs text-muted-foreground tabular-nums">{formatDuration(t.durationMs)}</span>
                <span>{t.summary}</span>
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>
    </div>
  );
}
