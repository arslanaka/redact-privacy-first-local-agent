"use client";

import { useEffect, useState } from "react";
import { AuditTimeline } from "@/components/audit-timeline";
import { DocumentNav, DocumentTitle } from "@/components/document-nav";
import { ErrorAlert } from "@/components/error-alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useDocument, useDocumentId } from "@/hooks/use-document";
import { api, ApiError } from "@/lib/api";
import type { AuditEventDto } from "@/lib/types";

export default function AuditPage() {
  const id = useDocumentId();
  const { data: doc, error } = useDocument(id);
  const [events, setEvents] = useState<AuditEventDto[] | undefined>(undefined);
  const [auditError, setAuditError] = useState<string | undefined>(undefined);

  useEffect(() => {
    api.audit(id).then(setEvents, (e) => setAuditError(e instanceof ApiError ? e.message : "Could not load audit trail"));
  }, [id]);

  if (error) return <ErrorAlert message={error.message} />;
  if (!doc) return <p className="text-muted-foreground">Loading…</p>;

  return (
    <div className="space-y-6">
      <DocumentTitle doc={doc} />
      <DocumentNav doc={doc} />
      <Card>
        <CardHeader>
          <CardTitle>Audit trail</CardTitle>
          <CardDescription>
            Every workflow step and review decision, in order. Detected values are masked (e.g. &ldquo;Ma•••••
            (12 chars)&rdquo;) so the trail does not become another copy of the personal data.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {auditError && <ErrorAlert message={auditError} />}
          {events ? <AuditTimeline events={events} /> : <p className="text-muted-foreground">Loading…</p>}
        </CardContent>
      </Card>
    </div>
  );
}
