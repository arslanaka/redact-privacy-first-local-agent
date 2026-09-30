"use client";

import { useEffect, useMemo, useRef } from "react";
import { segmentText } from "@/lib/segments";
import { selectionOffsets } from "@/lib/selection";
import type { EntityDto } from "@/lib/types";
import { cn } from "@/lib/utils";

interface Props {
  text: string;
  entities: EntityDto[];
  currentId?: string;
  onEntityClick?: (id: string) => void;
  onSelectText?: (range: { start: number; end: number } | null) => void;
}

const STATUS_STYLE = {
  PENDING: "bg-amber-100 decoration-amber-500 underline decoration-2 underline-offset-2",
  APPROVED: "bg-zinc-900 text-white",
  REJECTED: "bg-zinc-100 text-zinc-500 line-through decoration-zinc-400",
} as const;

/**
 * The extracted document text with every detected entity highlighted by review status.
 * Selecting text reports character offsets, used to create manual redactions.
 */
export function DocumentText({ text, entities, currentId, onEntityClick, onSelectText }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const segments = useMemo(
    () => segmentText(text, entities.map((e) => ({ start: e.startOffset, end: e.endOffset, id: e.id, entity: e }))),
    [text, entities],
  );

  useEffect(() => {
    if (!currentId) return;
    containerRef.current
      ?.querySelector(`[data-entity-id="${currentId}"]`)
      ?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [currentId]);

  function handleMouseUp() {
    if (!onSelectText || !containerRef.current) return;
    onSelectText(selectionOffsets(containerRef.current, window.getSelection()));
  }

  return (
    <div
      ref={containerRef}
      onMouseUp={handleMouseUp}
      className="font-mono text-[13px] leading-6 break-words whitespace-pre-wrap"
      data-testid="document-text"
    >
      {segments.map((seg) =>
        seg.span ? (
          <mark
            key={seg.start}
            data-entity-id={seg.span.id}
            onClick={() => onEntityClick?.(seg.span!.id!)}
            className={cn(
              "cursor-pointer rounded-sm px-0.5 text-inherit",
              STATUS_STYLE[seg.span.entity.reviewStatus],
              seg.span.id === currentId && "ring-2 ring-sky-500 ring-offset-1",
            )}
            title={`${seg.span.entity.type} · ${seg.span.entity.reviewStatus.toLowerCase()}`}
          >
            {seg.text}
          </mark>
        ) : (
          <span key={seg.start}>{seg.text}</span>
        ),
      )}
    </div>
  );
}
