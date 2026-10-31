# Waypoint

## What it is for

Waypoint helps you get a little better every week at the things you choose.

You tell it what you did. It keeps the record and does the counting. It tells you how you are doing against your own past, not against other people. It always gives you one small next step for each goal.

It is an Android app for one person. Your data stays on your phone. It can also go into an encrypted backup in your own Google Drive. It uses DeepSeek to read what you write and to reply in a few words. It still works without DeepSeek. You just type a little more by hand.

## Before you start: one-time setup

Do these once, in this order, before you rely on the app. Steps 1 and 2 need your own passwords and Google account, so nobody can do them for you.

1. **Make your release key.** Every Android app is signed with a private key, like a wax seal. Android only installs an update if the seal matches, and Google uses it to recognise the app. Make the key once and keep a copy in your password manager. If you lose it, you cannot update the app without wiping it. How: [docs/signing.md](docs/signing.md).
2. **Register the app with Google.** Backups go to a hidden folder in your own Google Drive. Even so, Google only lets an app use Drive after the app is registered. This is free and takes about 10 minutes. Without it, everything else works, but your data lives only on your phone. How: [docs/google-setup.md](docs/google-setup.md).
3. **Install the app built with your key.**
4. **Add your DeepSeek API key** in Settings. Without it, the timer, money entries and all the numbers still work. Text logs just wait as "Not parsed yet".
5. **Turn on backup** in Settings and choose a passphrase.

## How to use it

### 1. Set it up (about five minutes)

- Open the app and tap **Start fresh**.
- Pick your language, time zone, currency and starting balance.
- Add a DeepSeek API key if you have one. You can add it later in Settings.
- Set your sleep goal.

### 2. Create a track for each goal

A track is one thing you want to improve. Examples: learning Rust, reading a book, a side project, getting stronger.

- Go to **Tracks** and tap **New track**.
- Pick a domain: Exercise, Project, Basics, Math or Reading.
- Name it and say where you are now.
- Tap **Generate a draft with AI** (needs a DeepSeek key), or **Plan in DeepSeek** to talk it through in the DeepSeek app and paste the plan back. You can edit the draft before you save it, or skip both and fill it in yourself.
- Keep only a few tracks active at once. The app allows eight at most. Fewer works better.

### 3. Every day

- **Start the timer** on a track when you sit down to work. Stop it when you are done. The timer also lives in your notifications.
- **Tell it what you did.** Type in the box on **Today**, for example "Read chapter 3, the part on habits was useful". You can also attach a photo, take one, or share a screenshot into the app.
- Most logs save on their own; tap **Undo** if one is wrong. When the app isn't sure, a card asks you to check it first. You get the numbers right away, then a short comment and your next step.
- **Money:** type `-42.50 lunch`, `+350` or `=4210`. It is saved at once.
- **Sleep:** share a screenshot from your sleep app, or add the night by hand on **Foundation**.
- At night, open **Daily close** (the moon icon). Write down tomorrow's most important thing.

Small days count. Set a **daily target** from Today: a minimum that still counts as a win, and a plan. Meeting the minimum on a busy day keeps your streak going.

### 4. Every week

- Open **Review**.
- Look at the table. It shows time, finished units and logs for each track. Under it are your sleep and money compared with last week.
- Answer the few questions. DeepSeek writes them from your tracks. Tap **New questions** for a different set, or **Edit** to write your own.
- Write **one thing to fix** next week and your **top 3**. Your top 3 stays on Today all week.
- Submit. DeepSeek writes a short review of your week.

Next week, check whether you fixed that one thing. That is the core of the app.

### 5. When a goal is done

- Open the track and tap **Complete**. Write where you ended up.
- For exercise and math you can tap **Start next level**. The new track starts where the old one ended.
- If a goal no longer matters, tap **Abort** and write why. That is fine too. It is a choice, not a failure.

### Goals that are not tracks

Sleep and money are not tracks. They are always there, on the **Foundation** tab.

- **Sleep goal:** tap **Edit goal** under the sleep chart.
- **Savings goal:** tap **Set a savings goal** under the balance chart. Add a date and the app tells you how much to save each month.

### Good habits

- Log right after you finish, while you still remember.
- Be honest. The numbers only help if they are true.
- Answer the "Can you explain it without AI?" questions when they come. They show what you really understand.
- Turn on backup in Settings. Keep your passphrase somewhere safe. Without it, nobody can open the backup, not even you.

## Develop

```sh
direnv allow          # or: nix develop
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The flake gives you JDK 17 and the Android SDK (platform 35). `nix develop .#emulator` adds the emulator.

- Code is split into `domain/` (plain Kotlin rules with JVM tests), `data/`, `llm/`, `ingest/`, `timer/`, `backup/`, `github/`, `ui/` and `work/`.
- Prompts are in `app/src/main/assets/prompts/`. After you change one, refresh its golden file: `./gradlew testDebugUnitTest --tests '*PromptGolden*' -Dgolden.update=true`.
- UI text lives in `tools/strings_table.py`. Run `python3 tools/strings.py` after you edit it.
- The package is `com.khoryz666.waypoint`. Debug builds add `.debug`. `minSdk` is 29 and `targetSdk` is 35.
- Choices the spec left open are in [DECISIONS.md](DECISIONS.md).
- To check how well DeepSeek parses your real sleep screenshots and logs, put samples in `fixtures/` and run the fixture check. It uses your API key and costs a few tokens, so it never runs in CI. How: [fixtures/README.md](fixtures/README.md).

## Release

1. Set `waypoint.version` in `gradle.properties`.
2. Add a matching `## [x.y.z]` section to `CHANGELOG.md`.
3. Push a `vx.y.z` tag.

The `Release` workflow checks both, builds a signed APK and publishes it as a GitHub Release.
