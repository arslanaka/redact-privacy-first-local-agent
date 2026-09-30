"use client";

import { FileText, Lock, Upload } from "lucide-react";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { ErrorAlert } from "@/components/error-alert";
import { CapacityCard } from "@/components/capacity-card";
import { formatBytes } from "@/components/document-nav";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useSystemStatus } from "@/hooks/use-system-status";
import { api, ApiError } from "@/lib/api";
import {
  CHARS_PER_PAGE,
  analysisCostUsd,
  estimateSeconds,
  formatChars,
  formatUsd,
  maxCharsPerDocument,
} from "@/lib/estimate";
import { formatDuration } from "@/lib/format";
import type { SystemStatus } from "@/lib/types";
import { validateFile } from "@/lib/upload";
import { cn } from "@/lib/utils";

const SAMPLES = [
  { file: "sample-customer-record.md", label: "Customer record", hint: "name, DOB, address, IBAN" },
  { file: "sample-support-ticket.txt", label: "Support ticket", hint: "card number, phones" },
  { file: "sample-ambiguous-notes.txt", label: "Ambiguous notes", hint: "“Jordan works with Amazon in Berlin”" },
  { file: "sample-employee-record.md", label: "Employee record", hint: "tax ID, passport" },
  { file: "sample-invoice.txt", label: "Invoice", hint: "mostly business data" },
  { file: "sample-medical-referral.pdf", label: "Medical referral (PDF)", hint: "PDF extraction + sanitised PDF" },
];

export default function UploadPage() {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const status = useSystemStatus();

  function choose(candidate: File | undefined | null) {
    if (!candidate) return;
    const problem = validateFile(candidate);
    setError(problem);
    setFile(problem ? null : candidate);
  }

  async function loadSample(name: string) {
    setError(null);
    const res = await fetch(`/samples/${name}`);
    if (!res.ok) {
      setError("Sample not found. Run `npm run dev` (it copies the samples) or pick a file.");
      return;
    }
    const blob = await res.blob();
    const type = name.endsWith(".pdf") ? "application/pdf" : name.endsWith(".md") ? "text/markdown" : "text/plain";
    choose(new File([blob], name, { type }));
  }

  async function analyse() {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const doc = await api.upload(file);
      await api.analyse(doc.id);
      router.push(`/documents/${doc.id}/processing`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Upload failed.");
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-2xl space-y-8 py-6">
      <div className="space-y-2 text-center">
        <h1 className="text-3xl font-semibold tracking-tight">Local Privacy Agent</h1>
        <p className="text-muted-foreground">Detect and review sensitive information before sharing documents.</p>
      </div>

      <Card>
        <CardContent className="space-y-5">
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              choose(e.dataTransfer.files[0]);
            }}
            className={cn(
              "flex w-full flex-col items-center gap-3 rounded-lg border-2 border-dashed px-6 py-10 text-center transition-colors",
              dragging ? "border-zinc-900 bg-zinc-50" : "border-zinc-200 hover:border-zinc-400",
            )}
          >
            {file ? <FileText className="size-8 text-zinc-700" /> : <Upload className="size-8 text-zinc-400" />}
            {file ? (
              <div>
                <p className="font-medium">{file.name}</p>
                <p className="text-sm text-muted-foreground">{formatBytes(file.size)} · click to choose another file</p>
              </div>
            ) : (
              <div>
                <p className="font-medium">Drop a document here or click to browse</p>
                <p className="text-sm text-muted-foreground">PDF, TXT or Markdown · up to 10 MB</p>
              </div>
            )}
          </button>
          <input
            ref={inputRef}
            type="file"
            accept=".pdf,.txt,.md,.markdown,application/pdf,text/plain,text/markdown"
            className="hidden"
            onChange={(e) => choose(e.target.files?.[0])}
            data-testid="file-input"
          />

          {file && <FileEstimate file={file} status={status} />}

          {error && <ErrorAlert title="Cannot analyse this file" message={error} />}

          <Button className="w-full" size="lg" disabled={!file || busy} onClick={analyse}>
            {busy ? "Uploading…" : "Analyse Document"}
          </Button>

          <p className="flex items-start gap-2 text-sm text-muted-foreground">
            <Lock className="mt-0.5 size-4 shrink-0" />
            Documents are processed by this application&apos;s own server and are not sent to external LLM APIs.
          </p>
        </CardContent>
      </Card>

      <div className="space-y-3">
        <h2 className="text-sm font-medium text-muted-foreground">Or try a synthetic sample (all data is fictional)</h2>
        <div className="grid gap-2 sm:grid-cols-2">
          {SAMPLES.map((s) => (
            <button
              key={s.file}
              type="button"
              onClick={() => void loadSample(s.file)}
              className="rounded-lg border bg-white px-4 py-3 text-left transition-colors hover:border-zinc-400"
            >
              <span className="block text-sm font-medium">{s.label}</span>
              <span className="block text-xs text-muted-foreground">{s.hint}</span>
            </button>
          ))}
        </div>
      </div>

      <CapacityCard status={status} />
    </div>
  );
}

/**
 * Sets expectations before the click. Plain text and Markdown are about one character per byte; a PDF's text
 * length is only known after extraction, so it gets a per-page figure instead.
 */
function FileEstimate({ file, status }: { file: File; status: SystemStatus | null }) {
  const capacity = status?.capacity;
  if (!status || !capacity?.spansPerSecond) return null;
  const isPdf = file.name.toLowerCase().endsWith(".pdf");
  if (isPdf) {
    const perPage = estimateSeconds(CHARS_PER_PAGE, capacity)!;
    return (
      <p className="text-sm text-muted-foreground" data-testid="file-estimate">
        Estimated analysis: about {formatDuration(perPage * 1000)} per page of text (
        {formatUsd(analysisCostUsd(perPage, capacity, status.cost))} per page on {status.cost?.host ?? "this server"}).
      </p>
    );
  }
  const seconds = estimateSeconds(file.size, capacity)!;
  const max = maxCharsPerDocument(capacity);
  const tooLong = max !== null && file.size > max;
  return (
    <p className={tooLong ? "text-sm text-amber-800" : "text-sm text-muted-foreground"} data-testid="file-estimate">
      {tooLong
        ? `This file (~${formatChars(file.size)} characters) is likely above this deployment's limit of ~${formatChars(max!)}. Split it into smaller parts.`
        : `Estimated analysis: ~${formatDuration(seconds * 1000)} · est. ${formatUsd(analysisCostUsd(seconds, capacity, status.cost))} on ${status.cost?.host ?? "this server"}.`}
    </p>
  );
}
