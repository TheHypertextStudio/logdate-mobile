# Release secrets — iOS + Android publish setup

One-time and rotation runbook for the credentials that the publish
workflows need. Never commit real secret values, provider-assigned client
IDs, certificate fingerprints, account identities, or deployment receipts
here. Read current values from the named secret store or provider console.

The workflows that consume these secrets:

- `.github/workflows/ci.yml` (Android Firebase config — required to compile)
- `.github/workflows/screenshot-test.yml` (Android + iOS Firebase)
- `.github/workflows/publish-android-play.yml`
- `.github/workflows/publish-ios-app-store.yml` (new — see
  `docs/runbook/launch-readiness-workflow-patches.md`)

---

## Firebase config secrets (Android + iOS)

The composite action `.github/actions/setup-firebase-configs` materializes
these onto the runner before the build. The operator-side CLI
`scripts/sync-firebase-configs.sh` handles upload — drop the file on disk,
run one command.

| Secret                                                | Source file                                                                                                                                                           | Rotation                                                                                               |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `LOGDATE_ANDROID_GOOGLE_SERVICES_JSON_DEBUG_BASE64`   | `app/android-main/google-services.json` (debug Firebase project)                                                                                                      | When Firebase debug project config changes.                                                            |
| `LOGDATE_ANDROID_GOOGLE_SERVICES_JSON_RELEASE_BASE64` | `app/android-main/src/release/google-services.json` (release Firebase project)                                                                                        | When Firebase release project config changes.                                                          |
| `LOGDATE_IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64`        | `iosApp/iosApp/Firebase/GoogleService-Info-Release.plist` (single iOS Firebase project)                                                                               | When Firebase iOS project config changes.                                                              |
| `LOGDATE_GOOGLE_MAPS_API_KEY_DEBUG`                   | Dedicated debug-project Android key restricted to `studio.hypertext.logdate.debug`, its matching debug signing certificate, Places API, and Maps SDK for Android.     | When the debug signing key or API restriction changes.                                                 |
| `LOGDATE_CI_DEBUG_KEYSTORE_BASE64`                    | Dedicated CI-only debug keystore, alias `androiddebugkey`, with the standard Android debug password. Used only by native-map acceptance.                              | Rotate together with the CI Maps key restriction and `LOGDATE_CI_DEBUG_CERT_SHA1` repository variable. |
| `LOGDATE_CI_GOOGLE_MAPS_API_KEY`                      | `logdate-dev` Maps SDK for Android key restricted to `studio.hypertext.logdate.debug` and the exact dedicated CI debug certificate.                                   | When the CI signing identity changes.                                                                  |
| `LOGDATE_GOOGLE_MAPS_API_KEY_RELEASE`                 | Dedicated release-project Android key restricted to `studio.hypertext.logdate` and the applicable release signing certificates, Places API, and Maps SDK for Android. | When the upload or Play signing key changes.                                                           |

The iOS plist must come from the production Firebase registration for
`studio.hypertext.apps.logdate`. Set the repository variable
`LOGDATE_FIREBASE_IOS_APP_ID` to that registration's `GOOGLE_APP_ID`, and export the
same variable locally before validating or uploading the plist. Do not edit an
earlier registration's bundle field to reuse it. See the [Apple provider and data
continuity requirements](../reference/ios-build-setup.md#provider-registration-and-existing-installations).

### Google ID-token audiences and Android OAuth clients

The build workflows pass the web/server OAuth client ID separately from
`google-services.json`. These are GitHub repository variables, not secrets:

| Variable                                  | Environment | Authoritative source                                                |
| ----------------------------------------- | ----------- | ------------------------------------------------------------------- |
| `LOGDATE_GOOGLE_SERVER_CLIENT_ID_DEBUG`   | Debug       | Web/server OAuth client in the debug Google Auth Platform project   |
| `LOGDATE_GOOGLE_SERVER_CLIENT_ID_RELEASE` | Release     | Web/server OAuth client in the release Google Auth Platform project |

The release project must also contain an Android OAuth client restricted to package
`studio.hypertext.logdate` and the upload-signing certificate. Read the client and certificate
values from Google Auth Platform and the signing-key authority; do not copy them into this file.

Creating or changing an Android OAuth client changes the generated Firebase config. Download the
new `google-services.json`, validate it, and rerun
`./scripts/sync-firebase-configs.sh android-release` so CI receives the same client set.

The upload-certificate client does not authorize a Play-installed build. After Play App Signing
exists, obtain Google's app-signing SHA-1 from Play Console and create a second Android OAuth
client for the same package with that certificate. The corresponding SHA-256 belongs in the
Digital Asset Links file maintained by `logdate-web`.

**Upload from local disk** (one-shot, idempotent — re-running rotates):

```bash
./scripts/sync-firebase-configs.sh android-debug    # debug only
./scripts/sync-firebase-configs.sh android-release  # release only
./scripts/sync-firebase-configs.sh ios              # iOS only
./scripts/sync-firebase-configs.sh all              # everything
```

Each upload is checked against LogDate's exact Firebase project, package or
bundle ID, and Firebase app ID before transmission. Requires `gh` authenticated
against the repo.

> **Note on the previous secret name:** an earlier iteration referenced
> `LOGDATE_ANDROID_GOOGLE_SERVICES_JSON` (raw, unencoded). It's been
> replaced by the two `_BASE64`-suffixed entries above. If your local
> notes still reference the old name, update them — the workflows no
> longer read it.

---

## GitHub Secrets — Android publish (`production` environment)

| Secret                           | What it is                                                      | Rotation                                    |
| -------------------------------- | --------------------------------------------------------------- | ------------------------------------------- |
| `LOGDATE_RELEASE_STORE_BASE64`   | Base64 of the Android release keystore (`logdate-release.jks`). | Never (a new keystore = a new app on Play). |
| `LOGDATE_RELEASE_STORE_PASSWORD` | Password for the keystore.                                      | When rotated alongside the keystore.        |
| `LOGDATE_RELEASE_KEY_ALIAS`      | Key alias inside the keystore.                                  | When rotated alongside the keystore.        |
| `LOGDATE_RELEASE_KEY_PASSWORD`   | Password for the signing key.                                   | When rotated alongside the keystore.        |

These live in the `production` GitHub environment, not at repository scope,
so only `main` and release tags can read them. `./run setup production`
uploads them and reports when they drift; see
[`docs/reference/project-setup.md`](../reference/project-setup.md).

Play API access needs no secret. The publish jobs authenticate through
Workload Identity Federation as the service account named by the
`LOGDATE_PLAY_SERVICE_ACCOUNT` environment variable, so there is no key to
rotate.

### Local dev machine setup (dogfood builds)

The `dogfood` build type (`app/android-main/build.gradle.kts`) is signed
with the same upload key as `release` instead of the per-machine debug
keystore, specifically so a locally-built, fully debuggable install still
passes Android's Digital Asset Links check for passkey sign-in against
production. `./gradlew :app:android-main:installDogfood` (or `./run
run:android:dogfood`) falls back to debug signing — and debug's passkey
limitation — whenever these aren't set, so this is opt-in per machine, not
required for normal `debug` development.

To enable it on a machine, set these four environment variables (or the
equivalent `logdate.release.*` Gradle properties — see `resolveRelease()`
near the top of `app/android-main/build.gradle.kts`) to the _same_ upload
keystore used for real releases, stored however you already keep local
secrets on that machine:

- `LOGDATE_RELEASE_STORE_FILE` — path to the decoded `.jks` (not the
  base64 form GitHub Actions stores)
- `LOGDATE_RELEASE_STORE_PASSWORD`
- `LOGDATE_RELEASE_KEY_ALIAS`
- `LOGDATE_RELEASE_KEY_PASSWORD`

If a machine already builds real `release`/`bundleRelease` artifacts,
these are already in place and `dogfood` works with no further setup.

---

## GitHub Secrets — iOS (new — six secrets)

### `APP_STORE_CONNECT_API_KEY_ID`, `_ISSUER_ID`, `_P8`

App Store Connect API keys are how the workflow authenticates without
a human Apple ID. One key generated once per project, lives in App
Store Connect, used both by `xcrun altool --upload-app` and by
`scripts/submit-ios-for-review.sh`.

**Generate the key:**

1. Sign in to App Store Connect at
   https://appstoreconnect.apple.com/access/api as an Account Holder
   or Admin.
2. **Users and Access → Integrations → App Store Connect API → Team
   Keys → +**.
3. Name: `LogDate Publish CI`.
4. Access role: **App Manager** (sufficient for upload + submit; do
   not pick Admin unless you need to mint API keys themselves).
5. Click **Generate**.
6. Note the **Key ID** (10-character string, top of the row) → save as
   `APP_STORE_CONNECT_API_KEY_ID`.
7. Note the **Issuer ID** (UUID, near the top of the page) → save as
   `APP_STORE_CONNECT_API_ISSUER_ID`.
8. Click **Download API Key** — Apple gives you the `.p8` file
   **exactly once**. Save the file content (the entire `-----BEGIN
PRIVATE KEY-----` block) as `APP_STORE_CONNECT_API_KEY_P8`.

If you lose the `.p8`, you have to revoke the key in App Store Connect
and start over. Keep an out-of-band backup in your password manager.

### `IOS_DISTRIBUTION_CERT_P12_BASE64`, `_PASSWORD`

The Apple Distribution certificate the workflow uses to sign the
`.ipa`. One certificate, lives in your developer portal, expires every
**3 years** (rotate ahead of expiry).

**Generate / export:**

1. Open **Keychain Access** on a Mac that already has the cert (or
   request a new one from
   https://developer.apple.com/account/resources/certificates → +
   → **Apple Distribution** → upload a CSR generated by Keychain
   Access via Keychain Access → Certificate Assistant → Request a
   Certificate from a Certificate Authority).
2. In Keychain Access, find the **"Apple Distribution: <Team Name>
   (T95VDD3A4W)"** identity in the **login** keychain.
3. Right-click the identity (the row with the disclosure triangle, not
   just the cert) → **Export**.
4. Format: **Personal Information Exchange (.p12)**.
5. Set a password — save it as `IOS_DISTRIBUTION_CERT_PASSWORD`.
6. Convert the file to base64:

   ```bash
   base64 -i distribution.p12 | pbcopy
   ```

   Paste the result as `IOS_DISTRIBUTION_CERT_P12_BASE64`.

### `IOS_PROVISIONING_PROFILE_BASE64`

The App Store provisioning profile that pairs the cert with the
bundle ID. Profiles expire **every year**; rotate one full month
before expiry to avoid surprises.

**Generate:**

1. https://developer.apple.com/account/resources/profiles → +
   → **App Store Connect** → Continue.
2. Pick the **App ID** for `studio.hypertext.apps.logdate` (create it if
   missing under **Identifiers**; bundle ID `studio.hypertext.apps.logdate`,
   capabilities: HealthKit, Associated Domains, Push Notifications).
3. Pick the distribution certificate you exported above.
4. Name the profile something stable, e.g. `LogDate App Store
Distribution`.
5. **Generate**, then **Download**. Save the `.mobileprovision` file.
6. Convert to base64:

   ```bash
   base64 -i LogDate_App_Store_Distribution.mobileprovision | pbcopy
   ```

   Paste as `IOS_PROVISIONING_PROFILE_BASE64`.

`scripts/install-ios-signing.sh` reads the provisioning-profile UUID
out of the binary at job start and exports it as
`LOGDATE_IOS_PROVISIONING_PROFILE_UUID` so the workflow can write it
into `iosApp/Configuration/ExportOptions.plist` — you don't need to
hardcode the profile name in any committed file.

---

## GitHub Environments

**Android** publishes through the `production` environment, which
`./run setup production` configures. It has no required reviewer, because
pushing an `android-v*` tag is already a deliberate release act and a reviewer
gate would also pause every internal (dogfood) publish from `main`. Instead,
its deployment policy lets only `main`, `android-v*` tags, and `server-v*`
tags use it, so pull requests and other branches can never read its secrets.

**iOS** still uses `ios-production`, set up by hand:

1. **Settings → Environments → New environment**, named exactly
   `ios-production` (the workflow YAML references this string).
2. **Required reviewers** → add the people who can approve a production
   release.
3. Optionally add a **Wait timer** (e.g. 5 minutes) to give a paged reviewer
   a window to abort.

Without the environment, a `git push origin ios-v1.0.0` would auto-submit to
Apple's review queue with no human gate.

---

## Rotation calendar

| Item                           | Cadence                                             | Last rotated       |
| ------------------------------ | --------------------------------------------------- | ------------------ |
| Apple Distribution cert        | every 3 years                                       | _record date here_ |
| App Store provisioning profile | every 1 year                                        | _record date here_ |
| App Store Connect API key      | when team membership changes                        | _record date here_ |
| Android keystore               | never (one keystore for the app's lifetime on Play) | n/a                |

Add a recurring calendar reminder 30 days ahead of each rotation
window. Apple's expiry notices land in the dev-portal email; they're
easy to miss in a shared inbox.

---

## Verification checklist (after first-time setup)

Run through this once before the first production tag:

1. `gh secret list --repo TheHypertextStudio/logdate-mobile` — confirm all
   six iOS secrets are present.
2. `./run setup production --check` reports every step ok, and
   **Settings → Environments** lists `ios-production` with at least one
   required reviewer.
3. `gh workflow list` shows `Publish iOS` (after the runbook patch
   lands).
4. Local: `./scripts/resolve-ios-app-version.sh` runs cleanly against
   a clean clone with an `ios-v0.0.1-rc1` tag pointing at HEAD.
5. Cut a no-op commit on `main`; the **Publish iOS** workflow
   completes, the build appears in App Store Connect → TestFlight
   → Internal Testing within ~10 minutes.
6. Push `ios-v0.0.1-rc1` to a branch; the workflow runs to the
   environment-approval gate and waits. Cancel without approving so
   nothing actually submits.
