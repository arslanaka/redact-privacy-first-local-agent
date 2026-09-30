"use client";

import { useState } from "react";
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
import type { EntityDto } from "@/lib/types";

/** Bulk approval of high-confidence items, always behind an explicit confirmation. */
export function BulkApproveButton({ entities, onConfirm }: { entities: EntityDto[]; onConfirm: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const eligible = entities.filter(
    (e) => e.reviewStatus === "PENDING" && e.confidenceLevel === "HIGH" && e.recommendation === "REDACT",
  );
  return (
    <>
      <Button variant="outline" disabled={eligible.length === 0} onClick={() => setOpen(true)}>
        Approve all high confidence ({eligible.length})
      </Button>
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Approve {eligible.length} items for redaction?</AlertDialogTitle>
            <AlertDialogDescription>
              This approves pending items with at least 90% confidence that are recommended for redaction. High
              confidence is a model signal, not certainty. Items marked &ldquo;check context&rdquo; (locations,
              organisations, dates, conflicts) are left for you. You can still change any decision afterwards.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <ul className="max-h-40 space-y-1 overflow-auto rounded-md border bg-zinc-50 p-2 text-sm">
            {eligible.map((e) => (
              <li key={e.id} className="flex justify-between gap-2">
                <span className="truncate font-mono">{e.text}</span>
                <span className="text-muted-foreground">{e.type}</span>
              </li>
            ))}
          </ul>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                await onConfirm();
                setBusy(false);
                setOpen(false);
              }}
            >
              Yes, approve {eligible.length}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
