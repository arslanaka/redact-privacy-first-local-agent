export interface Span {
  start: number;
  end: number;
  id?: string;
}

export interface Segment<S extends Span = Span> {
  text: string;
  start: number;
  span?: S;
}

/**
 * Splits text into plain and highlighted segments. Spans are clipped to the text and processed in
 * order; a span overlapping an earlier one is trimmed so every character belongs to one segment.
 */
export function segmentText<S extends Span>(text: string, spans: S[]): Segment<S>[] {
  const sorted = [...spans]
    .map((s) => ({ ...s, start: Math.max(0, s.start), end: Math.min(text.length, s.end) }))
    .filter((s) => s.end > s.start)
    .sort((a, b) => a.start - b.start || b.end - a.end);
  const out: Segment<S>[] = [];
  let pos = 0;
  for (const s of sorted) {
    const start = Math.max(s.start, pos);
    if (start >= s.end) continue;
    if (start > pos) out.push({ text: text.slice(pos, start), start: pos });
    out.push({ text: text.slice(start, s.end), start, span: s as S });
    pos = s.end;
  }
  if (pos < text.length) out.push({ text: text.slice(pos), start: pos });
  return out;
}
