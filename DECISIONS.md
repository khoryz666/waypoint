# Decisions

Choices the spec left open, and every dependency added beyond the spec's allowlist (spec
section 1, "Rules for the implementing agents"). Newest last.

## Phase 0

- **Built in the `timer` repo on branch `feat/waypoint-v3`.** The spec reuses this repo's
  flake, direnv, CI and release workflows; all v2 sources were deleted rather than ported
  file by file. The timer service is re-implemented from v2's code in `timer/`.
- **New release keystore, not v2's.** v2's keystore was committed to the repo with its
  password, so it can't be the key Google OAuth trusts. v3 is a new package with a fresh
  install, so nothing depends on the old key. See `docs/signing.md`.
- **Unsigned release builds without secrets.** `assembleRelease` signs only when
  `WAYPOINT_KEYSTORE` (+ passwords) is set via environment or `~/.gradle/gradle.properties`.
  CI on push has no secrets and checks that the release build compiles; the tag workflow
  fails fast if the keystore secret is missing.
- **v2's self-updater from GitHub Releases is dropped.** The spec doesn't mention it and
  says to delete everything v2-specific. Updates are installed by downloading the APK.
- **JDK 17** in the flake and CI, as the spec says (v2 used 21).
- **Dependency: `androidx.room:room-testing`** (tests only). Part of Room; needed for the
  migration tests spec 10.3 requires (`MigrationTestHelper`).
- **Dependency: `kotlinx-coroutines-android`.** Part of the allowlisted "Coroutines + Flow";
  provides `Dispatchers.Main` on Android.
- **English fallback strings, `zh-CN` translation.** `res/values` is English and
  `res/values-zh-rCN` is Simplified Chinese.
- **Room schemas ship in debug assets.** Robolectric reads merged debug assets, not test
  assets, so `MigrationTest` can only find the exported schemas there. Release builds don't
  include them.
- **Lint: `CredManMissingDal` and `AppBundleLocaleChanges` disabled.** The backup passphrase
  is an app-only credential with no website to link; releases are APKs, not app bundles.

## Phases 1–4

### LLM
- **Default prices are DeepSeek's CNY peak rates** (checked 2026-10-30): `deepseek-flash`
  0.04 / 2 / 8, `deepseek-v4-pro` 0.30 / 9 / 27 per 1M tokens (hit / miss / output). Peak
  rates make the budget cap err on the safe side.
- **Thinking is explicitly disabled for Flash calls.** DeepSeek enables thinking by default;
  only reviews and "think harder" enable it, with a larger `max_tokens`.
- **Pro/thinking calls don't send `response_format`.** Its interaction with thinking mode is
  undocumented, so the prompt asks for JSON and the reply is decoded from its outermost
  `{...}`, with the same one-retry rule.
- **Cache layout:** the system message is `system_core.md` + the prompt file (identical per
  prompt type); the user message is profile → track → recent logs → input, as spec 8.3.
  Context blocks are templates too (`block_*.md`) so golden tests cover them.
- **Reading suggestions come from `review_week`** (a `suggestions` field), not a separate
  prompt, so they cost nothing extra and only appear in the weekly review.
- **Comment NEXT line:** `comment_log` ends with `NEXT: step | when` when the next step
  changes; it is hidden while streaming. Exercise logs take their next step from
  `exercise_next` instead.
- **Math photo checks** run inside the comment call: when a math log lists problems to check,
  its (already downscaled) photos are attached to `comment_log`.

### Input
- **Screenshots sent with the chip on Auto go to `parse_sleep_image`.** To log a fitness
  screenshot or math photos, pick the track first; they then go to `parse_input` with images.
- **Money shortcuts save immediately** (no confirm card) and show the receipt with Undo.
- **Balance entries get no LLM comment**, so entry notes never leave the device (spec 6.3).
- **Net change sums DELTA entries only.** A SET corrects drift and is never income or
  spending; reviews show the balance as start → end.
- **Units a log names that aren't in the outline are added and marked DONE.**
- **`minutes` in a log is informational.** Focus time comes only from timer sessions;
  retro-logged time becomes a session.
- **The timer tracks focus on a track only; there is no Joy timer** (user's call, departs from
  spec 5.5). The unreleased v1 schema was edited in place.
- **Money is not a track domain** (user's call, departs from spec 3). Like sleep, it lives on
  the Foundation tab, with one optional savings goal (amount, optional date) in the profile.
  The sleep goal is edited there too. "Fundamentals" is shown as "Basics" (基础知识, so it
  doesn't clash with the Foundation tab's 基础); the enum keeps its name.
- **Sleep:** a night's bed time from 12:00 onwards belongs to the evening before the wake
  date. The noon reminder checks for a record with today's wake date (last night).

### Learning and reviews
- **The weekly review replaces the spec 7.2 checklist** (user's call): no pillars, auto
  questions or Markdown import. DeepSeek writes 3-5 one-line questions from the active tracks
  the first time Review opens with a track, and again on "New questions"; without the API,
  one plain question per track. Questions can be edited. Answers are stored with the question
  text, so past reviews keep their wording. Onboarding drops the checklist step (7 steps).
- **The first revision is scheduled when a unit is marked DONE**, so a skipped recall check
  still gets revised. A grade then reschedules it.
- **Without the API (no key, 401/402, budget), revisions still work:** a plain "explain X
  from memory" question and self-grading buttons.
- **Streaks evaluate past days against today's active tracks.** Per-track targets for a
  track paused since then drop out of past days too.
- **The weekly review refreshes every active track's rolling summary** after submit (8.4).
- **Monthly/yearly reviews are on demand** from the Review tab; their reminders only notify.
- **Starter day types are Moderate, Deep and Light with no weekdays assigned** (the user picks);
  Light has nudges off. Targets are set in Settings once tracks exist.
- **Ownership checks are generated after a commit sync** (every 3 h and at app open), at most
  one per day.

### Backup
- **No Credential Manager sign-in step.** `AuthorizationClient` with `drive.appdata` picks the
  account itself and needs no web OAuth client ID, so `docs/google-setup.md` stays at the two
  Android clients. Credential Manager is used only for the passphrase.
- **The cached key is salt + derived key.** Routine backups reuse the salt; restoring caches
  the backup's key so the chain continues; "New passphrase" starts a new chain (older files
  then need the old passphrase).
- **Backup file names use UTC; retention groups days in the device zone.**

### UI and language
- **Compose screens use string resources; notification and receipt text uses `Lang`.**
  Receipts are built in pure `domain/` code, which can't read resources. Both pick the
  profile language.
- **The UI language is applied with a configuration override** (`ui/Locales.kt`), mirrored
  to SharedPreferences because `attachBaseContext` can't wait for DataStore.
- **Strings are generated** by `tools/strings.py` from one table, so English and Chinese
  always have the same keys.
- **Charts are hand-drawn Compose Canvas** (no chart dependency): single-series bars with a
  target band for sleep (a year shows weekly averages), a 2 px line for the balance; tap for
  values.

### Not verified on a real phone
Everything above passed unit tests and a walkthrough on an API 35 emulator (onboarding,
track creation, timer + notification, money shortcut, offline queue, Foundation, Review, Settings,
Chinese UI). The device checks in spec 10.2 — a real DeepSeek paste, real sleep screenshots,
Drive restore after reinstall, private-repo sync, a week of use — are still open.
