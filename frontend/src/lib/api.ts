import type {
  AnalysisView,
  AuditEventDto,
  DocumentDto,
  EntityDto,
  PiiType,
  PreviewDto,
  ReviewStatus,
  SystemStatus,
} from "./types";

/** Same origin: the Next.js proxy checks the session and forwards /api/* to the backend on the private network. */
export const API_URL = "";

/** Human-readable messages for the backend's structured error codes. */
const FRIENDLY: Record<string, string> = {
  MODEL_UNAVAILABLE: "Local PII model is currently unavailable.",
  DOCUMENT_TOO_LARGE: "The file is larger than the 10 MB limit.",
  DOCUMENT_NOT_FOUND: "This document no longer exists. It may have been deleted or expired.",
  OUTPUT_NOT_AVAILABLE: "No redacted file is available yet. Generate the redacted document first.",
  ANALYSIS_FAILED: "The analysis failed. You can retry it.",
  REDACTION_FAILED: "The redacted document could not be generated. Your review decisions are unchanged.",
};

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly requestId?: string,
  ) {
    super(message);
  }
}

export function friendlyMessage(code: string, serverMessage?: string): string {
  return FRIENDLY[code] ?? serverMessage ?? "Something went wrong.";
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_URL}${path}`, init);
  } catch {
    throw new ApiError("NETWORK", "Cannot reach the server. Is it running?", 0);
  }
  if (res.status === 401 && typeof window !== "undefined") {
    // Session expired or signed out in another tab: back to sign-in, then return here.
    const next = window.location.pathname + window.location.search;
    // A full navigation (not the router) on purpose: this runs outside React and must drop all client state.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign(`/login?next=${encodeURIComponent(next)}`);
  }
  if (!res.ok) {
    let body: { error?: string; message?: string; requestId?: string } = {};
    try {
      body = await res.json();
    } catch {
      /* non-JSON error body */
    }
    const code = body.error ?? `HTTP_${res.status}`;
    throw new ApiError(code, friendlyMessage(code, body.message), res.status, body.requestId);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const api = {
  systemStatus: () => request<SystemStatus>("/api/system/status"),
  upload: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<DocumentDto>("/api/documents", { method: "POST", body: form });
  },
  document: (id: string) => request<DocumentDto>(`/api/documents/${id}`),
  analyse: (id: string) => request<DocumentDto>(`/api/documents/${id}/analyse`, { method: "POST" }),
  analysis: (id: string) => request<AnalysisView>(`/api/documents/${id}/analysis`),
  entities: (id: string) => request<EntityDto[]>(`/api/documents/${id}/entities`),
  updateEntity: (entityId: string, change: { reviewStatus?: ReviewStatus; type?: PiiType }) =>
    request<EntityDto>(`/api/entities/${entityId}`, json("PATCH", change)),
  approveHighConfidence: (id: string) =>
    request<{ approved: number; entityIds: string[] }>(
      `/api/documents/${id}/entities/approve-high-confidence`,
      json("POST", { confirm: true }),
    ),
  addManual: (
    id: string,
    body: { startOffset?: number; endOffset?: number; text?: string; type: PiiType; allOccurrences?: boolean },
  ) => request<EntityDto[]>(`/api/documents/${id}/entities/manual`, json("POST", body)),
  redact: (id: string) => request<DocumentDto>(`/api/documents/${id}/redact`, { method: "POST" }),
  preview: (id: string) => request<PreviewDto>(`/api/documents/${id}/preview`),
  audit: (id: string) => request<AuditEventDto[]>(`/api/documents/${id}/audit`),
  remove: (id: string) => request<Record<string, number>>(`/api/documents/${id}`, { method: "DELETE" }),
  downloadUrl: (id: string) => `${API_URL}/api/documents/${id}/download`,
};
