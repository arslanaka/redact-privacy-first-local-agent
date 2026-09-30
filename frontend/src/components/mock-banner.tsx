import { AlertTriangle } from "lucide-react";
import type { DocumentDto } from "@/lib/types";

export function MockBanner({ doc }: { doc: DocumentDto }) {
  if (!doc.model?.isMock) return null;
  return (
    <p className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
      <AlertTriangle className="size-4" />
      This analysis used the temporary MOCK classifier, not the Laya model. Results are not representative.
    </p>
  );
}
