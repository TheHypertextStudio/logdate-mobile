# Staging and Production Configuration

This is the operating contract for LogDate's server and shipped app builds.
If a workflow, Gradle property, Firebase file, or Terraform value disagrees
with this page, treat it as a deployment blocker until the mismatch is
resolved.

## Server Environments

| Concern                 | Staging                                       | Production                                       |
| ----------------------- | --------------------------------------------- | ------------------------------------------------ |
| Deploy workflow         | `.github/workflows/deploy-server-staging.yml` | `.github/workflows/deploy-server-production.yml` |
| Trigger                 | Successful `CI` workflow on `main`            | `server-v*` tag push only                        |
| GitHub Environment      | `staging`                                     | `production`                                     |
| Terraform file          | `infra/terraform/staging.tfvars`              | `infra/terraform/production.tfvars`              |
| GCP project             | `logdate-dev`                                 | `logdate`                                        |
| Cloud Run service       | `logdate-server-staging`                      | `logdate-server`                                 |
| Public host             | `cloud-staging.logdate.app`                   | `cloud.logdate.app`                              |
| WebAuthn RP ID          | `staging.logdate.app`                         | `logdate.app`                                    |
| WebAuthn origin         | `https://app.staging.logdate.app`             | `https://cloud.logdate.app`                      |
| Auto migrations         | `AUTO_MIGRATE=false`                          | `AUTO_MIGRATE=false`                             |
| Browser passkey origins | `https://app.staging.logdate.app`             | `https://cloud.logdate.app`                      |
| Secret storage          | Staging project's Secret Manager              | Production project's Secret Manager              |
| Media bucket            | `logdate-media-staging`                       | `logdate-media-logdate`                          |

Production deploys are deliberately tag-gated and staging-verified. The
production workflow checks for a successful staging deploy for the same SHA,
then runs the passkey smoke test against `https://cloud-staging.logdate.app`
before invoking the reusable Cloud Run deploy workflow.

Both environments run explicit Flyway migrations before deploying the
no-traffic candidate. Migrations and the candidate use the same rendered
deployment contract and pinned database secret versions; application startup
does not run migrations.

`LOGDATE_DEPLOY_SOURCE` controls where deploy workflows read Terraform-style
configuration:

- `repo_files` reads the committed `infra/terraform/<env>.tfvars` files.
- `repo_vars` reads GitHub repository variables created by bootstrap tooling.
- If both are present, the workflow mode decides precedence; do not assume
  repo variables silently override committed tfvars.

The deployment contract below is rendered from the committed tfvars for production, and
for staging outside CI. In CI, staging renders from the committed
`infra/terraform/staging-contract-template.json` instead, and the same validation then
checks it. The deploy stops when the contract's project, region, or service differs from
the source the workflow selected.

The committed tfvars files must contain only non-sensitive configuration:
project IDs, service names, domains, Secret Manager secret IDs, and runtime
flags. Secret values belong only in Secret Manager or GitHub Actions secrets.

Every Cloud Run secret mount, including `DATABASE_URL`, `DATABASE_USER`, and
`DATABASE_PASSWORD`, must name an enabled, exact numeric Secret Manager version.
`latest` is prohibited in first-party staging and production contracts. Before
rotating a database secret, add and enable the new version, update all three
database bindings together in the committed environment contract, deploy a
no-traffic candidate, run migrations and smoke checks against that same
contract, then promote. Do not use a Cloud SQL instance name or database name
as a first-party deployment input: both the migration helper and candidate
revision consume the rendered Neon contract.

## Deployment Contract

Every deploy renders one contract with
`scripts/render-cloud-run-contract.sh --environment <env> --release-sha <sha>`. Except
for staging in CI, which reads the committed `staging-contract-template.json` from the
release commit, the renderer reads the tfvars through Terraform in a private, throwaway
data directory, so no local state or backend metadata can change the result. It refuses
to run when the script on disk differs from the copy in the release commit. Migrations, the container
preflight, the candidate revision, and the smoke tests all read that single rendered
file. That is what stops one step from addressing a different database, origin, or
certificate set than another. The renderer prints nothing when a rule below is broken.

- `LOGDATE_ENV` is `production` in both environments. Staging is a separate
  deployment, not permission to use development fallbacks such as in-memory
  repositories, so the server's production-profile validation runs in staging too.
- The key set is closed. The renderer requires every key it knows and rejects any key
  it does not know, in both `cloud_run_env` and `cloud_run_secret_env`. The optional
  feature flags it accepts must each be `true` or `false`. It derives
  `WEBAUTHN_ALLOWED_ORIGINS`, `ANDROID_CERT_FINGERPRINTS`, and `RELEASE_VERSION`
  (`logdate-server@<sha>`) itself, and rejects tfvars that set the first two.
- Each environment has exactly one domain. The public origin and the AT Protocol
  service URL are that domain's HTTPS origin, the billing provider is `play`,
  and `AUTO_MIGRATE` is `false`.
- Every secret binding carries an exact positive version number. Required secrets
  cover the database, JWT signing, hosted AT Protocol keys, server-side encryption,
  and the internal health token; Sentry, Google sign-in, Redis, billing, and
  decrypt-only key bindings are optional.
- The runtime listens on port 8080, probes `/health`, and allows at least one instance
  at maximum scale, never fewer than its minimum.

### Android signing certificates

`android_signing_certificates` lists the certificates that sign the builds people
install. Staging requires a `staging` certificate and may add `play_app_signing`;
production requires `upload` and may add `play_app_signing`. Play re-signs installs
with its own app-signing certificate, so passkey sign-in from a Play install works only
when that certificate is listed.

Each entry holds a colon-separated SHA-256 fingerprint and the matching
`android:apk-key-hash:` origin. The renderer recomputes the origin from the
fingerprint and rejects a mismatch, a certificate listed twice, the standard Android
debug certificate, and placeholder fingerprints (every byte equal, or counting up or
down). An ordinary per-machine debug certificate is therefore never trusted for Cloud
passkeys. Use `scripts/create-signing-keystore.sh` to derive an entry from a keystore;
see [Google Play publishing](../reference/google-play-publishing.md#helper-scripts).

The server turns the set into two things: the Android origins in
`WEBAUTHN_ALLOWED_ORIGINS` and the document it serves at
`/.well-known/assetlinks.json`. Android Credential Manager reads that document from the
relying-party domain, not from the API host, and lets the app use passkeys for the
domain only when the installed certificate is listed. In production the relying-party
domain `logdate.app` is a separate website, so its copy must list exactly the
certificate set in the contract. Update both together whenever a certificate changes.

## Database Contract

First-party staging and production use Neon PostgreSQL through three Secret Manager
secrets: `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD`. Cloud Run is the
compute platform, and media lives in Cloud Storage, independent of the database. A
self-hosted server can use any PostgreSQL service that satisfies the same contract,
and the server also still supports a Cloud SQL instance through
`INSTANCE_CONNECTION_NAME` and `DB_NAME`; first-party contracts use neither.

Each environment's three database secrets must point to its own Neon project or
branch with independent credentials, so that staging and production never share a
writable database. Nothing in the contract checks this, so confirm it whenever a
secret version changes. App builds embed no database credentials; a build knows only
its server's origin.

The migration runner (`scripts/run-migrations.sh`) normalizes the URL once, before it
starts any database client, and accepts only a URL that satisfies all of these:

- The scheme is `postgres://`, `postgresql://`, or `jdbc:postgresql://`, with one host
  and one database whose name uses letters, digits, `_`, `.`, or `-`.
- No credentials are embedded: no user information, and no `user`, `username`, or
  `password` query parameter. The user and password come only from their own secrets,
  which keeps a single source for each credential, the one the server and the runner
  both read.
- The only query parameters are `sslmode` and `channelBinding`, each given once with a
  valid value. A fragment, a control character, or a multi-host authority is rejected.

Flyway and `psql` both receive the normalized host, port, database, user, password, TLS
mode, and channel-binding policy from permission-0600 environment files in a private
directory that is removed on every exit, so the two clients cannot disagree about the
target. Secret values and the full URL never appear in command arguments or logs. A
rejected URL stops the run before Docker starts.

After migrating, the runner checks that the account-cleanup tables and triggers exist,
because servers started with `AUTO_MIGRATE=false` depend on them to remove stored files
when an account is deleted. It runs Flyway with `baselineOnMigrate=false` and
`outOfOrder=false`, and it can also validate the passkey foreign key. Migrations run
before the candidate receives traffic and are never reversed, so each one must work with
the revision that is still serving.

The server's connection pool matches Cloud Run's per-instance request concurrency, keeps
no idle floor, and releases idle connections after five minutes, so Neon can suspend its
compute after traffic stops. A request
waits at most ten seconds for a connection, which covers waking a suspended database. A
longer wait would starve the liveness probe and take the instance down.

## Deployment Proof

Staging and production deploy through `deploy-server-cloud-run.yml` in this order, and
the first failing step stops the deploy before traffic moves, except where noted:

1. Render the contract and check it names the selected project, region, and service.
2. Build and push the image tagged with the full release SHA.
3. Run the migrations against the database the contract pins.
4. Start the image locally with the contract's environment and secrets and require
   `/health`.
5. Deploy a tagged candidate revision with no traffic. Its environment variables are
   replaced from the contract, so a variable missing from the contract does not
   survive from an earlier revision.
6. Run the smoke test's `prepare` phase and the journal-merge smoke test's `prepare`
   phase against the candidate's private tag URL, using an identity token for the deploy
   identity. If the journal-merge phase fails, the workflow runs its `recover` phase and
   stops.
7. Run the linked-watch candidate journeys against the candidate.
8. Record the revision serving all traffic. The deploy stops if no revision does.
9. Move all traffic to the candidate.
10. Deploy the repository event stream service and the durable account blob cleanup
    job, and execute the job.
11. Run the journal-merge and linked-watch smoke tests, then the revision smoke test's
    `verify-and-cleanup` phase, against the canonical origin. All three run before any
    of them can fail the deploy, so a failure in one does not leave the smoke account
    behind.
12. Check that the service's Cloud Run URL, and then its custom domain, report healthy.
    The custom domain check only warns while its certificate is still provisioning.

If a step after the traffic move fails, the workflow restores the revision recorded in
step 8. It restores a single revision, so before a release, check that the service
serves from one revision. Production recovery uses this same workflow and contract. A
manual traffic change made during an incident is not authoritative until the equivalent
state is reconciled in the committed contract, or the next deploy replaces it.

The smoke test (`scripts/smoke-test-revision.sh`) proves that a candidate is the
environment it claims to be and that data survives the traffic switch. Both phases check
that public health reports the contract's release, that internal health (which needs the
health token) also reports a live database connection, the server descriptor's handle
domain, relying-party ID, and required capabilities, the assets-links document against
the contract's certificate set, that an unauthenticated entitlement request is refused,
and that Google sign-in rejects an invalid token. In `verify-and-cleanup` the
assets-links document is fetched from the relying-party domain rather than the API host.

`prepare` then creates a disposable account with a simulated passkey authenticator,
signs back in as it, checks the free-plan entitlement, uploads a media fixture (or a
record, for an account stored in a personal space), and checks storage usage.
`verify-and-cleanup` signs in as the same account on the canonical origin, reads the
fixture back byte for byte, and checks the usage and storage mode are unchanged. It
then deletes the media, proves the refresh token is revoked, and deletes the account
last. The state file holding the disposable credentials is private to the runner, and
cleanup records its progress there, so a second run resumes an interrupted cleanup.

## Server Safety Rules

- Do not share database URLs, JWT secrets, health tokens, Redis URLs, Sentry
  DSNs, or media buckets between staging and production.
- Do not change production `AUTO_MIGRATE=false` without a reviewed rollback
  plan. Production migrations should be explicit and observable.
- Do not set staging `webauthn_rp_id` to `logdate.app`. Staging uses
  `staging.logdate.app` with the exact browser origin
  `https://app.staging.logdate.app`, so staging passkeys use a distinct relying
  party. Never authorize the production frontend origin `https://app.logdate.app`
  or the production backend origin `https://cloud.logdate.app` in staging.
- Do not set `ATPROTO_PLC_PUBLISH_ENABLED=true` in staging. Staging accounts are test accounts,
  and anything published to the public PLC directory stays there; the contract renderer refuses it.
  Production turns it on only by following
  [identity publishing runbook](identity-publishing.md).
- Do not set `ATPROTO_RELAY_HOSTS` in staging. Relays are asked to follow the server only from
  production, and the contract renderer refuses it elsewhere. Production sets it only after
  `ATPROTO_FIREHOSE_ENABLED=true`, following the
  [repository event stream runbook](repository-event-stream.md).
- Do not add non-Cloud-Run domains to Terraform `domains`. Marketing redirects
  and unrelated DNS live outside this Cloud Run service.
- Do not mount optional Cloud Run secrets until the Secret Manager container
  has at least one version. Cloud Run rejects revisions with empty mounted
  secrets.

## Staging Browser Namespace

The staging frontend uses `staging.logdate.app`, browser authentication uses
`app.staging.logdate.app`, and tenant portals use
`<username>.staging.logdate.app`. Browser sessions must use host-only cookies;
tenant portals obtain their own session through the authorization code handoff.
These frontend hosts are separate from the Cloud Run API and identity host,
`cloud-staging.logdate.app`, which remains the backend deployment domain.

A passkey is bound to its relying-party ID, so a staging credential enrolled under
any RP ID other than `staging.logdate.app` cannot sign in to staging, and changing the
RP ID means every staging user enrolls a new passkey. Production passkeys use
`logdate.app` and are unaffected.

## App Builds

| Concern                 | Android debug / CI                                  | Android Play internal                                 | Android Play production                                                                                                                                                                       | iOS TestFlight                                            | iOS App Store                                             | Desktop                                       |
| ----------------------- | --------------------------------------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- | --------------------------------------------------------- | --------------------------------------------- |
| Workflow                | `ci.yml`, `screenshot-test.yml`                     | `publish-android-play.yml`                            | `publish-android-play.yml`                                                                                                                                                                    | `publish-ios-app-store.yml`                               | `publish-ios-app-store.yml`                               | Local Gradle tasks                            |
| Trigger                 | PR/push/manual test workflows                       | Manual dispatch with the `internal` stage             | Manual dispatch with a staged-rollout action, naming a tested `android-v*` tag                                                                                                                | `main` push or manual dispatch                            | `ios-v*` tag push                                         | Manual                                        |
| Human gate              | None                                                | Disabled unless repo var is true                      | Disabled unless repo var is true; the dispatch needs the tag, the tested version code, and a link to the acceptance record; the `production` environment accepts only `main` and release tags | Disabled unless repo var is true                          | `ios-production` reviewers                                | None                                          |
| Firebase input          | `LOGDATE_ANDROID_GOOGLE_SERVICES_JSON_DEBUG_BASE64` | `LOGDATE_ANDROID_GOOGLE_SERVICES_JSON_RELEASE_BASE64` | None; promotes tested internal artifact                                                                                                                                                       | `LOGDATE_IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64`            | `LOGDATE_IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64`            | None                                          |
| Firebase file on runner | `app/android-main/google-services.json`             | `app/android-main/src/release/google-services.json`   | Not materialized                                                                                                                                                                              | `iosApp/iosApp/Firebase/GoogleService-Info-Release.plist` | `iosApp/iosApp/Firebase/GoogleService-Info-Release.plist` | None                                          |
| Signing                 | Debug signing                                       | Release keystore from GitHub secrets                  | No keystore; promotes existing Play artifact                                                                                                                                                  | Apple distribution cert/profile                           | Apple distribution cert/profile                           | Local unsigned packages                       |
| Backend default         | `https://cloud.logdate.app` unless overridden       | `https://cloud.logdate.app`                           | Same tested artifact                                                                                                                                                                          | `https://cloud.logdate.app` via shared config             | Same source as TestFlight                                 | `https://cloud.logdate.app` via shared config |

The shared app default is production cloud:

- `shared/config/.../LogDateConfigRepository.kt` defaults to
  `https://cloud.logdate.app`.
- `gradle.properties` sets `logdate.origin=logdate.app` and
  `logdate.apiBaseUrl=https://logdate.app` for generated app BuildConfig
  values used by deep links and origin classification.
- Set `-Plogdate.backendUrl=https://cloud-staging.logdate.app` when building
  against staging. `shared/config/build.gradle.kts` uses this Gradle property
  to generate the shared client's default backend URL. Changing `logdate.origin`
  or `logdate.apiBaseUrl` does not change that backend default.

This default is intentional for shipped builds: users should not land on
staging by accident. It also means staging app testing must be explicit; never
assume a debug, desktop, or simulator build is pointed at staging unless the
override is visible in the command or local config.

## App Safety Rules

- Android CI and screenshot workflows use the debug Firebase project. Android
  Play internal and production release bundles use the release Firebase
  project because both paths operate on the `release` build type.
- Android production does not rebuild. It promotes the exact internal Play
  artifact for the tested version code, preserving provenance, and moves it
  through the staged rollout described in
  [Google Play publishing](../reference/google-play-publishing.md#production-track).
- iOS Debug builds run without Firebase. iOS Release/TestFlight/App Store
  builds require `GoogleService-Info-Release.plist` in the path the Xcode
  build phase copies.
- iOS production re-archives from source because App Store Connect has no Play
  style promote-from-internal API. The `ios-production` GitHub Environment is
  the required human gate.
- Desktop has no publishing workflow in this repo. Treat desktop packages as
  local verification artifacts until a signed desktop release workflow exists.
- Firebase config files and signing materials are gitignored; if one appears
  in `git status`, stop and remove it before committing.

## Pre-Release Checks

Before a production server tag:

1. Confirm `CI` and `Deploy Server Staging` are green for the target SHA.
2. Confirm the staging smoke test used `https://cloud-staging.logdate.app`.
3. Confirm production tfvars still have `AUTO_MIGRATE=false` and
   `webauthn_rp_id="logdate.app"`.

Before an Android production tag:

1. Confirm the tagged commit is already on Play internal; promotion finds
   that release by the commit's short SHA and the tested version code, and fails
   if either is missing.
2. Confirm every journey in the
   [Android acceptance journeys](../RELEASE.md#android-acceptance-journeys) passed
   for that version code.
3. Confirm `LOGDATE_PLAY_PRODUCTION_PUBLISH_ENABLED=true`, which
   `./run setup production --enable-play-production` sets.
4. Confirm `./run setup production --check` reports every step ok.

Before an iOS production tag:

1. Confirm the TestFlight build path succeeded for the target commit.
2. Confirm `LOGDATE_IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64` came from
   `iosApp/iosApp/Firebase/GoogleService-Info-Release.plist`.
3. Confirm the distribution cert and provisioning profile are not within
   30 days of expiry.
