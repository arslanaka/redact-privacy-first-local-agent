# Vendored from goku-san/laya-experts, file demo/pii_demo.py (Apache-2.0).
# Source: https://huggingface.co/goku-san/laya-experts/blob/main/demo/pii_demo.py
#
# The Laya `pii` flavour is a span *classifier*: it only works on candidate spans proposed by the
# exact rule-based finder used during training, serialised in the exact state layout below. The
# model card says so explicitly, so this module is copied VERBATIM (candidate families, label set,
# document typing and state construction) rather than re-implemented. Do not "improve" it without
# re-validating the model: any change shifts the input distribution the model was trained on.
#
# Only the model-facing parts are vendored. Scoring, overlap selection and label mapping live in
# laya_classifier.py and labels.py. See ai-service/NOTICE for attribution.
"""Laya PII candidate finder and state builder (verbatim from laya-experts demo/pii_demo.py)."""
from __future__ import annotations

import collections
import re

REPO = "goku-san/laya-experts"
SUBFOLDER = "pii"
CONTEXT_CHARS = 400
INSTRUCTIONS = ("A candidate span of text was pulled out of a document, and is shown with the text either "
                "side of it. Which kind of personal data is the span itself, or is it not personal data at "
                "all? Judge the span, not the surrounding text.")

# --------------------------------------------------------------------------- taxonomy --- #
# The dataset's own 19 labels, not a collapsed set: ~20 options cost ~60 of Laya's 192 head
# tokens, so there is no budget reason to merge, and the native set stays comparable to the
# NER baselines published on this corpus. scripts/pii_report.py reports a derived collapsed
# view from the same predictions, which measures what the confusable distinctions cost.
LABEL_MAP: dict[str, str] = {
    "GIVENNAME": "given name",
    "SURNAME": "surname",
    "TITLE": "title",
    "GENDER": "gender",
    "SEX": "sex",
    "AGE": "age",
    "DATE": "date",
    "EMAIL": "email address",
    "TELEPHONENUM": "phone number",
    "SOCIALNUM": "social security number",
    "TAXNUM": "tax number",
    "IDCARDNUM": "id card number",
    "PASSPORTNUM": "passport number",
    "DRIVERLICENSENUM": "driver licence number",
    "CREDITCARDNUMBER": "credit card number",
    "STREET": "street",
    "BUILDINGNUM": "building number",
    "CITY": "city",
    "ZIPCODE": "postal code",
    "TIME": "time",
    "URL": "web address",
}
NOT_PII = "not pii"
LABELS = list(LABEL_MAP.values()) + [NOT_PII]


# ---------------------------------------------------------------- candidate detector --- #
# One regex family per named constant so --report-recall can attribute misses. Nested
# candidates are kept on purpose: pruning to maximal spans is cheaper but collapses
# "Dear Mr John Smith" into one span, which no single candidate can then label correctly.
MONTHS = ("January|February|March|April|May|June|July|August|September|October|November|December"
          "|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec")
SEX_WORDS = ("male|female|man|woman|boy|girl|other|M|F|O|X")
GENDER_WORDS = ("non-binary|nonbinary|two-spirit|twospirit|transgender|trans|intersex|agender|androgynous|"
                "bigender|genderqueer|genderfluid|cisgender|cis|not specified|polygender|pangender|neutrois|demiboy|demigirl")
TITLE_WORDS = ("mr|mrs|ms|miss|mx|mister|madame|madam|master|dr|doctor|prof|professor|sir|dame|lord|lady|"
               "rev|reverend|hon|honourable|mayor|mayoress|captain|capt|sgt|officer")
ADDRESS_WORDS = "Apt|Apt\\.|Apartment|Suite|Ste|Unit|Flat|Block|Blk|Floor|Level|Room|Rm|#"

FAMILIES: dict[str, str] = {
    "EMAIL": r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}",
    "DATE_ISO": r"\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?)?",
    "DATE_NUMERIC": r"\b\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}\b",
    "DATE_WORDY": rf"\b(?:\d{{1,2}}(?:st|nd|rd|th)?\s+)?(?:{MONTHS})\.?(?:\s+\d{{1,2}}(?:st|nd|rd|th)?)?(?:,?\s*\d{{4}})?\b",
    "DATE_WORDY_REV": rf"\b(?:{MONTHS})\.?\s+\d{{1,2}}(?:st|nd|rd|th)?(?:,?\s*\d{{4}})?\b",
    "TIME": r"\b\d{1,2}:\d{2}(?::\d{2})?(?:\s*[APap]\.?[Mm]\.?)?",
    "CARD": r"\b(?:\d{4}[ \-]?){3}\d{1,4}\b",
    "PHONE": r"(?:\+\d{1,3}[ .\-]?)?(?:\(\d{1,4}\)[ .\-]?)?\d{2,5}(?:[ .\-]\d{2,6}){1,4}",
    "PHONE_RUN": r"\+?\d[\d ().\-]{7,}\d",
    "ALNUM_ID": r"\b(?=[A-Za-z0-9\-]*\d)(?=[A-Za-z0-9\-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9\-]{4,}\b",
    "POSTCODE_UK": r"\b[A-Z]{1,2}\d{1,2}[A-Z]?[ ]?\d[A-Z]{2}\b",
    "POSTCODE_NUM": r"\b\d{4,6}(?:-\d{4})?\b",
    "BARE_INT": r"\b\d{1,15}\b",
    "SEX_GAZETTEER": rf"\b(?:{SEX_WORDS})\b",
    "GENDER_GAZETTEER": rf"\b(?:{GENDER_WORDS})\b",
    "ADDRESS_KEYWORD": rf"\b(?:{ADDRESS_WORDS})\s*[A-Za-z0-9\-]+\b",
    "AGE_PHRASE": r"\b\d{1,3}(?=[ \-]*(?:years?[ \-]old|yo\b|y/o\b))",
    "DATE_MONTH_SLASH": rf"\b(?:{MONTHS})[/\-]\d{{1,4}}\b",
    "SSN_DASH": r"\b\d{3}-\d{2}-\d{4}\b",
    "POSTCODE_CA": r"\b[A-Z]\d[A-Z](?:[ ]?\d[A-Z]\d)?\b",
    "TITLE_GAZETTEER": rf"\b(?:{TITLE_WORDS})\b\.?",
    "URLISH": r"\b(?:https?://|www\.)[^\s,;]+",
}
_CASE_INSENSITIVE = ("SEX_GAZETTEER", "GENDER_GAZETTEER", "TITLE_GAZETTEER", "URLISH")
COMPILED = {k: re.compile(v, re.IGNORECASE if k in _CASE_INSENSITIVE else 0) for k, v in FAMILIES.items()}

WORD_TOKEN = re.compile(r"\w[\w'’.\-]*", re.UNICODE)
CAPS_MAX_RUN = 5  # sub-runs of a capitalised run, so "Mr John Smith" yields title/given/surname
# Lowercase particles are part of the name they sit in ("van Leeuwen van Wyk", "al-Zobeidy"),
# so a capitalised run has to be allowed to run through them -- otherwise the particle is
# never proposed, and no model can redact it however good it is. Capitalisation is tested
# with str.isupper(), not [A-Z], so "\u00c7elebi" is a name too.
PARTICLES = {"van", "von", "de", "del", "della", "der", "den", "da", "di", "du", "dos", "das",
             "al", "el", "bin", "binti", "binte", "ibn", "bint", "la", "le", "ter", "ten",
             "op", "af", "av", "mac", "mc", "st", "y", "e", "of", "ap", "abu"}


def propose_spans(text: str, max_run: int = CAPS_MAX_RUN) -> dict[tuple[int, int], list[str]]:
    """Candidate character spans -> the detector families that proposed each."""
    out: dict[tuple[int, int], list[str]] = collections.defaultdict(list)
    for name, rx in COMPILED.items():
        for m in rx.finditer(text):
            s, e = m.span()
            while e > s and text[e - 1] in " .,;:-":
                e -= 1
            if e > s:
                out[(s, e)].append(name)
    # capitalised runs: every contiguous sub-run up to max_run tokens (names, cities, streets),
    # running through lowercase name particles but never starting or ending on one
    toks = []
    for m in WORD_TOKEN.finditer(text):
        w = m.group(0)
        kind = "cap" if w[:1].isupper() else ("particle" if w.lower().strip(".") in PARTICLES else None)
        if kind:
            toks.append((m.start(), m.end(), kind))
    runs: list[list[tuple[int, int, str]]] = []
    for tk in toks:
        if runs and text[runs[-1][-1][1]:tk[0]].strip() in ("", "-"):
            runs[-1].append(tk)
        else:
            runs.append([tk])
    for run in runs:
        if not any(t[2] == "cap" for t in run):
            continue
        for i in range(len(run)):
            if run[i][2] == "particle":
                continue
            for j in range(i, min(i + max_run, len(run))):
                if run[j][2] == "particle":
                    continue
                s, e = run[i][0], run[j][1]
                while e > s and text[e - 1] in ".,;:-":
                    e -= 1
                if e > s:
                    out[(s, e)].append("CAPS_RUN" if j > i else "CAPS_SINGLE")
    return dict(out)


# ------------------------------------------------------------------- document typing --- #
DOC_TYPES: list[tuple[str, str]] = [
    ("email", r"\b(?:subject:|dear |regards,|sincerely,|unsubscribe|inbox)\b"),
    ("medical record", r"\b(?:patient|diagnos|clinic|prescri|symptom|therap|dosage|medical record)\b"),
    ("support ticket", r"\b(?:ticket|case number|support|helpdesk|issue id|resolved by)\b"),
    ("legal document", r"\b(?:hereby|pursuant|agreement|clause|plaintiff|defendant|contract|liabilit)\b"),
    ("hr record", r"\b(?:employee|payroll|onboard|salary|performance review|hr department|hiring)\b"),
    ("financial record", r"\b(?:invoice|payment|account balance|transaction|billing|statement|loan)\b"),
    ("form or application", r"\b(?:application|form|please fill|registration|submit the|applicant)\b"),
    ("message thread", r"\b(?:posted|reply|thread|forum|comment|@\w+|chat)\b"),
]
DOC_TYPE_RX = [(name, re.compile(rx, re.IGNORECASE)) for name, rx in DOC_TYPES]


def doc_type(text: str) -> str:
    """Label-blind register guess. Keyword-only and ablatable (--no-doc-type): if it ever
    carries template identity rather than register, dropping it should cost nothing."""
    best, best_n = "unknown", 0
    for name, rx in DOC_TYPE_RX:
        n = len(rx.findall(text))
        if n > best_n:
            best, best_n = name, n
    return best


# ------------------------------------------------------------------------- inference --- #
JOIN_GAP = re.compile(r"[ \t\-/.]*")


def build_states(text: str, region: str = "US") -> list[tuple[tuple[int, int], list[str], dict]]:
    """One model input per candidate span, in the exact key order the model was trained on."""
    dt = doc_type(text)
    rows = []
    for (s, e), fams in sorted(propose_spans(text).items()):
        rows.append(((s, e), fams, {
            "document_type": dt,
            "language": "English",
            "region": region,
            "left": text[max(0, s - CONTEXT_CHARS):s],
            "span": text[s:e],
            "right": text[e:e + CONTEXT_CHARS],
        }))
    return rows
