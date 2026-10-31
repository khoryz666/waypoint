# Parsing fixtures

A manual check of how well DeepSeek parses your real input (spec 10.3). It sends the files
below through the app's own parsing code to the real API and writes what came back to
`out/results.md`. It costs a few tokens per file, so it never runs in CI.

Everything in this folder except this README and the `.example` files is git-ignored, so
your screenshots and logs stay on your machine.

## What to put here

| Path | What |
| --- | --- |
| `sleep/` | Screenshots from your sleep app: single-night pages and multi-night summaries (PNG or JPG). |
| `logs.txt` | Sample text logs, one per line, written the way you'd type them in the app. `#` starts a comment. Money shortcuts like `-42.50 lunch` skip DeepSeek in the app; here they go through it, which tests natural-language money entries. |
| `tracks.txt` | Optional. Your tracks, one `DOMAIN | Title | Goal` per line, so text logs have something to route to. Domains: EXERCISE, PROJECT, FUNDAMENTAL (Basics), MATH, READING. |
| `sleep-app.txt` | Optional. The name of your sleep app, passed to the prompt as a hint. |

Copy `logs.txt.example` and `tracks.txt.example` to start.

## Run it

```sh
DEEPSEEK_API_KEY=sk-... ./gradlew testDebugUnitTest --tests '*FixtureParseRun*' -Dfixtures=true
```

Then read `fixtures/out/results.md`. For each file it shows the outcome, the track it was
routed to (with confidence) and the parsed JSON.

Optional environment variables: `FIXTURE_LANG` (`zh-CN` or `en`, default `zh-CN`),
`FIXTURE_CURRENCY` (default `USD`), `DEEPSEEK_FAST_MODEL` (default `deepseek-flash`).

To keep the key out of your shell history, type a space before the command (bash ignores
lines starting with a space when `HISTCONTROL` includes `ignorespace`), or `export` it
from a file you don't commit.
