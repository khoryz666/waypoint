# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
v2 (`timer`) history is in git before the `feat/waypoint-v3` branch.

## [Unreleased]

Waypoint v3: a new app (package `com.khoryz666.waypoint`) that replaces the v2 timer.
No data carries over from v2.

### Added
- Tracks for exercise, money, projects, fundamentals, math and reading, created through
  a DeepSeek-app handoff (copy prompt → discuss → paste the block), with repair via the
  API and a manual form as fallbacks. Draft / active / paused / completed / aborted
  lifecycle, eight-active limit, "Start next level" and revision tracks.
- One input box for everything, plus a share target for screenshots and text. Entries are
  saved before parsing and queued offline; `+350` / `-42.50` / `=4210` update the balance
  without the LLM. An editable confirm card for each kind of log.
- Instant receipts computed on the phone, then a streamed DeepSeek comment and an updated
  next step. Recall checks with grading and spaced revision (3 / 10 / 30 days).
- Timer ported from v2: Focus on a track or Joy, a notification with the last three focus
  tracks, "做了什么?" after a session of 10+ minutes, retro-logging, idle nudges.
- Day types with minimum and plan targets, and a streak.
- Sleep from screenshots of any sleep app or by hand, with weekly status; balance chart
  and money goals; exercise next session and "今天做不了" fallbacks.
- GitHub commit sync, a daily commit summary and once-a-day ownership checks.
- Daily close, a weekly checklist (starter template or Markdown import) with auto-filled
  answers and a Pro-written review, plus monthly and yearly reviews and "Ask about this
  track".
- DeepSeek cost meter with a monthly budget cap, model picker and editable price table.
- Encrypted automatic backup to Google Drive's app folder, and restore at first launch.
- English and Simplified Chinese UI.
