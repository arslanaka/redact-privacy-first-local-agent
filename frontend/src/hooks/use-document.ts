"use client";

import { useParams } from "next/navigation";
import { usePolling } from "@/hooks/use-polling";
import { api } from "@/lib/api";
import { isProcessing } from "@/lib/stages";

export function useDocumentId(): string {
  return useParams<{ id: string }>().id;
}

/** Loads the document and keeps polling while an analysis is running. */
export function useDocument(id: string) {
  return usePolling(() => api.document(id), { shouldPoll: (d) => isProcessing(d.status), intervalMs: 800 });
}
