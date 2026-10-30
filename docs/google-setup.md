# One-time Google Cloud setup

Waypoint backs up to the hidden `appDataFolder` of your own Google Drive (spec 9.3). Google
only lets an app do that once it has an OAuth client registered for its package and signing
key. You do this once; nothing here costs money.

## 1. Project and API

1. Open <https://console.cloud.google.com/> and create a project (any name, e.g. `waypoint`).
2. **APIs & Services → Library**, search for **Google Drive API**, click **Enable**.

## 2. OAuth consent screen

1. **APIs & Services → OAuth consent screen** (now called **Google Auth Platform**).
2. User type: **External**. App name: `Waypoint`. Support and developer email: your own.
3. **Data access → Add or remove scopes**: add
   `https://www.googleapis.com/auth/drive.appdata` ("See, create, and delete its own
   configuration data in your Google Drive"). Nothing else.
4. **Audience → Test users**: add the Google account you will back up to.

## 3. Android OAuth clients

Create two **Android** clients under **Clients → Create client**, one per build:

| Client | Package name | SHA-1 from |
| --- | --- | --- |
| Release | `com.khoryz666.waypoint` | the release keystore (`docs/signing.md`) |
| Debug | `com.khoryz666.waypoint.debug` | `~/.android/debug.keystore` |

Get each SHA-1 with:

```sh
# Release
keytool -list -v -keystore ~/.config/waypoint/release.keystore -alias waypoint | grep SHA1
# Debug (password: android)
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
```

No client ID needs to go into the app: on Android, Google matches the calling app by
package name and signing certificate.

The release SHA-1 must never change, because this client is tied to it. Keep the release
keystore backed up (see `docs/signing.md`).

## 4. Testing vs Production

While the consent screen's publishing status is **Testing**, Google expires a test user's
authorization after 7 days, so backups would start failing with an authorization error
roughly weekly and the app would ask you to re-authorize.

If that happens, go to **Audience → Publish app** and move it to **In production**.

Checked on 2026-10-30 against
<https://developers.google.com/workspace/drive/api/guides/api-specific-auth>: Google lists
`drive.appdata` under **non-sensitive** scopes, which need only basic app verification, not
the sensitive/restricted-scope security review. Re-check that page before publishing, since
Google changes these rules. Until verification is done, the consent screen shows an
"unverified app" warning that you can click through for your own account.
