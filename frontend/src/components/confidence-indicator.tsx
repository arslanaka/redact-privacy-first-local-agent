"use client";

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { CONFIDENCE_EXPLANATION, CONFIDENCE_LABEL, confidenceLevel, formatConfidence } from "@/lib/confidence";
import { cn } from "@/lib/utils";

const BAR: Record<string, string> = {
  HIGH: "bg-zinc-800",
  MEDIUM: "bg-amber-500",
  LOW: "bg-zinc-300",
};

interface Props {
  confidence: number;
  regexConfidence?: number;
  modelConfidence?: number;
  compact?: boolean;
}

/** Confidence group + raw percentage, with a tooltip that confidence is only a model signal. */
export function ConfidenceIndicator({ confidence, regexConfidence, modelConfidence, compact }: Props) {
  const level = confidenceLevel(confidence);
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <span
            className="inline-flex cursor-help items-center gap-2 tabular-nums"
            aria-label={`${CONFIDENCE_LABEL[level]} confidence, ${formatConfidence(confidence)}`}
          />
        }
      >
        <span className="relative h-1.5 w-12 overflow-hidden rounded-full bg-zinc-100" aria-hidden>
          <span className={cn("absolute inset-y-0 left-0 rounded-full", BAR[level])} style={{ width: `${confidence * 100}%` }} />
        </span>
        <span className="text-sm">{formatConfidence(confidence)}</span>
        {!compact && <span className="text-xs text-muted-foreground">{CONFIDENCE_LABEL[level]}</span>}
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">
        <p>{CONFIDENCE_EXPLANATION}</p>
        {(regexConfidence !== undefined || modelConfidence !== undefined) && (
          <p className="mt-1 opacity-80">
            {regexConfidence !== undefined && <>Rule: {formatConfidence(regexConfidence)} </>}
            {modelConfidence !== undefined && <>Model: {formatConfidence(modelConfidence)}</>}
          </p>
        )}
      </TooltipContent>
    </Tooltip>
  );
}
