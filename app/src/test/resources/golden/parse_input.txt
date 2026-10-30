Task: parse one report from the user into json for the app. Output a single json object only.

You receive the profile block (current date, time zone, UI language), the list of ACTIVE tracks (id, domain, title, goal, and for each track its outline units as "id: title [status]"), and the report text. The track chip may already name a track; if so, use that trackId with confidence 1.0.

Routing:
- "trackId": the id of the ACTIVE track the report is about, or null for sleep and balance reports, or when nothing fits.
- "confidence": 0.0-1.0, how sure you are about trackId. Use below 0.7 when two tracks could fit.

The "log" object has a "type" field and the fields for that type:
- "fundamental" (books, courses, languages, problem lists): {"type":"fundamental","unitsDone":[unit ids],"unitsStarted":[unit ids],"newUnits":["titles mentioned but not in the outline"],"usedHint":true|false|null,"minutes":int|null,"notes":"what the user said they did or learned, in their words"}
- "math": {"type":"math","book":"","section":"","problemsAttempted":"1-15","attemptedCount":15,"solved":["1","2"],"stuck":["9","12"],"unitsDone":[],"unitsStarted":[unit ids],"checkProblems":["problems the user asks to check"],"minutes":null,"notes":""}
- "reading": {"type":"reading","pagesOrChapter":"","keyIdeas":["at most 5"],"ownWords":true if written in their own words,"interestSignals":["topics the user expanded on or asked about"],"unitsDone":[],"minutes":null,"notes":""}
- "project": {"type":"project","milestonesDone":[unit ids the report suggests are finished],"minutes":null,"notes":""}
- "exercise": {"type":"exercise","exercises":[{"name":"Goblet squat","sets":[{"reps":10,"weightKg":12,"seconds":null,"assisted":"none|band|negative"}]}],"swimMin":30,"strokes":"breaststroke","rpe":7,"pain":"text or null","notes":""}. "3x10 12kg" means three sets of 10 reps at 12 kg.
- "session" (retro-logged time, e.g. "I did 40m of X around 3pm"): {"type":"session","startAt":"YYYY-MM-DDTHH:mm","endAt":"YYYY-MM-DDTHH:mm","category":"FOCUS|JOY","notes":""}. Resolve relative times with the current date and time zone; "around 3pm" for 40m starts at 15:00.
- "sleep" (sleep described in text): {"type":"sleep","nights":[{"date":"wake date YYYY-MM-DD","bedAt":"HH:mm","wakeAt":"HH:mm","totalMin":400}],"screenType":"daily"}
- "balance" (money received or spent, in words): {"type":"balance","kind":"DELTA|SET","amount":"1200.00 as a decimal string, negative for spending","note":""}
- "note": anything else: {"type":"note","text":""}

Only use unit ids from the outline you were given. Leave fields you cannot fill as null or empty; never guess numbers.

Example output:
{"trackId": 3, "confidence": 0.92, "log": {"type": "fundamental", "unitsDone": [41], "unitsStarted": [], "newUnits": [], "usedHint": null, "minutes": null, "notes": "读完 ch8，做了 hashmap 练习"}}
