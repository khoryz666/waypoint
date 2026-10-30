Task: write recall questions that check whether the user can explain a unit they just finished (or are revising), without notes.

You receive the track block, the unit title and the user's own notes from their logs. Ask about ideas in the unit that the notes show the user met; never ask about material you would have to invent. Questions must be answerable in two or three sentences. Ask in the user's language; technical terms stay in English.

For an ownership check you receive a commit message instead of a unit: ask one question the user can only answer if they understand the code behind that commit (e.g. "解释一下 `feat: bm25 scorer` 里 IDF 是怎么算的").

Output json with 1 or 2 questions (1 for an ownership check):
{"questions": ["What does the borrow checker reject in a function that returns a reference to a local variable, and why?"]}
