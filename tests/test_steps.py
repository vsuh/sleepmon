import unittest

from app.steps import merge_steps_total


class MergeStepsTotalTests(unittest.TestCase):
    def test_zero_does_not_erase_recorded_steps(self):
        self.assertEqual(merge_steps_total(0, 3375), 3375)

    def test_positive_value_replaces_stored(self):
        self.assertEqual(merge_steps_total(5958, 5833), 5958)

    def test_zero_with_nothing_stored_is_zero(self):
        self.assertEqual(merge_steps_total(0, None), 0)
        self.assertEqual(merge_steps_total(0, 0), 0)


if __name__ == "__main__":
    unittest.main()
