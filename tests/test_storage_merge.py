import unittest

import yaml

from app.storage import _merge_obsidian_owned_fields


class MergeObsidianOwnedFieldsTests(unittest.TestCase):
    def metadata(self, content):
        _, raw, _ = content.split("---", 2)
        return yaml.safe_load(raw) or {}

    def test_missing_form_fields_are_restored_from_sqlite(self):
        sqlite_note = (
            "---\n"
            "project: sleepmon\n"
            "created: 2026-10-17\n"
            "sleep_hours: 8.5\n"
            "alco: false\n"
            "well_being: 9\n"
            "sleep_quality: 8\n"
            "---\n\n## Заметки\n\nТекст из Obsidian\n"
        )
        obsidian_note = (
            "---\n"
            "project: sleepmon\n"
            "created: 2026-10-17\n"
            "sleep_hours: 8.5\n"
            "---\n\n## Заметки\n\nТекст из Obsidian\n"
        )

        merged = _merge_obsidian_owned_fields(sqlite_note, obsidian_note)
        metadata = self.metadata(merged)

        self.assertIs(metadata["alco"], False)
        self.assertEqual(metadata["well_being"], 9)
        self.assertEqual(metadata["sleep_quality"], 8)
        self.assertIn("Текст из Obsidian", merged)

    def test_present_form_fields_in_obsidian_override_sqlite(self):
        sqlite_note = (
            "---\n"
            "alco: false\n"
            "well_being: 4\n"
            "sleep_quality: 5\n"
            "---\n\nSQLite body\n"
        )
        obsidian_note = (
            "---\n"
            "alco: true\n"
            "well_being: 8\n"
            "sleep_quality: 9\n"
            "---\n\nObsidian body\n"
        )

        merged = _merge_obsidian_owned_fields(sqlite_note, obsidian_note)
        metadata = self.metadata(merged)

        self.assertIs(metadata["alco"], True)
        self.assertEqual(metadata["well_being"], 8)
        self.assertEqual(metadata["sleep_quality"], 9)
        self.assertIn("Obsidian body", merged)
        self.assertNotIn("SQLite body", merged)


if __name__ == "__main__":
    unittest.main()
