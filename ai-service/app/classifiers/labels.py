"""Mapping from Laya's native PII labels (pii/labels.json) to the application's PiiType.

Laya's `pii` flavour predicts 21 fine-grained labels plus "not pii". The application works with a
coarser, redaction-oriented taxonomy. Notable consequences of the mapping:

* given name / surname / title are separate model labels; adjacent ones are merged into one PERSON.
* street / building number / postal code are ADDRESS parts; adjacent parts are merged into one ADDRESS.
  A city next to address parts joins that ADDRESS; a standalone city becomes LOCATION.
* "date" is only DATE_OF_BIRTH when the text just before it says so (born, DOB, date of birth).
  Every other date is OTHER, because Laya flags *all* dates and most dates in a document
  (invoice dates, deadlines) are not personal data on their own.
* Laya has no IBAN or ORGANIZATION label. IBANs are covered by the backend's regex detector;
  organizations are not detected by this model at all.
"""
from __future__ import annotations

import re

from app.classifiers.base import PiiType

LAYA_TO_TYPE: dict[str, PiiType] = {
    "given name": PiiType.PERSON,
    "surname": PiiType.PERSON,
    "title": PiiType.PERSON,
    "gender": PiiType.OTHER,
    "sex": PiiType.OTHER,
    "age": PiiType.OTHER,
    "date": PiiType.OTHER,  # upgraded to DATE_OF_BIRTH by context, see resolve_type
    "email address": PiiType.EMAIL,
    "phone number": PiiType.PHONE,
    "social security number": PiiType.ID_NUMBER,
    "tax number": PiiType.ID_NUMBER,
    "id card number": PiiType.ID_NUMBER,
    "passport number": PiiType.ID_NUMBER,
    "driver licence number": PiiType.ID_NUMBER,
    "credit card number": PiiType.CREDIT_CARD,
    "street": PiiType.ADDRESS,
    "building number": PiiType.ADDRESS,
    "city": PiiType.LOCATION,
    "postal code": PiiType.ADDRESS,
    "time": PiiType.OTHER,
    "web address": PiiType.OTHER,
}

# A title on its own ("Dr", "Mrs") is not identifying; it only matters as part of a name.
STANDALONE_DROP: frozenset[str] = frozenset({"title"})

DOB_CUE = re.compile(r"(?:\bborn\b|\bbirth\b|\bd\.?o\.?b\.?|\bbirthday\b)[^\n]{0,20}$", re.IGNORECASE)
DOB_CONTEXT_CHARS = 40

# Gaps allowed between two pieces of the same entity.
NAME_GAP = re.compile(r"[ \t\-]*")
ADDRESS_GAP = re.compile(r"[ \t,\-/]*\n?[ \t]*")


def resolve_type(label: str, text: str, start: int) -> PiiType:
    """Map one native label to a PiiType, using the text before the span for dates."""
    mapped = LAYA_TO_TYPE.get(label, PiiType.OTHER)
    if label == "date":
        left = text[max(0, start - DOB_CONTEXT_CHARS):start]
        if DOB_CUE.search(left):
            return PiiType.DATE_OF_BIRTH
    return mapped


def can_join(left_type: PiiType, right_type: PiiType, gap: str) -> PiiType | None:
    """Type of the merged entity if two adjacent entities belong together, else None."""
    address_like = {PiiType.ADDRESS, PiiType.LOCATION}
    if (left_type in address_like and right_type in address_like
            and PiiType.ADDRESS in (left_type, right_type) and ADDRESS_GAP.fullmatch(gap)):
        return PiiType.ADDRESS
    # "Priya" + "Nair", "New" + "York", "+44" + "7700 900123": same type, only spaces/hyphens between.
    # Emails never join: two addresses separated by a space are two entities.
    if left_type == right_type and left_type != PiiType.EMAIL and NAME_GAP.fullmatch(gap):
        return left_type
    return None
