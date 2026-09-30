import type { AuditEventDto } from "@/lib/types";
import { cn } from "@/lib/utils";

const TONE: Record<string, string> = {
  ERROR_OCCURRED: "bg-red-500",
  REDACTION_WARNING: "bg-amber-500",
  OUTPUT_INVALIDATED: "bg-amber-500",
  ENTITY_REJECTED: "bg-zinc-400",
  ENTITY_APPROVED: "bg-zinc-900",
  BULK_APPROVED: "bg-zinc-900",
  MANUAL_ENTITY_ADDED: "bg-purple-500",
  REDACTION_COMPLETED: "bg-emerald-500",
  EXPORT_CREATED: "bg-emerald-500",
};

export function AuditTimeline({ events, limit }: { events: AuditEventDto[]; limit?: number }) {
  const shown = limit ? events.slice(-limit) : events;
  if (shown.length === 0) {
    return <p className="text-sm text-muted-foreground">No audit events yet.</p>;
  }
  return (
    <ol className="relative space-y-3 border-l pl-5">
      {shown.map((e) => (
        <li key={e.id} className="relative">
          <span
            className={cn("absolute top-1.5 -left-[25px] size-2.5 rounded-full ring-4 ring-white", TONE[e.eventType] ?? "bg-zinc-300")}
          />
          <div className="flex flex-wrap items-baseline gap-x-3">
            <time className="font-mono text-xs text-muted-foreground tabular-nums" dateTime={e.createdAt}>
              {new Date(e.createdAt).toLocaleTimeString([], { hour12: false })}
            </time>
            <span className="text-sm">{e.message}</span>
            {e.actor && <span className="text-xs text-muted-foreground">by {e.actor}</span>}
          </div>
          <span className="text-[11px] tracking-wide text-muted-foreground uppercase">{e.eventType.replaceAll("_", " ")}</span>
        </li>
      ))}
    </ol>
  );
}
