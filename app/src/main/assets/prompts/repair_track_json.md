Task: the user pasted the end of a goal-setting conversation, but the app could not parse the track block. Recover the track as json.

Use only what the pasted text says. Never invent units, chapters or checkpoints that are not in the text. If the text has no outline, return an empty units list.

Output exactly this shape:
{"domain": "FUNDAMENTAL", "title": "...", "goal": "...", "initialState": "...", "units": [{"title": "..."}], "targetDate": "YYYY-MM-DD or null", "checkpoints": [{"title": "...", "by": "YYYY-MM-DD or null"}]}
