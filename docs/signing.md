# Release signing

The release keystore never goes in the repo. Its SHA-1 is registered with Google OAuth
(`docs/google-setup.md`), and Android only installs updates signed with the same key, so
**the key must never change**. Lose it and you lose both in-place updates and Drive access
until you register a new key.

## Create the keystore (once)

```sh
mkdir -p ~/.config/waypoint
keytool -genkeypair -v -keystore ~/.config/waypoint/release.keystore \
  -alias waypoint -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Waypoint"
```

Store a copy of the keystore and its passwords in your password manager.

## Local release builds

Add to `~/.gradle/gradle.properties` (not the repo's):

```properties
WAYPOINT_KEYSTORE=/home/<you>/.config/waypoint/release.keystore
WAYPOINT_KEYSTORE_PASSWORD=...
WAYPOINT_KEY_ALIAS=waypoint
WAYPOINT_KEY_PASSWORD=...
```

Environment variables with the same names work too. Without them, `assembleRelease` still
builds, but the APK is unsigned.

## GitHub Actions secrets

The `Release` workflow (on `v*` tags) needs four repository secrets:

```sh
gh secret set WAYPOINT_KEYSTORE_BASE64 < <(base64 -w0 ~/.config/waypoint/release.keystore)
gh secret set WAYPOINT_KEYSTORE_PASSWORD
gh secret set WAYPOINT_KEY_ALIAS --body waypoint
gh secret set WAYPOINT_KEY_PASSWORD
```

`CI` (on push) uses no secrets and builds the release APK unsigned, only to check it builds.
