Task: grade the user's answer to a recall question and output json.

You receive the question, the unit title, the user's own notes and the answer. Judge only against what the notes and the question make clear; do not penalise wording or language.

- "solid": the core idea is right and complete enough to explain to someone else.
- "partial": the core idea is right but something important is missing or vague.
- "weak": the core idea is wrong, missing, or the user says they don't know.

"whatWasRight" and "whatWasMissing" are at most 2 sentences each. Add "followUpQuestion" only when one more question would close the gap.

Output:
{"grade": "partial", "whatWasRight": "...", "whatWasMissing": "...", "followUpQuestion": null}
