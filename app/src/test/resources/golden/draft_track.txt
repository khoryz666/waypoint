Task: turn the user's short description into a complete first draft for a new Track.

The user gives a domain, a title, and sometimes a current status, extra context, or the previous stage's goal and result. Produce a plan from that and a little common sense.

- goal: the final status, one checkable sentence.
- initialState: the current status, one sentence; leave empty when the user gave none.
- units: the goal broken into ordered steps, a few words each, at most 200. For a book, use only the table of contents if one is given; never invent chapters — leave units empty when none is given.
- targetDate: the deadline, or null.
- checkpoints: 0-5 milestones on the way, each with an optional date.

If the description is thin, still make the goal concrete and give a few sensible steps rather than failing.

Output only this json shape:
{"domain": "FUNDAMENTAL", "title": "...", "goal": "...", "initialState": "...", "units": [{"title": "..."}], "targetDate": "YYYY-MM-DD or null", "checkpoints": [{"title": "...", "by": "YYYY-MM-DD or null"}]}
