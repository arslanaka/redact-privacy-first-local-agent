"""Tests for the benchmark's loader and scoring (stdlib unittest): python3 -m unittest evaluation/test_run_evaluation.py"""
import tempfile
import unittest
from pathlib import Path

from run_evaluation import DATASET, Span, load_document, prf, score


class LoaderTest(unittest.TestCase):
    def test_strips_annotations_and_keeps_offsets(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.txt"
            p.write_text("Hi [[PERSON|Anna Keller]], mail [[EMAIL|a@b.io]] [[?OTHER|34]].", encoding="utf-8")
            text, spans = load_document(p)
        self.assertEqual(text, "Hi Anna Keller, mail a@b.io 34.")
        self.assertEqual([(s.type, text[s.start:s.end], s.ignore) for s in spans],
                         [("PERSON", "Anna Keller", False), ("EMAIL", "a@b.io", False), ("OTHER", "34", True)])

    def test_every_dataset_file_loads(self):
        files = sorted(DATASET.glob("*.txt"))
        self.assertGreaterEqual(len(files), 10)
        for f in files:
            text, _ = load_document(f)
            self.assertNotIn("[[", text, f.name)


class ScoreTest(unittest.TestCase):
    def test_overlap_matching_false_positives_and_ignore_regions(self):
        gold = [Span(0, 10, "PERSON", "x"), Span(20, 30, "EMAIL", "y"), Span(40, 45, "OTHER", "z", ignore=True)]
        preds = [Span(2, 8, "PERSON", "a"),      # partial overlap -> found
                 Span(20, 30, "PHONE", "b"),     # found, wrong type
                 Span(50, 55, "LOCATION", "c"),  # false positive
                 Span(41, 44, "OTHER", "d")]     # only touches ignore region -> not scored
        s = score(gold, preds, 60)
        self.assertEqual((s["tp"], s["fp"], s["fn"], s["predictions"]), (2, 1, 0, 3))
        self.assertEqual(s["typed_found"], 1)
        self.assertEqual(s["gold_chars"], 20)
        self.assertEqual(s["gold_chars_covered"], 16)

    def test_misses_are_false_negatives(self):
        s = score([Span(0, 5, "IBAN", "x")], [], 10)
        self.assertEqual((s["tp"], s["fn"]), (0, 1))

    def test_prf(self):
        self.assertEqual(prf(3, 4, 3, 6), (0.75, 0.5, 0.6))
        self.assertEqual(prf(0, 0, 0, 0), (1.0, 1.0, 1.0))


if __name__ == "__main__":
    unittest.main()
