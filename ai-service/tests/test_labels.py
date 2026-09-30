import pytest

from app.classifiers import laya_candidates as cand
from app.classifiers.base import PiiType
from app.classifiers.labels import LAYA_TO_TYPE, can_join, resolve_type


def test_every_native_label_is_mapped():
    native = [lbl for lbl in cand.LABELS if lbl != cand.NOT_PII]
    assert sorted(native) == sorted(LAYA_TO_TYPE)


@pytest.mark.parametrize("label,expected", [
    ("given name", PiiType.PERSON),
    ("surname", PiiType.PERSON),
    ("email address", PiiType.EMAIL),
    ("phone number", PiiType.PHONE),
    ("passport number", PiiType.ID_NUMBER),
    ("tax number", PiiType.ID_NUMBER),
    ("credit card number", PiiType.CREDIT_CARD),
    ("street", PiiType.ADDRESS),
    ("postal code", PiiType.ADDRESS),
    ("city", PiiType.LOCATION),
    ("web address", PiiType.OTHER),
])
def test_label_mapping(label, expected):
    assert resolve_type(label, "x", 0) == expected


def test_unknown_label_maps_to_other():
    assert resolve_type("something new", "x", 0) == PiiType.OTHER


def test_date_becomes_dob_only_with_birth_cue():
    text = "Date of birth: 12/03/1985. Invoice date: 01/02/2024."
    assert resolve_type("date", text, text.index("12/03")) == PiiType.DATE_OF_BIRTH
    assert resolve_type("date", text, text.index("01/02")) == PiiType.OTHER
    text2 = "She was born on 4 May 1990."
    assert resolve_type("date", text2, text2.index("4 May")) == PiiType.DATE_OF_BIRTH


def test_joins():
    assert can_join(PiiType.PERSON, PiiType.PERSON, " ") == PiiType.PERSON
    assert can_join(PiiType.PERSON, PiiType.PERSON, ", ") is None
    assert can_join(PiiType.ADDRESS, PiiType.LOCATION, ", ") == PiiType.ADDRESS
    assert can_join(PiiType.LOCATION, PiiType.LOCATION, ", ") is None
    assert can_join(PiiType.EMAIL, PiiType.EMAIL, " ") is None
