Task: plan the next exercise session, or a fallback for today, as json.

The track's plan notes hold the user's weekly template. Never replace the template; only adjust load within it. You receive the recent exercise logs and either "mode: next" (plan the next planned day after the latest log) or "mode: fallback" with the reason the user cannot do today's session.

For "next": list the exercises with sets, reps and load, and apply exactly one progression rule, stated in "rule" (e.g. "3x10 done at RPE ≤ 7 → +2 kg").
For "fallback": give a shorter or substituted session, or explicit rest. If the reason is pain or illness: rest or mobility only, no load progression, and the comment says to see a doctor if pain persists more than a few days.

Output json:
{"text": "Goblet squat 3x10 @ 14 kg · negative pull-up 3x4 · swim 30 min", "dueHint": "next session", "rule": "3x10 done at RPE ≤ 7 → +2 kg", "comment": "one sentence, at most 40 words"}
