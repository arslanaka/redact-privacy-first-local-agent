"use client";

import { ArrowLeft, ArrowRight, Check, Keyboard, Plus, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CategoryBadge, RecommendationBadge, ReviewStatusBadge, SourceBadge, categoryLabel } from "@/components/badges";
import { BulkApproveButton } from "@/components/bulk-approve-button";
import { ConfidenceIndicator } from "@/components/confidence-indicator";
import { DocumentNav, DocumentTitle } from "@/components/document-nav";
import { DocumentText } from "@/components/document-text";
import { ErrorAlert } from "@/components/error-alert";
import { LinkButton } from "@/components/link-button";
import { MockBanner } from "@/components/mock-banner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { useDocument, useDocumentId } from "@/hooks/use-document";
import { reviewProgress, useEntities, type ManualRedactionBody } from "@/hooks/use-entities";
import { api } from "@/lib/api";
import { nextPendingIndex } from "@/lib/review";
import { PII_TYPES, type EntityDto, type PiiType } from "@/lib/types";

export default function ReviewPage() {
  const id = useDocumentId();
  const { data: doc, error } = useDocument(id);
  const { entities, update, approveHighConfidence, addManual } = useEntities(id);
  const [text, setText] = useState<string | undefined>(undefined);
  const [index, setIndex] = useState(0);
  const [selection, setSelection] = useState<{ start: number; end: number } | null>(null);

  useEffect(() => {
    api.preview(id).then((p) => setText(p.originalText), () => setText(""));
  }, [id]);

  // Start on the first pending entity once they are loaded (derived state, set during render once).
  const [initialised, setInitialised] = useState(false);
  if (entities && !initialised) {
    const first = entities.findIndex((e) => e.reviewStatus === "PENDING");
    setIndex(first >= 0 ? first : 0);
    setInitialised(true);
  }

  const current: EntityDto | undefined = entities?.[index];
  const progress = reviewProgress(entities);

  // Ignore further decisions until the current one is saved, so fast keyboard use never acts on a stale entity.
  const deciding = useRef(false);
  const decide = useCallback(
    async (decision: "APPROVED" | "REJECTED") => {
      if (!current || !entities || deciding.current) return;
      deciding.current = true;
      try {
        if (await update(current.id, { reviewStatus: decision })) {
          const updated = entities.map((e) => (e.id === current.id ? { ...e, reviewStatus: decision } : e));
          setIndex(nextPendingIndex(updated, index));
        }
      } finally {
        deciding.current = false;
      }
    },
    [current, entities, index, update],
  );

  const go = useCallback(
    (delta: number) => {
      if (!entities?.length) return;
      setIndex((i) => (i + delta + entities.length) % entities.length);
    },
    [entities],
  );

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement)?.tagName;
      if (["INPUT", "SELECT", "TEXTAREA"].includes(tag) || e.metaKey || e.ctrlKey) return;
      if (e.key === "a") void decide("APPROVED");
      else if (e.key === "r") void decide("REJECTED");
      else if (e.key === "n" || e.key === "ArrowRight") go(1);
      else if (e.key === "p" || e.key === "ArrowLeft") go(-1);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [decide, go]);

  const entityIndexById = useMemo(() => new Map(entities?.map((e, i) => [e.id, i]) ?? []), [entities]);

  if (error) return <ErrorAlert message={error.message} />;
  if (!doc || !entities || text === undefined) return <p className="text-muted-foreground">Loading…</p>;

  const pct = progress.total ? Math.round((progress.reviewed / progress.total) * 100) : 100;
  return (
    <div className="space-y-6">
      <DocumentTitle doc={doc} />
      <DocumentNav doc={doc} />
      <MockBanner doc={doc} />

      <div className="flex flex-wrap items-center gap-4">
        <div className="min-w-56 flex-1">
          <div className="mb-1 flex justify-between text-sm">
            <span className="font-medium" data-testid="review-progress">
              {progress.reviewed} / {progress.total} reviewed
            </span>
            <span className="text-muted-foreground">{progress.pending} pending</span>
          </div>
          <Progress value={pct} />
        </div>
        <BulkApproveButton entities={entities} onConfirm={approveHighConfidence} />
        <LinkButton href={`/documents/${id}/output`} variant={progress.pending === 0 ? "default" : "outline"}>
          {progress.pending === 0 ? "Continue to redaction" : "Redaction (after review)"}
        </LinkButton>
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_24rem]">
        <Card className="min-w-0">
          <CardHeader>
            <CardTitle>Document context</CardTitle>
            <CardDescription>
              Click a highlight to review it. Select any text to add a redaction the detectors missed.
            </CardDescription>
          </CardHeader>
          <CardContent className="max-h-[70vh] overflow-auto">
            <DocumentText
              text={text}
              entities={entities}
              currentId={current?.id}
              onEntityClick={(eid) => setIndex(entityIndexById.get(eid) ?? 0)}
              onSelectText={setSelection}
            />
          </CardContent>
        </Card>

        <div className="space-y-4">
          {current ? (
            <EntityCard
              entity={current}
              position={index + 1}
              total={entities.length}
              onDecide={decide}
              onPrev={() => go(-1)}
              onNext={() => go(1)}
              onChangeType={(type) => void update(current.id, { type })}
            />
          ) : (
            <Card>
              <CardContent className="py-6 text-sm text-muted-foreground">
                No detected entities. Select text in the document to add manual redactions.
              </CardContent>
            </Card>
          )}
          <ManualRedactionCard
            text={text}
            selection={selection}
            onAdd={async (body) => {
              await addManual(body);
              setSelection(null);
              window.getSelection()?.removeAllRanges();
            }}
          />
          <p className="flex items-center gap-2 text-xs text-muted-foreground">
            <Keyboard className="size-3.5" /> Shortcuts: <kbd>A</kbd> approve · <kbd>R</kbd> reject · <kbd>N</kbd>/→
            next · <kbd>P</kbd>/← previous
          </p>
        </div>
      </div>
    </div>
  );
}

function EntityCard(props: {
  entity: EntityDto;
  position: number;
  total: number;
  onDecide: (d: "APPROVED" | "REJECTED") => void;
  onPrev: () => void;
  onNext: () => void;
  onChangeType: (t: PiiType) => void;
}) {
  const { entity: e } = props;
  return (
    <Card data-testid="entity-card">
      <CardHeader>
        <CardDescription>
          Entity {props.position} of {props.total}
        </CardDescription>
        <CardTitle className="font-mono text-base break-all">{e.text}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <CategoryBadge type={e.type} />
          <SourceBadge source={e.source} />
          <ReviewStatusBadge status={e.reviewStatus} />
        </div>
        <dl className="grid grid-cols-[7rem_1fr] gap-y-2 text-sm">
          <dt className="text-muted-foreground">Confidence</dt>
          <dd>
            <ConfidenceIndicator confidence={e.confidence} regexConfidence={e.regexConfidence} modelConfidence={e.modelConfidence} />
          </dd>
          <dt className="text-muted-foreground">Position</dt>
          <dd className="tabular-nums">
            chars {e.startOffset}–{e.endOffset}
          </dd>
          {e.modelLabels && (
            <>
              <dt className="text-muted-foreground">Model labels</dt>
              <dd>{e.modelLabels}</dd>
            </>
          )}
          {e.detectorNote && (
            <>
              <dt className="text-muted-foreground">Detector</dt>
              <dd>{e.detectorNote}</dd>
            </>
          )}
        </dl>
        <div className="rounded-md border bg-zinc-50 p-3 font-mono text-xs leading-5 break-words whitespace-pre-wrap">
          <span className="text-muted-foreground">…{e.contextBefore}</span>
          <mark className="rounded-sm bg-sky-100 px-0.5">{e.text}</mark>
          <span className="text-muted-foreground">{e.contextAfter}…</span>
        </div>
        <div className="space-y-1">
          <RecommendationBadge recommendation={e.recommendation} />
          <p className="text-sm text-muted-foreground">{e.recommendationReason}</p>
        </div>
        <label className="flex items-center justify-between gap-2 text-sm">
          <span className="text-muted-foreground">Change category</span>
          <select
            className="h-8 rounded-md border bg-white px-2 text-sm"
            value={e.type}
            onChange={(ev) => props.onChangeType(ev.target.value as PiiType)}
            aria-label="Change category"
          >
            {PII_TYPES.map((t) => (
              <option key={t} value={t}>
                {categoryLabel(t)}
              </option>
            ))}
          </select>
        </label>
        <div className="grid grid-cols-2 gap-2">
          <Button onClick={() => props.onDecide("APPROVED")} variant={e.reviewStatus === "APPROVED" ? "default" : "outline"}>
            <Check /> Approve
          </Button>
          <Button onClick={() => props.onDecide("REJECTED")} variant={e.reviewStatus === "REJECTED" ? "secondary" : "outline"}>
            <X /> Reject
          </Button>
        </div>
        <div className="flex justify-between">
          <Button variant="ghost" size="sm" onClick={props.onPrev}>
            <ArrowLeft /> Previous
          </Button>
          <Button variant="ghost" size="sm" onClick={props.onNext}>
            Next entity <ArrowRight />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function ManualRedactionCard({
  text,
  selection,
  onAdd,
}: {
  text: string;
  selection: { start: number; end: number } | null;
  onAdd: (body: ManualRedactionBody) => Promise<void>;
}) {
  const [type, setType] = useState<PiiType>("PERSON");
  const [search, setSearch] = useState("");
  const [allOccurrences, setAllOccurrences] = useState(true);
  const selected = selection ? text.slice(selection.start, selection.end) : "";
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle className="text-sm">Add manual redaction</CardTitle>
        <CardDescription>For anything the detectors missed. Manual redactions are approved immediately.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <label className="flex items-center justify-between gap-2 text-sm">
          <span className="text-muted-foreground">Category</span>
          <select
            className="h-8 rounded-md border bg-white px-2 text-sm"
            value={type}
            onChange={(e) => setType(e.target.value as PiiType)}
            aria-label="Manual redaction category"
          >
            {PII_TYPES.map((t) => (
              <option key={t} value={t}>
                {categoryLabel(t)}
              </option>
            ))}
          </select>
        </label>
        {selection && selected.trim() ? (
          <div className="space-y-2">
            <p className="text-sm">
              Selected: <span className="font-mono break-all">&ldquo;{selected}&rdquo;</span>
            </p>
            <Button size="sm" className="w-full" onClick={() => void onAdd({ startOffset: selection.start, endOffset: selection.end, type })}>
              <Plus /> Redact selection
            </Button>
          </div>
        ) : (
          <form
            className="space-y-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (search.trim()) void onAdd({ text: search, type, allOccurrences }).then(() => setSearch(""));
            }}
          >
            <Input placeholder="…or type exact text to find" value={search} onChange={(e) => setSearch(e.target.value)} />
            <label className="flex items-center gap-2 text-xs text-muted-foreground">
              <input type="checkbox" checked={allOccurrences} onChange={(e) => setAllOccurrences(e.target.checked)} />
              Redact every occurrence
            </label>
            <Button size="sm" variant="outline" className="w-full" type="submit" disabled={!search.trim()}>
              <Plus /> Find and redact
            </Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
