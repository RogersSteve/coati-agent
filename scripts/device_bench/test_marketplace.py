"""Checks the Car Market ground truth and the answer scoring (python -m unittest test_marketplace)."""
import unittest

from marketplace import expected, score

MATCHES = {"CM-3417": 41900, "CM-4052": 44500, "CM-1586": 39950, "CM-8134": 40800, "CM-9046": 42600}


class ExpectedTest(unittest.TestCase):
    def test_answers_match_the_kotlin_test(self):
        self.assertEqual(MATCHES, expected("M1"))
        self.assertEqual(MATCHES, expected("M5"))
        self.assertEqual(5, expected("M2"))
        self.assertEqual({"id": "CM-1586", "price_usd": 39950}, expected("M3"))
        self.assertEqual({k: v for k, v in MATCHES.items() if k != "CM-1586"}, expected("M4"))


class ScoreTest(unittest.TestCase):
    def test_list_answer_in_any_format(self):
        s = score("M1", "Found 5: CM-3417 ($41,900), cm 4052 at $44500, CM–1586 $39,950, "
                        "CM-8134 - 40,800 USD, CM 9046 ($42,600).")
        self.assertTrue(s["correct"])
        self.assertEqual((1.0, 1.0, 5), (s["recall"], s["precision"], s["prices_right"]))

    def test_bare_ids(self):
        self.assertTrue(score("M1", "3417, 4052, 1586, 8134 and 9046")["correct"])

    def test_partial_list(self):
        s = score("M1", "CM-3417, CM-4052, CM-6375 (sold) and CM-2297")
        self.assertFalse(s["correct"])
        self.assertEqual((0.4, 0.5), (s["recall"], s["precision"]))
        self.assertEqual(["CM-2297", "CM-6375"], s["extra"])

    def test_empty_answer(self):
        s = score("M1", None)
        self.assertEqual((False, 0.0, 0.0), (s["correct"], s["recall"], s["precision"]))

    def test_count(self):
        for answer in ["5", "There are 5 matching listings.", "Five cars match.",
                       "I found 5 listings within 100 km under 50,000 miles from 2023: CM-3417 ($41,900) ..."]:
            self.assertTrue(score("M2", answer)["correct"], answer)
        for answer in ["4 listings", "There are 6 matching cars.", "Several E 300 cars", ""]:
            self.assertFalse(score("M2", answer)["correct"], answer)

    def test_cheapest(self):
        self.assertTrue(score("M3", "The cheapest is CM-1586 at $39,950.")["correct"])
        self.assertTrue(score("M3", "CM-1586, 39950 USD")["correct"])
        self.assertFalse(score("M3", "CM-2861 at $36,900")["correct"])
        self.assertFalse(score("M3", "CM-1586 at $41,900")["correct"])
        # Listing several IDs is not an answer to "the cheapest".
        self.assertFalse(score("M3", "CM-1586 ($39,950), CM-8134 ($40,800)")["correct"])


if __name__ == "__main__":
    unittest.main()
