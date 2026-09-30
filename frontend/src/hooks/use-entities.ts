"use client";

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import type { EntityDto, PiiType, ReviewStatus } from "@/lib/types";

export type ManualRedactionBody = Parameters<typeof api.addManual>[1];

/** Entities of one document plus the review actions. Server responses are the source of truth. */
export function useEntities(documentId: string) {
  const [entities, setEntities] = useState<EntityDto[] | undefined>(undefined);
  const [error, setError] = useState<string | undefined>(undefined);

  const reload = useCallback(async () => {
    try {
      setEntities(await api.entities(documentId));
      setError(undefined);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load detections");
    }
  }, [documentId]);

  useEffect(() => {
    let cancelled = false;
    api.entities(documentId).then(
      (list) => !cancelled && setEntities(list),
      (e) => !cancelled && setError(e instanceof ApiError ? e.message : "Could not load detections"),
    );
    return () => {
      cancelled = true;
    };
  }, [documentId]);

  const update = useCallback(
    async (entityId: string, change: { reviewStatus?: ReviewStatus; type?: PiiType }) => {
      try {
        const updated = await api.updateEntity(entityId, change);
        // keep the context fields, which PATCH responses do not include
        setEntities((prev) => prev?.map((e) => (e.id === entityId ? { ...e, ...updated, contextBefore: e.contextBefore, contextAfter: e.contextAfter } : e)));
        return true;
      } catch (e) {
        toast.error(e instanceof ApiError ? e.message : "Update failed");
        return false;
      }
    },
    [],
  );

  const approveHighConfidence = useCallback(async () => {
    try {
      const res = await api.approveHighConfidence(documentId);
      toast.success(`${res.approved} high-confidence item(s) approved`);
      await reload();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Bulk approval failed");
    }
  }, [documentId, reload]);

  const addManual = useCallback(
    async (body: ManualRedactionBody) => {
      try {
        const created = await api.addManual(documentId, body);
        toast.success(`${created.length} manual redaction(s) added`);
        await reload();
        return created;
      } catch (e) {
        toast.error(e instanceof ApiError ? e.message : "Could not add redaction");
        return [];
      }
    },
    [documentId, reload],
  );

  return { entities, error, reload, update, approveHighConfidence, addManual };
}

export function reviewProgress(entities: EntityDto[] | undefined) {
  const total = entities?.length ?? 0;
  const reviewed = entities?.filter((e) => e.reviewStatus !== "PENDING").length ?? 0;
  return { total, reviewed, pending: total - reviewed };
}
