Task: the user pasted the end of a goal-setting conversation, but the app could not parse the track block. Recover the track as json.

Use only what the pasted text says. Never invent units, chapters or criteria that are not in the text. If the text has no outline, return an empty units list.

Output exactly this shape:
{"domain": "FUNDAMENTAL", "title": "...", "initialState": "...", "goal": "...", "goalCriteria": [{"label": "...", "metric": "...", "target": "..."}], "targetDate": "YYYY-MM-DD or null", "units": [{"title": "..."}], "planNotes": "..."}
