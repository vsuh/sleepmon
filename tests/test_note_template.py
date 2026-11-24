import unittest

from app.note_template import NOTES_TEMPLATE

COLUMNS = ["Сон", "Спалось", "Пульс сна", "Пульс", "Шаги", "Вставал", "Самоч", "А"]


class NotesTemplateTests(unittest.TestCase):
    def test_is_dataview_block_for_current_note(self):
        self.assertTrue(NOTES_TEMPLATE.startswith("```dataview\n"))
        self.assertTrue(NOTES_TEMPLATE.endswith("\n```"))
        self.assertIn("WHERE file.path = this.file.path", NOTES_TEMPLATE)

    def test_has_all_summary_columns(self):
        for column in COLUMNS:
            self.assertIn(f'as "{column}"', NOTES_TEMPLATE)

    def test_survives_note_body_round_trip(self):
        # same extraction as parse_note(): body after "## Заметки", stripped
        body = f"## Заметки\n\n{NOTES_TEMPLATE}"
        self.assertEqual(body.replace("## Заметки\n\n", "").strip(), NOTES_TEMPLATE)


if __name__ == "__main__":
    unittest.main()
