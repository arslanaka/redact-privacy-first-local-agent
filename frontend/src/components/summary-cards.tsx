import { RiskBadge, categoryLabel } from "@/components/badges";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { PrivacySummary } from "@/lib/types";

function Stat({ label, value, hint }: { label: string; value: React.ReactNode; hint?: string }) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle className="text-sm font-normal text-muted-foreground">{label}</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-semibold tabular-nums">{value}</div>
        {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
      </CardContent>
    </Card>
  );
}

export function SummaryCards({ summary }: { summary: PrivacySummary }) {
  const categories = Object.entries(summary.categories).sort((a, b) => b[1] - a[1]);
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <Stat label="Potential PII" value={summary.totalDetections} hint="unique candidates after merging" />
      <Stat label="High confidence" value={summary.highConfidence} hint="≥ 90%, still needs a decision" />
      <Stat
        label="Needs review"
        value={summary.needsReview + summary.lowConfidence}
        hint={`${summary.needsReview} medium · ${summary.lowConfidence} low confidence`}
      />
      <Card size="sm">
        <CardHeader>
          <CardTitle className="text-sm font-normal text-muted-foreground">Categories</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-x-3 gap-y-1 text-sm">
          {categories.length === 0 && <span className="text-muted-foreground">None</span>}
          {categories.map(([type, n]) => (
            <span key={type} className="whitespace-nowrap">
              {categoryLabel(type)} <span className="tabular-nums text-muted-foreground">{n}</span>
            </span>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}

export function RiskPanel({ summary }: { summary: PrivacySummary }) {
  return (
    <Card>
      <CardContent className="flex flex-col gap-2 sm:flex-row sm:items-start sm:gap-6">
        <RiskBadge level={summary.riskLevel} className="h-6 px-3 text-sm" />
        <div className="space-y-1 text-sm">
          <p className="font-medium">{summary.recommendation}</p>
          <ul className="list-disc pl-5 text-muted-foreground">
            {summary.riskReasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </div>
      </CardContent>
    </Card>
  );
}
