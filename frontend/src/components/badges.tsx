import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { DetectionSource, PiiType, Recommendation, ReviewStatus, RiskLevel } from "@/lib/types";

const CATEGORY_STYLE: Record<PiiType, string> = {
  PERSON: "bg-sky-50 text-sky-800 border-sky-200",
  EMAIL: "bg-violet-50 text-violet-800 border-violet-200",
  PHONE: "bg-indigo-50 text-indigo-800 border-indigo-200",
  ADDRESS: "bg-teal-50 text-teal-800 border-teal-200",
  IBAN: "bg-rose-50 text-rose-800 border-rose-200",
  CREDIT_CARD: "bg-rose-50 text-rose-800 border-rose-200",
  ID_NUMBER: "bg-orange-50 text-orange-800 border-orange-200",
  DATE_OF_BIRTH: "bg-amber-50 text-amber-800 border-amber-200",
  ORGANIZATION: "bg-slate-50 text-slate-700 border-slate-200",
  LOCATION: "bg-emerald-50 text-emerald-800 border-emerald-200",
  OTHER: "bg-zinc-50 text-zinc-700 border-zinc-200",
};

export function categoryLabel(type: string): string {
  return type
    .split("_")
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(" ")
    .replace("Iban", "IBAN")
    .replace("Id ", "ID ");
}

export function CategoryBadge({ type }: { type: PiiType }) {
  return (
    <Badge variant="outline" className={cn("font-medium", CATEGORY_STYLE[type])}>
      {categoryLabel(type)}
    </Badge>
  );
}

const SOURCE_STYLE: Record<DetectionSource, { label: string; className: string; title: string }> = {
  REGEX: { label: "Rule", className: "border-zinc-300 text-zinc-700", title: "Deterministic rule (regex + checksum)" },
  LAYA: { label: "Laya", className: "border-blue-300 text-blue-800", title: "Local Laya PII model" },
  HYBRID: {
    label: "Hybrid",
    className: "border-emerald-300 text-emerald-800",
    title: "Found by both a deterministic rule and the local model",
  },
  MANUAL: { label: "Manual", className: "border-purple-300 text-purple-800", title: "Added by a reviewer" },
};

export function SourceBadge({ source }: { source: DetectionSource }) {
  const s = SOURCE_STYLE[source];
  return (
    <Badge variant="outline" className={cn("bg-transparent", s.className)} title={s.title}>
      {s.label}
    </Badge>
  );
}

const REVIEW_STYLE: Record<ReviewStatus, { label: string; className: string }> = {
  PENDING: { label: "Pending", className: "bg-zinc-100 text-zinc-700" },
  APPROVED: { label: "Redact", className: "bg-zinc-900 text-white" },
  REJECTED: { label: "Keep", className: "bg-white text-zinc-600 border-zinc-300" },
};

export function ReviewStatusBadge({ status }: { status: ReviewStatus }) {
  const s = REVIEW_STYLE[status];
  return (
    <Badge variant="outline" className={s.className}>
      {s.label}
    </Badge>
  );
}

const RECOMMENDATION_LABEL: Record<Recommendation, string> = {
  REDACT: "Suggest redact",
  REVIEW: "Check context",
  LIKELY_KEEP: "Likely keep",
};

export function RecommendationBadge({ recommendation }: { recommendation: Recommendation }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        recommendation === "REDACT" && "border-zinc-300 text-zinc-800",
        recommendation === "REVIEW" && "border-amber-300 bg-amber-50 text-amber-800",
        recommendation === "LIKELY_KEEP" && "border-zinc-200 text-zinc-500",
      )}
    >
      {RECOMMENDATION_LABEL[recommendation]}
    </Badge>
  );
}

const RISK_STYLE: Record<RiskLevel, string> = {
  HIGH: "bg-red-50 text-red-800 border-red-200",
  MEDIUM: "bg-amber-50 text-amber-800 border-amber-200",
  LOW: "bg-emerald-50 text-emerald-800 border-emerald-200",
};

export function RiskBadge({ level, className }: { level: RiskLevel; className?: string }) {
  return (
    <Badge variant="outline" className={cn(RISK_STYLE[level], className)}>
      {level} risk
    </Badge>
  );
}
