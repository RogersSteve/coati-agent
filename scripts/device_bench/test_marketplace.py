"""Checks the Car Market ground truth and the answer scoring (python -m unittest test_marketplace)."""
import unittest

from marketplace import REPEAT_TASKS, expected, parse_session, repeat_items, score, score_repeat

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


SESSION = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="repeat_mode" value="true" />
    <set name="favorites">
        <string>CM-2048</string>
        <string>CM-6610</string>
    </set>
    <set name="hidden">
        <string>CM-6375</string>
    </set>
    <int name="refreshes" value="6" />
</map>"""

FIRST_TEN = ["CM-2048", "CM-6610", "CM-5208", "CM-3305", "CM-3417", "CM-7472", "CM-1259", "CM-2861", "CM-8821",
             "CM-6943"]
SOLD = {"CM-6610", "CM-6375", "CM-2583", "CM-8457", "CM-9512", "CM-4904"}


class RepeatTest(unittest.TestCase):
    def state(self, **kw):
        base = {"favorites": set(), "compare": set(), "hidden": set(), "refreshes": 0}
        base.update(kw)
        return base

    def test_task_texts_and_items(self):
        self.assertEqual(["R1", "R2", "R3", "R4"], sorted(REPEAT_TASKS))
        self.assertIn("CM-3305, CM-8821, CM-5561, CM-7035, CM-6809, CM-1438", REPEAT_TASKS["R2"])
        self.assertIn("CM-9338 drops below $40,000", REPEAT_TASKS["R4"])
        self.assertEqual([10, 6, 6, 5], [repeat_items(t) for t in ("R1", "R2", "R3", "R4")])

    def test_session_parsing(self):
        state = parse_session(SESSION)
        self.assertEqual({"CM-2048", "CM-6610"}, state["favorites"])
        self.assertEqual(set(), state["compare"])
        self.assertEqual({"CM-6375"}, state["hidden"])
        self.assertEqual(6, state["refreshes"])
        self.assertEqual({"favorites": set(), "compare": set(), "hidden": set(), "refreshes": 0}, parse_session(""))

    def test_favorites_must_be_exactly_the_first_ten(self):
        self.assertTrue(score_repeat("R1", self.state(favorites=set(FIRST_TEN)))["correct"])
        partial = score_repeat("R1", self.state(favorites=set(FIRST_TEN[:7]) | {"CM-4716"}))
        self.assertEqual((False, 7, ["CM-4716"]), (partial["correct"], partial["done"], partial["extra"]))

    def test_compare_and_hide(self):
        compare = {"CM-3305", "CM-8821", "CM-5561", "CM-7035", "CM-6809", "CM-1438"}
        self.assertTrue(score_repeat("R2", self.state(compare=compare))["correct"])
        self.assertFalse(score_repeat("R2", self.state(compare=compare - {"CM-1438"}))["correct"])
        self.assertTrue(score_repeat("R3", self.state(hidden=SOLD))["correct"])
        # Hiding an unsold listing is a wrong result, not extra credit.
        self.assertFalse(score_repeat("R3", self.state(hidden=SOLD | {"CM-2048"}))["correct"])

    def test_monitoring_needs_the_refreshes_and_the_new_price(self):
        self.assertTrue(score_repeat("R4", self.state(refreshes=5), "CM-9338 is now $38,450")["correct"])
        self.assertTrue(score_repeat("R4", self.state(refreshes=7), "38450")["correct"])
        # Reporting the old price, or the new one without refreshing far enough, fails.
        self.assertFalse(score_repeat("R4", self.state(refreshes=5), "Still $40,100")["correct"])
        self.assertFalse(score_repeat("R4", self.state(refreshes=4), "$38,450")["correct"])


if __name__ == "__main__":
    unittest.main()
