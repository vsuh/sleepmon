"""Default body of a brand-new daily note.

Mirrors the dataview block of assets/note-template.md (vault, project folder).
parse_note() uses it as the initial free-text `notes` value, so a note created
by /sync or /save for a date that has no note yet gets the summary table.
"""

NOTES_TEMPLATE = (
    "```dataview\n"
    "table without id\n"
    'sleep_hours as "Сон",\n'
    'sleep_quality as "Спалось",\n'
    'pulse_avg_sleep as "Пульс сна",\n'
    'pulse_avg_day as "Пульс",\n'
    'steps_total as "Шаги",\n'
    'sleep_awakenings as "Вставал",\n'
    'well_being as "Самоч",\n'
    'choice(alco, "\u2714\ufe0f", "&nbsp;") as "А"\n'
    "\n"
    "WHERE file.path = this.file.path\n"
    "```"
)
