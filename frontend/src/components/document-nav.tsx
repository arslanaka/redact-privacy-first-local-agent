"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { DocumentDto } from "@/lib/types";
import { cn } from "@/lib/utils";

/** Workflow tabs for one document; later steps unlock as the document progresses. */
export function DocumentNav({ doc }: { doc: DocumentDto }) {
  const pathname = usePathname();
  const base = `/documents/${doc.id}`;
  const analysed = ["REVIEW_REQUIRED", "APPROVED", "REDACTING", "COMPLETED"].includes(doc.status);
  const items = [
    { href: base, label: "1. Results", enabled: analysed },
    { href: `${base}/review`, label: "2. Review", enabled: analysed },
    { href: `${base}/output`, label: "3. Redacted output", enabled: analysed },
    { href: `${base}/audit`, label: "Audit trail", enabled: true },
  ];
  return (
    <nav className="flex gap-1 border-b">
      {items.map((item) =>
        item.enabled ? (
          <Link
            key={item.href}
            href={item.href}
            className={cn(
              "-mb-px border-b-2 px-3 py-2 text-sm transition-colors",
              pathname === item.href
                ? "border-zinc-900 font-medium text-zinc-900"
                : "border-transparent text-muted-foreground hover:text-zinc-900",
            )}
          >
            {item.label}
          </Link>
        ) : (
          <span key={item.href} className="px-3 py-2 text-sm text-zinc-300">
            {item.label}
          </span>
        ),
      )}
    </nav>
  );
}

export function DocumentTitle({ doc }: { doc: DocumentDto }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-2">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{doc.originalFilename}</h1>
        <p className="text-sm text-muted-foreground">
          {doc.format} · {formatBytes(doc.size)} · uploaded {new Date(doc.createdAt).toLocaleString()} · auto-deleted{" "}
          {new Date(doc.expiresAt).toLocaleString()}
        </p>
      </div>
    </div>
  );
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
