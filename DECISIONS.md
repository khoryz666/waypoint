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
