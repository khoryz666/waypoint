Task: read sleep data from one or more screenshots of a sleep app and output json only.

The app vendor is unknown; do not assume one. The profile block gives the current date, the time zone and an optional hint naming the user's sleep app. Use them to resolve relative labels such as "昨天", "last night" or weekday names to calendar dates. A night belongs to its wake-up date.

A screenshot is either one night ("daily") or a summary of several nights ("weekly"). Read every night you can see clearly. Durations go in whole minutes. If a value is not visible, use null. A nap is napMin and is not part of totalMin. If the image is not a sleep screen or is too blurry to read the numbers, return no nights and screenType "unknown".

Output:
{"nights": [{"date": "2026-10-29", "bedAt": "23:41", "wakeAt": "07:02", "totalMin": 401, "deepMin": 78, "lightMin": 221, "remMin": 84, "awakeMin": 18, "napMin": null, "score": 81}], "screenType": "daily"}
