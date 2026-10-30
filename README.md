# Waypoint

A single-user Android app that turns every report (text, timer session, screenshot, git
commit) into a structured log, answers with an instant receipt plus a short DeepSeek
comment, and keeps one concrete next step per goal. It replaces the v2 `timer` app.

The full spec lives outside the repo; choices it leaves open are in [DECISIONS.md](DECISIONS.md).

## Develop

```sh
direnv allow          # or: nix develop
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The flake provides JDK 17 and the Android SDK (platform 35). `nix develop .#emulator` adds
the emulator and an x86_64 system image.

- Code layout follows the spec: `domain/` (pure Kotlin rules, JVM-tested), `data/`, `llm/`,
  `ingest/`, `timer/`, `backup/`, `github/`, `ui/`, `work/`. Prompts are in
  `app/src/main/assets/prompts/`; after changing one, refresh its golden file with
  `./gradlew testDebugUnitTest --tests '*PromptGolden*' -Dgolden.update=true`.
- UI strings live in `tools/strings_table.py`; run `python3 tools/strings.py` after editing it.
- Package `com.khoryz666.waypoint` (`.debug` suffix for debug builds), `minSdk 29`, `targetSdk 35`.
- Release signing: [docs/signing.md](docs/signing.md). Google Drive backup setup: [docs/google-setup.md](docs/google-setup.md).

## Release

Set `waypoint.version` in `gradle.properties`, add a matching `## [x.y.z]` section to
`CHANGELOG.md`, and push a `vx.y.z` tag. The `Release` workflow checks both, builds a signed
APK and publishes it as a GitHub Release.
