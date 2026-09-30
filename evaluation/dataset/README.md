# Evaluation dataset

14 short, **entirely fictional** English documents (forms, emails, chat logs, letters, meeting notes,
an invoice, a project update) with hand-written ground truth. Values come from reserved or documentation
ranges (`example.*` domains, Ofcom drama phone numbers, published test card numbers, standard example
IBANs, the well-known void SSN `078-05-1120`).

## Annotation format

Ground truth is written inline so it cannot drift from the text:

```
Full name: [[PERSON|Katharina Vogel]]
```

`run_evaluation.py` strips the markup, keeping the character offsets of each labelled span.
`[[?TYPE|text]]` marks an *ignore region*: text that is arguably personal (for example an age), where a
detection is neither rewarded nor penalised. The current dataset does not use any.

## Labelling policy

Labelled (personal data a sanitised copy must not contain):

| Label | Examples |
|---|---|
| `PERSON` | full names, single first names referring to a person, names with a title (`Dr Patel`) |
| `EMAIL`, `PHONE` | personal or work contact details |
| `ADDRESS` | a postal address as one span (street, number, postcode, city) |
| `IBAN`, `CREDIT_CARD` | account and card numbers |
| `ID_NUMBER` | tax IDs, passport, NHS, SSN, driving licence numbers |
| `DATE_OF_BIRTH` | birth dates only |

**Deliberately not labelled** (decoys; flagging them counts as a false positive):

- organisations (`Amazon`, `Oracle`, `Blue Harbor Analytics Ltd`) and company addresses
- city and country names used as places or products (`Jordan pavilion`, `Georgia is expanding`, `Paris`)
- business dates and times (invoice dates, meeting times), amounts, ticket, order and invoice numbers

This policy is a choice, and it is strict on purpose. The Laya model is trained to flag *every* date and
city, so part of what the benchmark measures is how much ambiguity is left for the human reviewer.
`04-ambiguous-meeting.txt` and `11-project-update.txt` exist mainly to measure false positives.
