import unittest

from app.date_labels import format_date_label


class FormatDateLabelTests(unittest.TestCase):
    def test_weekday_day_month(self):
        self.assertEqual(format_date_label("2026-11-24"), "вт. 24 ноя")

    def test_day_is_zero_padded(self):
        self.assertEqual(format_date_label("2026-03-02"), "пн. 02 мар")

    def test_weekend_and_may(self):
        self.assertEqual(format_date_label("2026-05-09"), "сб. 09 мая")
        self.assertEqual(format_date_label("2026-11-22"), "вс. 22 ноя")

    def test_invalid_value_is_returned_unchanged(self):
        self.assertEqual(format_date_label("not-a-date"), "not-a-date")
        self.assertEqual(format_date_label(""), "")


if __name__ == "__main__":
    unittest.main()
