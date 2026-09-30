#!/usr/bin/env python3
"""Benchmark: Regex only vs Laya only vs Hybrid, on the synthetic, hand-labelled dataset.

Runs against a live backend (docker compose up) through POST /api/evaluation/detect, so every number
comes from the production detectors and merger. Standard library only.

    python3 evaluation/run_evaluation.py [--backend http://localhost:8080]

Writes evaluation/results.json and evaluation/REPORT.md. Nothing here is estimated or hard-coded.
"""
from __future__ import annotations

import argparse
import json
import platform
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATASET = HERE / "dataset"
METHODS = ["REGEX", "MODEL", "HYBRID"]
METHOD_LABEL = {"REGEX": "Regex only", "MODEL": "Laya only", "HYBRID": "Hybrid (regex + Laya)"}
ANNOTATION = re.compile(r"\[\[(\??)([A-Z_]+)\|(.+?)\]\]", re.DOTALL)


@dataclass(frozen=True)
class Span:
    start: int
    end: int
    type: str
    text: str
    ignore: bool = False

    def overlaps(self, other: "Span") -> bool:
        return self.start < other.end and other.start < self.end


def load_document(path: Path) -> tuple[str, list[Span]]:
    """Strips [[TYPE|text]] annotations and returns (plain text, gold spans). [[?TYPE|text]] = ignore region."""
    raw = path.read_text(encoding="utf-8")
    out, spans, pos = [], [], 0
    for m in ANNOTATION.finditer(raw):
        out.append(raw[pos:m.start()])
        start = sum(len(p) for p in out)
        value = m.group(3)
        out.append(value)
        spans.append(Span(start, start + len(value), m.group(2), value, ignore=m.group(1) == "?"))
        pos = m.end()
    out.append(raw[pos:])
    text = "".join(out)
    for s in spans:
        assert text[s.start:s.end] == s.text, f"{path.name}: offset bug for {s.text!r}"
    return text, spans


def detect(backend: str, text: str, method: str) -> dict:
    body = json.dumps({"text": text, "method": method}).encode()
    req = urllib.request.Request(f"{backend}/api/evaluation/detect", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=900) as res:
        payload = json.load(res)
    payload["wallMs"] = round((time.perf_counter() - started) * 1000)
    return payload


def score(gold: list[Span], preds: list[Span], text_len: int) -> dict:
    """Entity metrics in two views plus character coverage. See METHODOLOGY in the report."""
    real_gold = [g for g in gold if not g.ignore]
    ignore = [g for g in gold if g.ignore]
    # A prediction that only touches an ignore region is neither right nor wrong.
    scored_preds = [p for p in preds if not (any(p.overlaps(i) for i in ignore)
                                             and not any(p.overlaps(g) for g in real_gold))]
    tp_pred = [p for p in scored_preds if any(p.overlaps(g) for g in real_gold)]
    fp = [p for p in scored_preds if p not in tp_pred]
    found = [g for g in real_gold if any(g.overlaps(p) for p in preds)]
    fn = [g for g in real_gold if g not in found]
    typed_found = [g for g in real_gold if any(g.overlaps(p) and p.type == g.type for p in preds)]
    typed_tp_pred = [p for p in scored_preds if any(p.overlaps(g) and p.type == g.type for g in real_gold)]

    covered = [False] * text_len
    for p in preds:
        for i in range(p.start, p.end):
            covered[i] = True
    gold_chars = {i for g in real_gold for i in range(g.start, g.end)}
    pred_chars = {i for i, c in enumerate(covered) if c}
    ignore_chars = {i for g in ignore for i in range(g.start, g.end)}
    return {
        "tp": len(found), "fp": len(fp), "fn": len(fn),
        "predictions": len(scored_preds), "gold": len(real_gold),
        "tp_pred": len(tp_pred), "typed_found": len(typed_found), "typed_tp_pred": len(typed_tp_pred),
        "gold_chars": len(gold_chars), "gold_chars_covered": len(gold_chars & pred_chars),
        "extra_chars": len(pred_chars - gold_chars - ignore_chars),
        "fp_examples": [{"text": p.text, "type": p.type} for p in fp],
        "fn_examples": [{"text": g.text, "type": g.type} for g in fn],
        "found_types": [g.type for g in found], "gold_types": [g.type for g in real_gold],
    }


def prf(tp_pred: int, predictions: int, found: int, gold: int) -> tuple[float, float, float]:
    p = tp_pred / predictions if predictions else 1.0
    r = found / gold if gold else 1.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f


def aggregate(per_doc: list[dict], method: str) -> dict:
    rows = [d["methods"][method] for d in per_doc]
    s = lambda k: sum(r["score"][k] for r in rows)  # noqa: E731
    p, r, f = prf(s("tp_pred"), s("predictions"), s("tp"), s("gold"))
    tp_, tr, tf = prf(s("typed_tp_pred"), s("predictions"), s("typed_found"), s("gold"))
    latencies = [r["durationMs"] for r in rows]
    per_type: dict[str, dict[str, int]] = {}
    for row in rows:
        for t in row["score"]["gold_types"]:
            per_type.setdefault(t, {"gold": 0, "found": 0})["gold"] += 1
        for t in row["score"]["found_types"]:
            per_type[t]["found"] += 1
    return {
        "precision": round(p, 4), "recall": round(r, 4), "f1": round(f, 4),
        "typed_precision": round(tp_, 4), "typed_recall": round(tr, 4), "typed_f1": round(tf, 4),
        "true_positives": s("tp"), "false_positives": s("fp"), "false_negatives": s("fn"),
        "predictions": s("predictions"), "gold_entities": s("gold"),
        "pii_char_coverage": round(s("gold_chars_covered") / s("gold_chars"), 4) if s("gold_chars") else 1.0,
        "extra_chars_redacted": s("extra_chars"),
        "avg_latency_ms": round(statistics.mean(latencies), 1),
        "p50_latency_ms": round(statistics.median(latencies), 1),
        "max_latency_ms": max(latencies),
        "per_type_recall": {t: {**v, "recall": round(v["found"] / v["gold"], 4)} for t, v in sorted(per_type.items())},
        "false_positive_examples": [dict(e, doc=d["document"]) for d in per_doc
                                    for e in d["methods"][method]["score"]["fp_examples"]],
        "false_negative_examples": [dict(e, doc=d["document"]) for d in per_doc
                                    for e in d["methods"][method]["score"]["fn_examples"]],
    }


def pct(x: float) -> str:
    return f"{x * 100:.1f}%"


def write_report(results: dict, path: Path) -> None:
    s = results["summary"]
    lines = [
        "# Evaluation report",
        "",
        f"_Generated by `evaluation/run_evaluation.py` on {results['generatedAt']}. Do not edit by hand; re-run the script._",
        "",
        "## Setup",
        "",
        f"- Dataset: {results['dataset']['documents']} synthetic documents, {results['dataset']['goldEntities']} labelled "
        f"personal-data entities, {results['dataset']['characters']:,} characters (see `evaluation/dataset/README.md`).",
        f"- Model: `{results['model']['name']}` (mock: {results['model']['isMock']}), served by the AI service "
        f"at {results['environment']['backend']}.",
        f"- Host: {results['environment']['host']}. Latency is the server-side detection time per document.",
        "- Every document is sent through `POST /api/evaluation/detect`, i.e. the production regex detector, "
        "Laya adapter and DetectionMerger.",
        "",
        "## Results",
        "",
        "Entity-level, **redaction view**: a prediction is correct if it overlaps a labelled entity, whatever its category. "
        "This is what matters for leaking or not leaking data.",
        "",
        "| Method | Precision | Recall | F1 | False positives | False negatives | PII chars covered | Avg latency |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for m in METHODS:
        a = s[m]
        lines.append(f"| {METHOD_LABEL[m]} | {pct(a['precision'])} | {pct(a['recall'])} | {a['f1']:.3f} | "
                     f"{a['false_positives']} | {a['false_negatives']} | {pct(a['pii_char_coverage'])} | "
                     f"{a['avg_latency_ms']:.0f} ms |")
    lines += [
        "",
        "**Typed view**: a prediction only counts if it overlaps a labelled entity *and* has the same category.",
        "",
        "| Method | Precision | Recall | F1 |",
        "|---|---:|---:|---:|",
    ]
    for m in METHODS:
        a = s[m]
        lines.append(f"| {METHOD_LABEL[m]} | {pct(a['typed_precision'])} | {pct(a['typed_recall'])} | {a['typed_f1']:.3f} |")

    all_types = sorted({t for m in METHODS for t in s[m]["per_type_recall"]})
    lines += ["", "### Recall by category", "", "| Category | Gold | " + " | ".join(METHOD_LABEL[m] for m in METHODS) + " |",
              "|---|---:|" + "---:|" * len(METHODS)]
    for t in all_types:
        gold = s["HYBRID"]["per_type_recall"].get(t, {}).get("gold", 0)
        cells = []
        for m in METHODS:
            v = s[m]["per_type_recall"].get(t)
            cells.append(f"{v['found']}/{v['gold']}" if v else "-")
        lines.append(f"| {t} | {gold} | " + " | ".join(cells) + " |")

    for m in ["MODEL", "HYBRID"]:
        fps = s[m]["false_positive_examples"]
        fns = s[m]["false_negative_examples"]
        lines += ["", f"### {METHOD_LABEL[m]}: errors", "",
                  f"False positives ({len(fps)}), i.e. flagged text the ground truth does not treat as personal data:", ""]
        lines += [f"- `{e['text']}` as {e['type']} ({e['doc']})" for e in fps] or ["- none"]
        lines += ["", f"False negatives ({len(fns)}), i.e. labelled personal data no prediction touched:", ""]
        lines += [f"- `{e['text']}` ({e['type']}, {e['doc']})" for e in fns] or ["- none"]

    lines += [
        "",
        "## Methodology",
        "",
        "- **Ground truth** is written inline in each dataset file as `[[TYPE|text]]`. Only personal data is labelled: "
        "names, emails, phones, postal addresses, IBANs, card numbers, government IDs and dates of birth. "
        "Organisations, standalone city/country names, business dates, times, amounts and ticket numbers are "
        "deliberately *not* labelled, so flagging them counts as a false positive (they are the ambiguity a "
        "reviewer has to resolve).",
        "- **Matching** is overlap-based: a gold entity is found if any prediction overlaps it; a prediction is a true "
        "positive if it overlaps any gold entity. Precision = true-positive predictions / predictions; "
        "recall = found gold entities / gold entities.",
        "- **PII chars covered** = share of labelled personal-data characters inside some predicted span "
        "(1 - this is the fraction that would leak if every prediction were approved and nothing else redacted).",
        "- **Latency** = `durationMs` reported by the backend for detection only (Hybrid includes both detectors "
        "and the merge). It excludes HTTP and queueing time.",
        "- Small, synthetic, English-only dataset: treat the numbers as a sanity check of the pipeline, "
        "not as a benchmark of real-world performance.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--out", default=str(HERE))
    args = ap.parse_args()

    try:
        status = json.load(urllib.request.urlopen(f"{args.backend}/api/system/status", timeout=10))
    except (urllib.error.URLError, OSError) as e:
        print(f"Backend not reachable at {args.backend}: {e}. Start it with `docker compose up`.", file=sys.stderr)
        return 1
    if status.get("aiService") != "ready":
        print(f"Local model not ready (status={status.get('aiService')}). Wait for it and retry.", file=sys.stderr)
        return 1
    if status.get("modelIsMock"):
        print("Refusing to benchmark the MOCK classifier. Set PII_CLASSIFIER=laya.", file=sys.stderr)
        return 1

    files = sorted(DATASET.glob("*.txt"))
    per_doc = []
    for f in files:
        text, gold = load_document(f)
        entry = {"document": f.name, "characters": len(text), "gold": len([g for g in gold if not g.ignore]),
                 "methods": {}}
        for method in METHODS:
            res = detect(args.backend, text, method)
            preds = [Span(e["start"], e["end"], e["type"], e["text"]) for e in res["entities"]]
            entry["methods"][method] = {"durationMs": res["durationMs"], "wallMs": res["wallMs"],
                                        "entities": res["entities"], "score": score(gold, preds, len(text))}
            print(f"{f.name:32} {method:6} {len(preds):3} predictions  {res['durationMs']:>6} ms", flush=True)
        per_doc.append(entry)

    results = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "environment": {"backend": args.backend, "host": f"{platform.system()} {platform.machine()}",
                        "python": platform.python_version()},
        "model": {"name": status.get("model"), "isMock": status.get("modelIsMock")},
        "dataset": {"documents": len(per_doc), "goldEntities": sum(d["gold"] for d in per_doc),
                    "characters": sum(d["characters"] for d in per_doc)},
        "summary": {m: aggregate(per_doc, m) for m in METHODS},
        "documents": per_doc,
    }
    out = Path(args.out)
    (out / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    write_report(results, out / "REPORT.md")
    print()
    for m in METHODS:
        a = results["summary"][m]
        print(f"{METHOD_LABEL[m]:24} P={pct(a['precision'])} R={pct(a['recall'])} F1={a['f1']:.3f} "
              f"FP={a['false_positives']} FN={a['false_negatives']} avg={a['avg_latency_ms']:.0f}ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
