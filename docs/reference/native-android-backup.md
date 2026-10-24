# Native Android backup and restore

Android's backup service delivers application data independently of account sign-in. LogDate
preserves the journal owner and encryption identity while creating a distinct installation device
ID. Content availability and account authentication have independent recovery state.

The [credential custody model](native-android-recovery-custody.md) defines the owner, transport,
recovery, and invalidation boundaries for each key and credential, including deliberate Block Store non-use.

All Android execution uses Gradle Managed Devices. External host actions select the current run's
new QEMU process, expected AVD and managed-device identifier, derived console port, and matching
fixture nonce before addressing a device. An `emulator-NNNN` serial alone does not establish custody.
The native acceptance workflow allocates fresh managed generations for each scenario. Payload
drivers run only inside that lifecycle; selecting a serial directly does not establish ownership.

The [native restore test driver](../../tools/native-restore-driver/README.md) hosts transport checks
on two concurrent managed instances while BackupManager restarts LogDate. Its host controller
verifies current-run custody before each device command, preserves shell identity for nested probes,
and requires both the native content oracles and the two waiting-driver results. Invoke it through
`tests/e2e/managed-native-restore-acceptance.py`; running a payload driver directly does not establish
managed-device ownership.

The controller cold boots each read-only managed clone through a private SDK facade. Reusing
snapshot RAM can copy the guest's network identity and provisioning state between instances. The
facade adds `-no-snapshot-load` only to the current managed AVD's read-only test launches; capability
queries and snapshot creation pass through to the installed executable. The installed SDK and its
packages are unchanged. Gradle still starts and stops every device, with no data wipe or snapshot
save. Conflicting launch arguments and a `local.properties` SDK override fail before testing.

For rooted paired tests, both cold-boot launch receipts must match the current nonce, AVD, and
managed-device identifiers. Each actual `wlan0` address must be readable, valid, and distinct before
app probes start. The focused network profile checks both devices after an offline/online radio
cycle. The app must observe validated Wi-Fi and its real network-policy callbacks; a shell connection
request alone does not prove connectivity. Private launch records remain with the results. The
facade is removed only after a successful process observation confirms Gradle and its observed
launch processes have stopped; ambiguous observations retain it for inspection. CI excludes the
facade from uploaded evidence.

## Backup coverage

The manifest selects `backup_rules.xml` for older Android versions and `data_extraction_rules.xml`
for Android 12 and later. The main preferences file is `files/logdate.preferences_pb`, rather than
a file beneath `datastore/`.

| Content | Encrypted cloud backup | Direct transfer |
| --- | --- | --- |
| Main and legacy preferences | Included | Included |
| Journal database and SQLite sidecars | Included | Included |
| Database passphrase recovery | Included | Included |
| Phrase-derived or account-bound identity recovery and independent media keys | Included | Included |
| Committed originals and accepted camera drafts | Excluded | Included |
| Incomplete archive recovery records | Included | Included |
| Retained source archives for incomplete recovery | Excluded | Included |
| Keystore-bound preferences, vendor installation state, background jobs, caches, and staging | Excluded | Excluded |

Cloud backup requires client-side encryption for the portable recovery material. Android's 25 MB
per-app cloud quota applies to the selected data as a whole: exceeding it prevents a new backup;
the platform does not selectively drop the largest files. LogDate Cloud and user-created archives
provide separate recovery paths. The app cannot infer a recent successful Android backup from a
local save or a successful LogDate Cloud synchronization. Backup settings explain these limits
and offer archive export without requiring an account. A native quota callback records the last
reported failure in non-backed-up storage; the UI displays that failed attempt independently of
LogDate Cloud status.

Before emitting a cloud backup, the native agent checks that a live journal has a committed
portable passphrase and that the passphrase opens and passes integrity validation on the live
SQLCipher database, including the supported legacy cipher settings. A missing, malformed, or
mismatched key aborts the backup before Android receives journal bytes. With no database yet,
an absent key is valid, but a malformed orphan key is not: it would block a fresh restore.
Device transfer uses its separately verified selected snapshot.

## Background account recovery

The restricted backup callback retains the original delivery, generation, server/privacy digest,
owner, and binding before publishing completion. It schedules a persisted `JobScheduler` job with
network connectivity and exponential backoff. It never resolves Koin or performs credential exchange.
Ordinary unlocked startup and boot/package-replacement reconciliation reschedule retained work,
including a callback interrupted before its completion receipt. A running job can finish only the
reserved ticket for that native generation. Network loss, dependency initialization, and expired
per-attempt privacy epochs preserve a still-authorized durable request for a fresh attempt.

The account choice has a separate durable receipt in `noBackupFilesDir`. It is written before the
main inbox and updated before owner preferences or secure session publication. Persisted system
job extras can repair an unreadable inbox after validating the original configuration and owner.
A matching receipt or exact interrupted-commit stamp preserves the accepted account; conflicting
accounts or changed privacy/server settings cannot authorize reconstruction. Unreadable bytes are
retained. Recovery metadata contains no credential, access token, or refresh token.

Publication takes the native delivery gate, the session identity mutex, and the shared durable
privacy-epoch fence, in that order. HTTP and provider calls stay outside those locks. Owner admission
and secure persistence run inside the guarded publication. Use the shared API rather than saving
a session and validating afterward:

```kotlin
vault.installNativeRestoreSessionIfCurrent(guard, session, privacyEpoch, capturedEpoch) {
    admitOriginalRestoreOwner()
}
```

Every first write of local journal content must use the native delivery gate as well. Existing
Room opening already observes that gate. Draft stores, camera captures, canonical media, audio
recordings, archives, and watch reception admit their destination directory before writing bytes:

```kotlin
admitNativeContent(destinationDirectory)
writeCapture(destinationDirectory)
```

Synchronous platform entrypoints use `admitNativeContentBlocking` for directory admission only.
Never hold the gate through recording, media copying, network work, or a whole editor session.
An unbound restore cannot claim a journal after local content appears during authentication.

Host tests cover retained scheduling, callback process death, interrupted writes, account changes,
sign-out, delayed configuration persistence, and first-content races. Managed-device acceptance
must additionally verify persisted worker execution and transfer behavior. Synthetic credential
exchange does not verify Google Restore Credentials, which requires its dedicated provider setup.
The account-bound transfer profile can additionally execute the actual persisted system job,
verify retry after process restart, and check the journal while no provider credential is available:

```sh
python3 tests/e2e/managed-native-restore-acceptance.py \
  --output /absolute/path/to/new-account-job-evidence \
  --after-transfer account-job -- --identity account
```

The Android probe checks the effective service/receiver manifest and exact persisted request.
The host requires an increased failure counter for this registered service after each forced
execution, preserves the same request and pending account ticket, and runs the populated journal
oracle after restart. These checks verify system scheduling and retry with retained journal data. The system failure
counter does not identify the retry reason or establish successful Google credential restoration.

## Restore lifecycle and keys

`NativeBackupAgent` uses file-based Auto Backup (`fullBackupOnly`). Its restore-completion callback
atomically records content availability before normal startup can create an empty Room database. It removes
the transferred `device.id` field without removing canonical owner or user preferences. Content
and account recovery have separate markers in `noBackupFilesDir`; acknowledging local content
does not acknowledge unresolved authentication. An unreadable delivery marker cannot authorize
automatic archive import over retained content.

Android runs the agent in restricted backup mode, where the framework instantiates the base
`Application` instead of LogDate's manifest-declared `LogdateApplication` and does not initialize
the app's content providers. Keep backup callbacks independent of Koin, Room, WorkManager,
location services, and same-app providers. Do not cast or depend on the `Application` object;
persist a small durable marker and let ordinary startup reconcile it after restore completes.

Ordinary startup must also wait for the first user unlock after boot. Credential-encrypted
storage and WorkManager are unavailable before that unlock, even when instrumentation or a
platform callback has already created the application process. Both the manifest Application
and the retained base-Application startup path await `UserManager.isUserUnlocked()` before
initializing Koin or opening the journal. Register for `ACTION_USER_UNLOCKED`, recheck the
platform state after registration to close the missed-broadcast race, and unregister when the
wait completes or is canceled. An unlock broadcast alone cannot authorize storage access.

Journal readiness waits for completed-recording salvage, database/key/privacy validation, and required
native media quarantine. Device observers and background scheduling start once in the application
scope after that validation succeeds; constructing optional service dependencies must not hold the
journal on its loading screen. A failed content-open attempt cannot start those observers. Location
foreground services register their own notification channel before promotion, including service-only
entry and launches racing with background initialization.

On the ARM64 API 34 managed image, verify external location and motion grants with:

```sh
python3 tests/e2e/managed-permission-acceptance.py --output /absolute/path/to/new-permission-evidence
```

The driver starts Gradle, selects only its new managed emulator, and grants each permission after
the matching fixture nonce requests it. It requires one passing native case and retains logs and
results. Startup timeout diagnostics include thread frames without local values. If observation
fails while Gradle remains alive, inspect the saved process handle before launching another run.

The installation device ID also lives in non-backed-up storage. An ordinary upgrade retains its
previous ID; a replacement installation creates a new one even if preference cleanup is interrupted.
Archive workers carry the native delivery ID and authenticated account/origin. Only completion of
that delivery can acknowledge missing content, and stale work cannot acknowledge a later delivery.
A temporarily unavailable captured session leaves archive work and its source pending for retry,
both before opening and after reading the archive. A different authenticated owner or an obsolete
delivery remains stale and cannot import or acknowledge the pending journal.
Pending sign-in recovery can retry when the app resumes or networking reconnects.

Archive retries preserve drafts with newer or equal saved modification times. Recovery reads and
writes propagate storage failures, retain archive timestamps, and stay bound to the account and
server selected at the start. Conditional writes preserve later editor saves;
duplicate records track their captured snapshots, and invalid draft IDs are skipped. A retry
repairs a missing upload request after an interrupted persistence/enqueue boundary without
replacing a later pending mutation. Successful note, journal, and membership writes survive a later
archive failure; recovery never deletes an accepted original as failure cleanup. Draft writes also
survive later failures, and captured snapshots cannot replace a later edit.
Per-entry application receipts commit with Room rows or the draft DataStore value, so replay cannot
recreate an entry deleted after import even after that deletion has synced.

Android Keystore ciphertext is not portable. The database passphrase has a recoverable copy under
`db_recovery/`. Phrase-derived content identities retain their recovery phrase under
`identity_recovery/`; phrase-less account identities retain a versioned, account-bound key there.
Independent media keys are selected by identity binding and retained alongside its recovery material.
Explicit phrase recovery selects the corresponding media key while preserving earlier identity
records. Root changes, media selection, and backfill share a lock so startup cannot bind a
previously cached media key to the recovered identity.
Account backup version 2 stores the root and independent media key together, so interruption
between primary key writes leaves both recoverable. Version 1 account backups remain readable.
Phrase recovery preserves a paired media key before replacing the account backup representation.
Startup, sync provisioning, and the full-backup callback backfill existing installations without
minting new identities. These copies use app-private filesystem protection, Android file-based encryption, and the
additional native cloud encryption requirement described above.

A retained database without a valid passphrase enters database recovery instead of generating a
replacement key. A malformed identity backup or an account-key backup belonging to another retained
owner cannot mint an unrelated replacement identity. Account-key backups validate a checksum
over the owner and key to detect corruption. Conflicting database key copies preserve both for
recovery. Backfill validates retained identity and media copies against the primary keys before
writing, and preserves conflicting or malformed material. Key files are replaced atomically, and incomplete replacements are excluded from backup.

For local database-key recovery, startup tries the primary key, the committed portable copy, and
any complete key left in `passphrase_backup.part` by an interrupted write. It changes neither
portable file until SQLCipher opens the journal. If the pending file supplies the accepted key,
confirmation syncs and atomically promotes that file without truncating its only surviving copy.
Malformed pending material also prevents generation of a replacement key. Pending files remain
excluded from Android backup; this repairs local interruption, not a key omitted from a transfer.

Missing database keys must be recognized through dependency wrappers in `ApplicationStartupGate`.
That surface runs before database-backed navigation and retains the encrypted journal while allowing
another key-open attempt. Archive and account recovery currently depend on ready storage and cannot
be reached through this gate. Adding those actions requires an isolated recovery destination;
passing a different archive or account into the failed journal is not a key-recovery mechanism.
An identity recovery phrase does not recover the independent SQLCipher database key. Never invoke
the storage-reset controller automatically or replace retained content with an empty database.

### Database version compatibility

Production database builders require an explicit migration path in both directions. Never call
Room’s `fallbackToDestructiveMigration(false)` to disable destructive migration: the boolean
controls whether unrelated tables are dropped, and the call enables destructive fallback.
The same applies to `fallbackToDestructiveMigrationOnDowngrade(false)`. Persistent journal
builders omit both APIs. Only a test with deliberately disposable storage may opt in.

Android checks an existing journal’s schema read-only after its current or legacy SQLCipher key
opens it. A newer schema stops startup before Room callbacks or cipher migration can run; a newer
plaintext journal is refused before encryption export touches any migration files. The schema
limit and Room annotation share `DATABASE_VERSION`. Update that constant with schema migrations.
Unknown older versions remain protected by Room’s required migration path.

When a received journal needs a newer app, startup keeps the journal and recovery key and offers
**Update LogDate** before any content-backed navigation. The action opens this installation’s
Play listing, falls back to a browser, and copies the update link if neither is available. Retrying
startup or resetting storage cannot repair an unsupported schema.

`RetainedDatabaseMigrationSafetyJvmTest` checks refusal, supported migration, tombstones,
relationships, and pending work. `NewerJournalRecoveryRuntimeTest` exercises normal app startup
against a synthetic encrypted restore on a fresh Gradle Managed Device, including the update
action and retained key. `LegacyPlaintextMigrationTest` covers newer plaintext and legacy cipher
refusal as well as supported plaintext conversion. Run the native cases together:

```sh
./gradlew :app:android-main:phoneApi34DebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.NewerJournalRecoveryRuntimeTest,app.logdate.client.e2e.LegacyPlaintextMigrationTest \
  -Plogdate.androidTestOrchestrator=false \
  -Pandroid.testInstrumentationRunnerArguments.newerJournalAcceptance=true
```

## Provider credential cleanup

Sign-out records an installation-local cleanup obligation before changing the backed restore
policy or clearing the secure session. Local sign-out precedes best-effort remote logout. A worker
can finish an interrupted local sign-out only while its captured policy decision still applies;
a later explicit login supersedes that decision.

`RestoreCredentialLifecycle` owns provider creation and clearing through one process-wide mutex.
Its durable records retain cleanup separately for each server and track provider-key origins.
An uncertain creation preserves every possible origin until a successful replacement. A newer enrollment supersedes
older global provider deletion, while old-server Android account cleanup can still finish.
Provider calls keep the mutex until the SDK operation finishes, including caller cancellation.
Secure-storage read failures retain work for retry instead of authorizing deletion.

The cleanup scheduler awaits WorkManager persistence and uses a unique name per ticket. Startup
reconciliation repairs interrupted scheduling; worker failures retain their ticket and retry with
backoff. These obligations and WorkManager state are excluded from device backup. The portable
restore policy travels with native backup and independently prevents signed-out account recovery.

Android account creation, token updates, and removal share a mutation mutex. Writers validate the
captured login inside that boundary. Removal validates the sign-out decision there and handles
partial records, including a missing access token. Use `addAccountIfCurrent`,
`updateTokensIfCurrent`, and `removeAccountIfSignedOut` for these operations. Checking identity
before dispatching an Android account operation does not protect its eventual mutation.

Connected Android account repositories receive `RestoreCredentialLifecycle` rather than the raw
SDK manager. The native backup callback uses the raw manager only to retrieve a delivered key;
it must not create or clear provider credentials. Server-scoped repositories retain their existing
no-provider behavior because Android has one restore credential for the application.

## Incomplete media recovery

Before importing content, Android stores a private copy of the archive and a recovery record bound
to the canonical owner and server. Each missing attachment is recorded before its entry is written.
Unavailable references identify the recovery generation, entry, block, and original or preview role.
They keep text and captions uploadable without attempting to read absent bytes. A recovered video
preview cannot stand in for its original.

Recovery runs as separate WorkManager jobs. It first reuses available originals and checkpointed
candidates, then searches retained archives and the current account's Cloud media. Other retained
archives must match the expected bytes by SHA-256; a repeated filename is insufficient. Cloud
recovery refreshes expired credentials and stays bound to the captured owner and server. Temporary
failures retain their checkpoints and retry without requiring another app launch.

Refresh refusal clears a session only through the vault authorization captured before the refresh
request. A later sign-in or server change invalidates that authority, including a sign-in that
installs equal credential values. Missing authority never permits an unconditional clear. Device
revocation is reported only after the conditional clear succeeds.

Recovery observes server configuration, encryption-identity changes, authenticated-session
readiness, and durable media-mapping changes. Scheduling awaits WorkManager's database commit and coalesces changes into one waiting
successor, so a running job cannot discard a later wakeup. Missing or invalid source bytes retain
their checkpoints while awaiting a changed source, key, or session; empty sync passes do not
restart foreground recovery.

Conditional reference patches preserve captions, timestamps, text, block order, and memberships.
Current-editor notes and their original edit versions persist recovered references through the same
`StoredMediaReferences` resolver as the journal. Keeping a device file path in an edit version while
the journal stores a portable reference creates a false editing conflict even when both read the
same bytes. Media-mapping comparisons also use that resolver before comparing reference spellings.
Deleted entries, replaced attachments, and pending deletions take precedence. Notes, journals,
memberships, places, location samples, drafts, and profile decisions have durable application
receipts, including decisions to preserve newer content. Draft recovery admits its sync write
inside a Room transaction before committing preferences; an interrupted Room commit can repair
its upload intent without replaying the draft.

A restarted worker uses its retained source even if the original picker permission or file is gone.
Native cloud backup carries the compact records; direct transfer also carries retained source
archives. A source ZIP is unnecessary for attachment repair once metadata is safely imported.
Explicit cancellation persists before source copying can finish; a paused import can resume from
its retained archive. Source replacement checkpoints unfinished metadata replay across restart.

The result sheet reports background repair quietly. It requests another archive only when the
remaining content needs a source the app cannot recover itself, and retains that action after the
sheet closes. Technical warnings remain in recovery records instead of appearing as raw error
strings. Completing all pending content removes the retained source copy. Manual imports do not
acknowledge native delivery markers.

## Account recovery

Credential Manager Restore Credentials creates a cloud-backed restore key where possible and
retries local-only creation specifically when cloud end-to-end encryption is unavailable. Restored credentials are requested independently
of onboarding preferences, and transient errors remain retryable. Account recovery does not
automatically import an archive over an intact transferred database.

Before explicit passkey or Google authentication publishes its session, enrollment is marked
pending for the authentication request's original server and owner. An already signed-in Android
installation without an enrollment record initializes one at startup. Temporary storage failures
remain inside the retry loop; resolved startup records do not rotate keys on every launch. Accounts
opened for a secondary server do not enroll over the connected installation's provider credential.

After background sign-in, a durable, origin-bound marker keeps replacement restore-key registration
pending until the provider and server confirm it. While signed in, transient failures retry with
backoff from 30 seconds to 15 minutes; a process restart resumes from the marker. Sign-out or a
server/account change stops that session's retry loop. Expired access tokens refresh through the
captured server, and conditional vault writes prevent an old refresh from replacing a new login.
Foreground registration and background replacement share a lock because the provider holds one
restore credential per app.

Each pending replacement has its own generation. Completion clears only the generation it
registered, under the same lock used to persist a new obligation; another restore for the same
account therefore remains pending. Retries capture current API configuration for their original
server, allowing corrected discovery or API-version settings to take effect without signing in again.

Foreground restore also records the replacement obligation before publishing the recovered
session. Its requests use a pinned server configuration, and vault installation compares an
initialized origin and revision so a later login or sign-out wins. A server switch during a
completed secure write leaves that session only in its issuing server's vault. Cancellation
retains pending renewal, and an explicit trigger resumes registration even when the recovered
session matches the existing session. Platform account creation and registration failures retry
without delaying access to the recovered journal.

Restore assertions have a passive authentication contract. The dedicated server restore verifier
validates origin, RP, challenge, registered-key signature, ownership, and replay without requiring
a fresh user-presence or biometric ceremony. Ordinary passkey verification is a separate path.
Android retains restore keys after authentication until they are explicitly cleared. The server
therefore consumes the challenge and atomically updates the verified active credential's counter;
it keeps the key available for a fresh signed challenge after a dropped response, account-preparation
outage, or interrupted session write. A repeated challenge remains invalid. Positive counter updates
use compare-and-set; zero-counter credentials can authenticate concurrently with distinct challenges.

Registration adds the verified key without invalidating keys held by other installations. Failed
storage never reports successful enrollment or revokes an existing key. Post-restore enrollment
remains enabled for compatibility with older servers that consumed keys; it is not required to make
the corrected server key usable again. Retained historical server keys require explicit revocation;
local sign-out clears the provider credential rather than revoking every device's keys.

Sign-out persists a server-scoped authorization decision in the backed-up main preferences before
writing the secure session tombstone. A replacement device therefore retains sign-out intent even
though Keystore-bound session records are excluded from transfer. Missing decisions preserve
compatibility with older backups; malformed decisions and storage-read failures cannot authorize
restoration. This protects snapshots containing the decision; it cannot change older provider backups.
Provider cleanup remains locally queued until it succeeds or newer enrollment supersedes it.

Explicit authentication captures an opaque guard before its ceremony and enables restoration only
after securely persisting the resulting session. Installation compares the durable session record
and authorization generation under a shared vault lock, so later sign-out or login wins across
foreground, native-callback, and server-pinned vaults. An interrupted authorization write keeps
persisted tokens inaccessible until explicit authentication repairs it. Refresh captures one
origin-bound session, conditionally replaces or clears only that session, and cannot enable
restoration. Server-scoped accounts use the same guarded storage rather than ordinary vault writes.

Platform token repair retains the authorization captured before refresh or renewal begins. Each
Android account mutation validates that authorization inside the shared platform lock, then reads
the exact current origin and tokens after validation finishes. A refresh must not overwrite a newer
access token merely because the account and refresh token still match. Missing-account fallback uses
the same predicate. Explicit sign-in invalidates earlier work even when the resulting tokens match.
Active operations retain an opaque authorization independently of the vault's bounded installation
guard cache; eviction must not silently stop pending enrollment. Authorization remains scoped to its
issuing vault and origin, and never grants permission to install a session using an expired guard.

## Missing originals on the replacement device

Android provider IDs and shared-storage paths identify content on the source device. A matching
`content://media/...` ID on the destination may name unrelated bytes. Native restore records an
independent quarantine marker in `no_backup`. Normal startup rewrites restored external identities
before indexing, background work, or content exposure; the backup callback does not open Room.
The marker remains pending until notes, covers, and every retained draft scope have been processed.
Damaged scope values are retained privately, and complete draft records are salvaged without
blocking valid journals. Audio reference changes retain their transcripts in the same transaction.
Draft migration preserves ownership, timestamps, and archive application receipts, including while
signed out.

`logdate-unavailable://native/` retains the source reference for scoped mapping lookup. Never open
that source identity as a recovery fallback. Reuse an owned private original or download the mapped
original. Outbound pending edits resolve the mapped remote URL; an unresolved marker stays local.
A preview does not count as its original. Download validation checks the wire length before
legacy plaintext acceptance or authenticated decryption.

Recovery uses `MediaManager.isRecoveredOriginal` to read the complete private stream with bounded
buffers and verify its advertised length. Canonical `objects/sha256` files must also match the
digest encoded in their path. A nonempty truncated file or same-length corruption remains missing
and is downloaded again; the canonical store replaces the damaged object atomically. Legacy
references have no expected digest, so their check establishes complete readability and length.
The shared helper runs file operations on the platform IO dispatcher, including stream opening
and closing, so initial sign-in can await verification without blocking Main.

The Android photo-library manager must expose `ConditionalMediaSaver` as well as `MediaManager`.
Kotlin interface delegation forwards only the declared interface, so wrapping a guarded store can
otherwise make native recovery fail after downloading the original. Forward the original commit
predicate to the store's atomic save boundary; an earlier identity check cannot guard a later write.
Host regressions must exercise the manager returned by production DI, including an account change
during persistence, rather than testing only the underlying file store.

Local originals and retained archive files repair without media-library or notification permission.
Availability requires an owned file that passes the shared integrity check, rather than a successful
provider metadata query. Network downloads obey the current data policy and account-scoped explicit
mobile-data consent. A deferred download retains its recovery state; an allowed-network transition
wakes recovery automatically. It does not require another sign-in or a manual retry.

Android network policy follows the default network's callback capabilities. Synchronous connectivity
getters can still describe an older network inside a callback. Data Saver broadcasts refresh their
restriction state without replacing those capabilities, and callbacks from a superseded network
cannot overwrite the new default's state. Recovery remains deferred while the current policy
disallows its download; an allowed policy does not guarantee a reachable server, so failed
connections must retain the operation for retry.

A successful full worker acknowledges media readiness only when both the download inbox and the
current owner's upload queue have drained. A metered pass may finish successfully with original
uploads deferred; it must not consume the Wi-Fi recovery wake waiting behind that worker. Queue
read failures cannot establish completion, and account/server checks follow the final queue read.

Download scheduling distinguishes retained rows, runnable recovery, and failed attempts held by
backoff. Network-policy pauses do not fail an otherwise successful sync. Successful inbox pages
continue in a bounded group in the same worker. Unfinished work at that bound returns to durable
retry without acknowledging completion; a pending flag can include held uploads and must never
cause an unbounded loop. Test this with location uploads held in backoff as well as media pages.
After connectivity returns, released failures are ready work rather than evidence of a new failure.
Policy-paused rows become runnable when both the current policy allows media and their delay has
been released or elapsed. Retained rows that require a newer app still prevent media-completion
proof. Keep these distinctions in the aggregate DAO query and test them through full sync, including
more than one inbox page and a reopened Room database.

The missing-media network oracle separates host configuration from app callback delivery. A fresh
private nonce identifies each metered/Data Saver case. After the deferred-download marker, the host
disables Data Saver, verifies its state and the unmetered override, then acknowledges the change
through the app's private directory. The device allows a bounded host handoff and then retains its
45-second policy-Flow deadline. It never refreshes the provider through synchronous getters to make
the assertion pass. Failure evidence includes host step timings and a device snapshot of the active
transport, metering, restriction and last observed policy, without network identifiers.
The host reads back the staged acknowledgment before publishing it atomically. The device can
consume the published file immediately without racing a second host read.

Use `managed-native-restore-acceptance.py --mode network-policy --output <new-private-directory>`
for a focused reproduction under the same two-driver custody checks. This route tests policy-driven
original recovery; it does not perform a native transfer or establish Google-provider acceptance.

Restored entries retain their complete text. Optional enrichment counts the combined input before
concatenating it, and day previews allocate only their displayed range. Oversized entries must not
exhaust the replacement device's heap during timeline startup.

The publication profile also seeds synthetic access and refresh tokens in Android's separate
AccountManager store on the source. It reads them through the production account manager, then
requires both the OS account and its tokens to be absent on the replacement and after restart.
Source cleanup removes only that fixture's exact account and must succeed before acceptance is
written. This verifies package-backup exclusion; Google account delivery and Setup Wizard account
migration require their own provider acceptance.

## Replacement-device permissions and widget choices

The managed `--mode widget` route binds a real Fixed Memory widget to the synthetic source's entry
before native backup. Android delivers that backup twice before the replacement app opens. The
source allocator advances beyond both restore deliveries so replacement registration must exercise
a nonidentity ID remap. This route uses the system restore lifecycle: a single-package `bmgr`
restore does not finish Android's widget restore. Before each system restore, the controller inspects
the actual local transport dataset and requires exactly the synthetic LogDate app payload plus
package-manager metadata. Other app payloads, links, special files and unsafe archive paths fail
the run before restore begins. Ordinary native-transfer scenarios keep their package-specific scope.
The destination must retain exactly one registered widget and its chosen entry and shape after
duplicate delivery. The test does not inject provider callbacks or modify destination choices.
The production worker and Glance implementation must render the chosen entry inside an actual
`AppWidgetHostView`; a real tap must open that entry's viewer. A cold restart must retain both the
registered ID and the working tap. Screenshots preserve the rendered widgets and opened entries.

The test host temporarily adopts `BIND_APPWIDGET` only for platform binding and drops it before
refresh, rendering or interaction. It does not substitute preview RemoteViews, fake repositories or
a hand-built launch intent. Both source and destination host allocations are cleaned up. This tests
app restore callbacks and interaction; launcher migration during Android setup remains a separate
Google-provider acceptance route.

Restored tracking settings preserve the user's intent independently of the replacement device's
grants. Resume reads the current saved setting before reconciling permissions, so a recently saved
pause takes precedence over an older debounced observation. An unchanged resume does not replace
periodic work. Foreground capture can retry a launch denied before the activity became visible.

A later motion-permission grant must register activity transitions in an already running capture
service. Keep an existing location subscription rather than starting a second one. Media-library
and notification grants are separate from access to private recovered originals. Both reminder paths
check destination notification permission, the app-wide switch, and channel/group blocking before
recording a reminder as shown. A blocked reminder remains eligible for a later evaluation.

Placed-widget choices live in `shared_prefs/logdate_widget_instances.xml`. Native cloud and device
transfer include that specific file; launcher restoration remaps old widget IDs to replacement IDs.
The selected entry, recall filters, photo shape, and prompt preference belong to the placed widget.

The canonical widget receivers run in a lightweight `:widget_ingress` process. Application startup
must skip DI, WorkManager, and journal initialization there. Ingress retains validated lifecycle
events in an installation-local inbox before reading optional source metadata, then enqueues a
main-process JobService. API 34 persists queued work; earlier versions reconcile the file inbox on
boot and startup. All settings and Glance state mutations belong to the main process. Glance dispatch
uses a real private broadcast to a registered instance of the same receiver class so its canonical
provider identity and asynchronous broadcast lifetime remain valid.

The backup agent retains raw widget preferences by native delivery generation. Pending, observed,
prepared, delivered, captured, and omitted payloads have distinct meanings. Observation persists
before input is read. Complete incoming bytes are bounded to 1 MiB, synced, and read back in a
prepared snapshot before Android can change the destination. Truncated input never reaches the
platform copier. Destination bytes must match the complete retained input before a delivery receipt
can commit.

If copying or its receipt fails, completion replays the prepared original without requiring Android
to redeliver the file. Replay calls the framework restore method at the original destination, retaining
its eligibility checks, then verifies exact bytes before recording delivery. Another failed receipt
keeps the prepared original for the next completion attempt. Replay is limited to the current active
generation. Its scratch input file is serialized by the snapshot lock and bounded to one copy.
Duplicates drain their input and repair from the original prepared payload. Captures remain immutable.

Before accepting completion, the agent persists an immutable manifest of enrolled and delivered files.
A completion retry retains the active generation even if manifest creation was interrupted. It recovers
the delivered set from successful captures, per-file receipts, or repaired prepared input, preserving database delivery receipts
from that session. Empty recreated callback memory cannot establish omission. An existing final manifest
remains authoritative. File callbacks settle before duplicate completion detection, completion callbacks
run serially, and the session closes before admitting a new file.

Foreground reads use the completed generation's manifest to retry failed capture or omission writes.
An omitted file never reads destination defaults. Captured historical bytes remain available, while
an unfinished historical snapshot cannot recapture another delivery's XML. The manifest survives content
acknowledgement. Snapshot file locks are released before recording detector completion.
`NativePreferenceDeliveryRecoveryTest` exercises manifest-write failure on the same and recreated agent,
pre-copy retention, truncated input and retry, prepared replay after recreation, another receipt failure
during replay, duplicate delivery, omission retry, and historical generation reads in the managed storage follow-up. Host tests cover the persistence decisions; the
native cases exercise the platform file-copy and snapshot APIs.
The isolated native fixture uses test-APK metadata without include/exclude rules. Keep assertions on
the complete destination bytes after replay and preservation of the destination after truncated
input. A receipt-write obstruction must be nonempty: AtomicFile.openRead removes an empty .new
directory before the next write. Verify the retained prepared record survives the failure before
removing only the isolated obstruction and recreating completion.

Lifecycle events retain the corresponding generation and copy its immutable choices before applying
a remap. New explicit choices win over delayed mappings, including overlapping IDs and ABA edits.
Automatic defaults are not explicit choices. Settings writes drain accepted pending repairs first;
deletion checks current bindings before changing preferences or dispatching Glance cleanup. An event
is acknowledged only after its settings decision is durable and its runtime callback completes.

Source IDs and destination IDs belong to different installations. A matching integer must not erase
an independently live local widget or copy its unrelated restoration lineage. Cleanup removes obsolete
source keys while retaining live local choices. Remote-source deletion provenance remains authoritative
through ID reuse, duplicate delivery, and process recreation, including deletion events that have not
yet been compacted. An accepted different source may replace a removed destination, after retaining
the deleted source's provenance. Identity mappings also record their source. Missing or null remote
provenance in older metadata is treated conservatively. Seed raw restored preferences in fixtures;
calling a settings save records a new explicit user choice and tests a different contract.

Completed native deliveries from versions without preference retention do not require new metadata.
The job awaits WorkManager's refresh/cancellation persistence before dispatch and requires a matching
event ID in the private ordered-broadcast result. Glance's own exception handling cannot acknowledge
app scheduling. All widget configuration screens use the same durable refresh path.
New lifecycle jobs request expedited execution where supported, with a regular-job fallback
when expedited quota is exhausted. Reuse an existing job's exact metadata to avoid restarting
running work. Re-read pending state after a rejected enqueue: the observed job may have
completed in between, so its earlier existence cannot suppress the regular fallback.

Widget setup retains an unfinished save instead of closing after a storage or scheduling failure.
The selected entry, filters, or shape remain in the form while automatic retries use a bounded
backoff. A storage action opens Android's storage management; returning requires fresh app-lock
authorization before private content or writes resume. Saved instance state contains only validated
control values and identifiers, never entry text or media. Editing the form invalidates the previous
submission, and a canceled write cannot complete a newer selection.

A setup save succeeds only after both its settings and refresh handoff are accepted. Persisting an
inbox record without an accepted scheduler wake keeps setup pending. Retries and recreation reuse
one serialized event ID with an immutable refresh payload; current provider IDs are resolved at
delivery rather than captured on every retry. Keep the inbox record when a wake is rejected. Do not
create a new lifecycle event for each retry or finish the activity merely because settings were
written. The shared message banner supplies recovery text and the storage action, including at large
text sizes; configuration screens must not add independent error cards or transient failure toasts.

Dynamic shortcuts are republished from current journal state after normal startup; a retained
WorkManager request is not their recovery source. Static capture shortcuts keep stable IDs and
use the variant's literal application ID, generated from one template. A string-resource reference
in `android:targetPackage` is not resolved by Android's shortcut intent parser. The opt-in
`RestoredShortcutTargetsRuntimeTest` reads the installed platform shortcut records and checks that
every capture intent targets and resolves within the current application without launching capture.
It also republishes a draft shortcut twice and checks the platform retains one target with the
original draft ID. A refused dynamic-shortcut publication remains retryable work; rate limiting must
not acknowledge a completed refresh. Cancellation propagates so a superseded refresh cannot finish
as a successful publication.

## Emulator acceptance

Worker tests use real `androidx.work.Data`: `Data.EMPTY` for ordinary requests and
`NativeRestoreWorkState.bind` for delivery- and owner-bound recovery. Relaxed data mocks can return
synthetic strings for absent inputs, making valid recovery look obsolete. Assert network calls,
archive handoff, and retained state as well as worker results: success can mean stale work was
correctly skipped. Stale-delivery and changed-owner tests assert that no backup listing occurs.

Recovery note lookups use nullable DAO reads: absence returns null, while cancellation and storage
errors propagate to retry. Never convert a failed read into a missing entry, because repair may then
discard its checkpoint. Check coroutine activity after reading an attachment target before dropping
pending recovery. Replacement archives enter through the locked worker flow and a new source
generation; do not overwrite a retained ZIP while a worker may be reading it.

Transcription waits while a recording carries an unavailable-original reference. Existing text,
segments, and timestamps remain intact. Once recovery rebinds the recording and transcript to the
readable private original, unfinished transcription resumes with recovery priority; a valid final
document is reused. Regression tests must rebind both rows, as the recovery transaction does,
rather than relying on a temporary URI mismatch to trigger work.

`tests/e2e/native-missing-media-acceptance.py` runs on explicitly selected synthetic emulators,
including ones with existing fixtures. Supply `--synthetic-data`, one or more `--emulator` serials,
`--app-apk`, `--test-apk`, and a new `--output` directory. For an isolated debug package, set
`LOGDATE_NATIVE_ACCEPTANCE_PACKAGE` to the same package ID used to build both APKs. It verifies provider-ID collisions,
private originals with denied media/notification permissions, metered and Data Saver transitions,
and retained-archive repair through WorkManager and process restart. It restores network overrides
and each original permission state. It never clears or uninstalls the app.

`tests/e2e/native-permission-acceptance.py` uses the same explicit emulator and synthetic-data
arguments. It exercises externally granted location and motion permission through the resumed
activity, notification delivery and widget ID remapping. Notification cases require the host
sequence opt-in and test denied/granted permission through the real coordinator. The blocked-channel
case uses a disposable channel to check the OS gate, then checks the worker retains reminder history
when that gate refuses delivery. It never mutes a production channel.

The recording fixture stages a completed AAC file, stops the app process, and launches the real
activity. The host observes publication and checks that no recording service is running before
starting verification instrumentation. Verification checks exact bytes and offline playback, then
removes only its uniquely named recording. Normal startup and the foreground fallback for a
restore-retained Application run the same salvage step before database access.

Current-editor drafts retain failed recording blocks as pending metadata. Missing staging bytes
must not make autosave discard the block's caption, transcript, position, creation time, or recovery
paths. Reload reconstructs a stopped recovery attempt, never an active recorder, and can accept a
committed fallback if it becomes available. This preserves the draft without including temporary
recording containers in Android backup.

The location test waits for SDK acceptance using a temporary in-memory
Napier sink; a non-null pending request is insufficient, and production log redaction stays intact.
A private checkpoint retains the original tracking preferences
until cleanup succeeds. Cleanup restores permissions and radio settings and reads them back.

`tests/e2e/native-backup-acceptance.py` exercises Android's local debug transport on two fresh,
rootable Google APIs emulators. It invokes the source backup service, moves that transport's
payload, and invokes the destination restore service. The harness requires fresh installations and preserves existing app data.
When an emulator already contains LogDate data, build an isolated application ID with
`-Plogdate.applicationId=studio.hypertext.logdate.transfer` and set
`LOGDATE_NATIVE_ACCEPTANCE_PACKAGE=studio.hypertext.logdate.transfer.debug`; both packages remain
installed side by side, so the transfer fixture does not need to clear the regular app.

The fixture includes two journals, shared entry relationships, original image/audio/video content,
a rich draft, and a deleted entry. Destination assertions cover owner and content-key continuity,
a distinct device ID, original bytes, offline decoding/playback, draft structure, and an offline
edit retained across process restart. The `legacy-account` identity scenario starts with enrolled
secure keys but no portable key backup, and checks that the native backup callback prepares them.
An independently encrypted media payload must decrypt on the replacement phone. The harness
requires explicit `emulator-NNNN` serials and
refuses a destination that already has the debug application installed. The optional
`--exercise-quota` scenario creates an oversized synthetic source entry and checks Android's
quota rejection and persisted callback record. Optional summarization and entity extraction
reject inputs beyond their processing budget before constructing prompts or normalizing cache
keys. Moment inference checks the budget before serializing a day and uses bounded preview
fragments for large entries; the source entries remain intact.

The optional `--exercise-archive-recovery` scenario also transfers an incomplete archive import.
It preserves edited captions, reordered draft blocks, text, memberships, and a video preview while
two originals are unavailable. The destination retries from the retained archive after the picker
file is gone, accepts a replacement archive with different media paths, and verifies recovered
bytes and offline playback across another process restart. Test IDs travel as instrumentation
arguments; the application's recovery record and source ZIP must arrive through native backup.

## Android platform testing methods

Native recovery fixtures must isolate `filesDir`, database paths, `noBackupFilesDir`, and
`applicationInfo.dataDir` wherever recovery reads those roots. Do not reload the application's
shared `databaseModule` value into a test Koin graph: its singleton factories can reuse the live
application's key provider, and closing the test graph can clear those shared factories. Construct
the real key provider with fixture storage when testing key recovery, and use explicit encrypted
files for SQLCipher checks. Test worker construction against the live graph separately.

Follow Android's [backup and restore testing guide](https://developer.android.com/identity/data/testingbackup)
when choosing what each emulator run proves. Cloud backup can be exercised on one device; a
comprehensive device-to-device transfer uses a source device and a separate, fresh target.

The two-emulator `native-backup-acceptance.py` scenario calls Android's Backup Manager on the
source and target with the encrypted local transport. Transfer scenarios select Android's
device-transfer rules; the credentials-only scenario selects cloud-backup rules, requiring the
live journal recovery key while excluding selected transfer bundles and original media. The
harness verifies native callbacks, selected files, and destination recovery across two app
installations. LocalTransport does not prove Google's provider delivery or the setup wizard.

For complete cloud-selected journal recovery, run the managed controller in transfer mode with
`-- --exercise-cloud-backup`. It selects encrypted cloud rules on both fresh managed devices and
restores the unfiltered payload, including the database, actual owner/preferences file, and portable
identity, media and database recovery keys. The source archive and destination must exclude original
media, retained journal custody and transfer-only bundles. The destination verifies journal content,
drafts, deletion state, identity and key continuity, duplicate delivery, and an offline edit across a
cold app restart. Missing originals remain unavailable. The local transport's encryption flag tests
rule selection; it does not establish Google's encryption, retention or account delivery.

`native-d2d-acceptance.py` follows the guide's repeatable single-device GMS `D2dTransport` test-mode
flow on an API 31+ Google Play emulator. It backs up a synthetic journal, changes its title and a
committed recording after backup, then runs `bmgr restore` and verifies that both saved versions
return. The repository
forbids uninstalling or clearing LogDate app data, so the harness restores explicitly instead of
using the guide's uninstall/reinstall step. Passing it proves the GMS test transport, callbacks, and
restored content; it does not prove source-to-fresh-target setup-wizard transfer. That broader
acceptance remains a separate device-transfer test and must use a new target.
Transport failures retain bounded backup-service Logcat output in a private diagnostic file.
A package-manager transport failure occurs before LogDate's agent and cannot establish an app
backup failure or success. The diagnostic capture must preserve the failed acceptance result even
when Logcat is unavailable.
The in-place simulation retains the installation ID stored outside backup. The two-emulator
scenario requires a distinct ID on the fresh destination and verifies recovery into a new Keystore.

After process death, a read-only probe must find the saved offline edit before the content
verifier runs. A verifier that creates a missing edit cannot establish persistence.

`tests/e2e/native-restore-work-restart-acceptance.py` first checks account and server
transitions against real WorkManager on an offline emulator. The current account receives
an immediate recovery request even when the previous account's request is queued or running.
A previous request's failure or retry must not block the new account. Token refresh keeps
the same schedule, and a server change remains observable even with identical credentials.
Signing out and back into the same account also replaces a running pre-authentication request;
its later failure or retry cannot consume the new authenticated wake.
Scheduling acknowledges a target only after its WorkManager operations persist. Transient
storage failures retry without requiring another authentication or network event. A newer account
or policy cancels obsolete retries and reconciles partially applied periodic constraints.

The same harness verifies an unresolved native delivery
and its queued Cloud restore request across process death. Its fixture requires a non-null
delivery ID before scheduling; restart and repeated enqueue must retain that ID and the same
WorkManager request. A generic request without a native delivery cannot satisfy this check.

`tests/e2e/native-device-enrollment-acceptance.py` combines a populated two-emulator native
transfer with the real account/device API and an isolated PostgreSQL server. Build
`:integration:server-client-e2e:writeAndroidHistoryHarnessClasspath`, then use the managed controller
with the generated classpath, a private database environment file, and a new output directory.
The controller builds and pins the matching app and instrumentation APKs before starting its two
fresh emulator instances.
Enrollment has a bounded 30-minute native phase shared with its local server; the driver also
covers boot, requested follow-ups, and teardown, with a 90-minute upper limit:

```sh
LOGDATE_NATIVE_ACCEPTANCE_PACKAGE=studio.hypertext.logdate.debug \
python3 tests/e2e/managed-native-restore-acceptance.py --mode enrollment \
  --classpath integration/server-client-e2e/build/android-history-harness.classpath \
  --fixture-environment /private/temporary/enrollment-environment.json \
  --output /private/temporary/native-enrollment-result
```

The environment file must have mode `0600` and contain `DATABASE_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `SERVER_ENCRYPTION_KEY`, and `LOGDATE_BLOB_STORAGE_DIR`. Use a disposable,
loopback-only PostgreSQL database and a Base64-encoded random 32-byte server key. The harness
creates its own synthetic passkey account. It supplies credentials through standard input to a
private `no_backup` fixture; credentials never appear in instrumentation arguments. The destination
receives that fixture only after native transfer, duplicate delivery, readable offline content,
and process-restart checks pass. The fixture selects its local server through real discovery,
then normal Activity startup and WorkManager must enroll the replacement and upload the retained
draft and offline-created entry. Before authentication, the fixture continues the restored draft
with a real repository save while the emulator is offline. Its block IDs, media, selected journals,
and creation time remain intact. The server copy must decrypt to that new edit after ordinary
startup sync. The test does not call registration or sync on the destination.
A second process restart must execute new sync work and retain the same replacement identity
and session, with exactly two installations in the real server registry.
Both startup stages require a new successful WorkManager request. Server snapshot verification
uses the raw API and the production decoders and cipher; the durable sync inbox contains only
unapplied work and can correctly be empty after successful synchronization.
The native transfer check waits for the retained text entry to appear on the Timeline, rather
than accepting navigation over loading placeholders. Local entries publish independently of
optional recommendations; a stalled or failed lookup cannot hide recovered entries or stop
later local edits from appearing.

The enrollment session also runs `NativeAutomaticMediaWorkerProbeTest` against the real server.
The host saves the managed device's cellular setting and temporarily disables mobile data so
Wi-Fi metering affects the active transport. After radio cycling it explicitly reconnects the
emulator's open `AndroidWifi` fixture; enabling the radio does not establish a connection.
Each probe observes active validated Wi-Fi before
establishing its worker baseline; `wifi_on` alone is insufficient. Cleanup restores cellular even
if other environment restoration fails and verifies the original setting.
It quarantines one synthetic note's mapping while preserving its original file, defers the resulting
download under metered and Data Saver policies, and verifies automatic repair after each policy
lifts. An incomplete running sync must receive a persisted successor before ordinary retry backoff
can satisfy the test. A concurrent full sync can instead prove completion for the same account and
server only after the retained operation is applied and exact original bytes are recovered.
A separate process-death case retains the download operation and recovers on normal
startup. Every case requires exact original bytes, unchanged timestamps, an applied inbox operation,
and no pending download before accepting completion; the host never requests recovery a second time.

The source fixture uploads the committed photo and current-editor attachment through the real media
API before backup. This establishes the server-original precondition without draining its deliberately
pending deletion and edits. It does not establish automatic source backup scheduling.

`NativeDeferredOriginalUploadProbeTest` retains a new original upload while the server holds the
worker after its upload phase. With no periodic writer running, the host lifts the network policy.
The pending operation must remain unchanged until release, and a distinct immediate request must
persist before normal retry backoff. That request must drain the operation; downloading from the
real server must return the exact original bytes without another backup request from the fixture.

`NativeMediaReauthenticationProbeTest` retains a fourth download across authentication loss.
The local server revokes the destination's actual refresh token and supplies a correctly signed,
already expired access token. The ordinary client must encounter the refusal and durably clear
its session; the fixture never clears it directly. The same owner, server, inbox operation,
unavailable reference, timestamps and encryption identity must survive a signed-out cold restart.
The server observes the client's refresh attempt, and binary-request counts must stay unchanged
through cold startup, assertion exchange, and the real authentication response.

Sign-in uses `DefaultPasskeyAccountRepository` and the production HTTP routes. Its sole ceremony
substitute is the same retained P-256 test authenticator that created the synthetic account. The
normal session observers must resume the original operation and recover its exact bytes without a
second backup request. `NativeOriginalMediaProbe` shares those byte, metadata, operation and
completion checks with the network-policy cases. Google restore-key enrollment is excluded from
this fixture; it does not establish platform Credential Manager or provider acceptance.

`NativeRetainedDraftShortcutProbeTest` seeds the current editor's `EntryDraftRepository` before
backup, separately from the legacy rich-draft fixture. On the replacement device it checks text,
media bytes, memberships, and draft identity, publishes the real dynamic shortcut twice, then opens
the platform-published intent and verifies the saved draft appears in the editor. This exercises
retained draft storage and shortcut interaction; explicit publication in this probe does not prove
automatic startup publication.

The current editor and legacy archive editor use different draft stores. Native recovery must
cover both: `NativeEntryDraftMediaQuarantine` fences current `EntryDraft` snapshots, and
`NativeEntryDraftMediaRecovery` joins the ordinary draft sync phase. It retains an owner-scoped
`MEDIA_NATIVE_ENTRY_DRAFT` inbox operation until every committed attachment is readable. Missing
recording staging files keep their pending metadata; they are not downloadable originals.
A draft's `editBase.original` is an independent recovery candidate: its current attachment may be
readable while the original-version reference is stale. Repair only that reference and preserve the
original fields, scope, and copy identity so legitimate edits do not become false conflicts.

Use `EntryDraftRecoveryRepository` for media-only compare-and-set repairs. It preserves creation
and update times, selected journals, captions, editing provenance, and pending recording metadata.
The Android store checks the current snapshot and recovery authority inside its serialized write.
Its JSON patch changes known fields while preserving unfamiliar restored metadata, including nested
records. A cancelled write acknowledgement can follow a successful commit: reconcile the affected
cached draft from storage before retrying, preserve the original failure, and never treat a failed
reread as a deleted draft. A rejected disk comparison also refreshes the cache.

`NativeCurrentDraftRecoveryProbeTest` adds a cold-delivery simulation after the enrolled native
transfer. It places unrelated bytes behind an accessible provider URI, attaches that URI to the
current draft with a matching real-server media mapping, and arms native quarantine. Offline
startup must quarantine the reference while preserving the unrelated provider file. A subsequent
online startup must recover the correct original through normal sync and apply the draft's inbox
operation. Opening the actual shortcut and restarting again must retain the unfinished recording's
caption, transcript, timestamp, position, and recovery reference without starting capture.
A separate base-only stage retains a readable replacement attachment while recovering the original
version reference. It publishes through `AddNoteUseCase` and verifies that a later genuine conflict
is still refused. The provider-collision fixture is created on the destination; it does not prove
setup-wizard delivery of the collision or Google credentials.

Archive recovery must await `LogDateConfigDataSource.awaitConfigurationLoaded()` before
capturing its owner/origin or creating a recovery record. The repository initially exposes the
default Cloud origin; treating that initial value as a saved choice can bind accepted work to
the wrong server before draft storage resolves its persisted scope. Use
`currentArchiveRecoveryScope` for Android archive workers and scheduling. Never repair an older
record by guessing that a different selected origin owns its pending work.

Archive download, import, retained-source replay, attachment repair, and delayed repair retries
require Android's `StorageNotLow` constraint. Cloud downloads also require a connected network;
local archive work must remain eligible offline. Use `archiveRecoveryConstraints` for archive
import and media requests so resume paths do not bypass the storage policy. Write failures during
an attempt still retain its source and checkpoint for retry.

`tests/e2e/native-storage-recovery-acceptance.py` accepts explicit emulator, app APK, test APK,
private output directory, and `--synthetic-data` arguments. It interrupts metadata import after
accepting a private archive, forces Android's low-storage state, and checks the source bytes and
queued request across separate app processes. The final probe starts while storage is still low;
the host releases the storage condition only after that probe is ready. The original WorkManager
request must finish and restore the draft without another app launch. The harness resets and
reads back Android's original storage state even when a stage fails. This validates platform
deferral and interrupted-write custody, rather than physically filling the emulator's filesystem.

`tests/e2e/native-recovery-ui-acceptance.py` checks production restore sheets at phone and tablet
display sizes and with Android's system font scale at 200%. Each sheet's text-layout density must
match the requested font scale: a `LocalDensity` override around the page does not establish the
scale of a separate native modal window. The probe verifies partial-state copy, media counts,
reachable accessibility actions, and one callback per tap, and captures each state's content and
action placement for inspection. It restores and reads back the original display and font settings.
Compose semantics and action checks do not establish a TalkBack navigation session.

The independent server verifier checks that the uploaded draft carries the replacement device ID
and its original creation time. The historical entry keeps its creation time and encrypted capture
metadata; the destination decrypts that metadata through the production content API and checks
its original values. Content records expose capture platform and activity rather than a per-entry
device UUID, so draft attribution and historical capture continuity have separate assertions.
Successful cleanup conditionally clears only the fixture sessions and restores both radio states.

The native acceptance workflow runs this scenario after the transport and permission checks.
Publish only its explicit acceptance receipt and selected instrumentation logs. Keep the database
environment, server control directory, credential fixture, and native transport payload private.
This scenario does not establish Google credential delivery, setup-wizard acceptance, independent
provider-issued replacement sessions, or enrollment key rotation. Account/device registration
stores installation metadata; peer authentication keys have their own fresh-handshake checks.

The local transport exercises file selection, native callbacks, and application recovery using
test encryption with the rules for each scenario. Google credential delivery and setup transfer
require the corresponding providers and a dedicated test account. Cross-platform transfer requires a
matching Android/iOS format and receiving implementation before configuration can be enabled.
The receiving implementation is the native app in `logdate-apple`, bundle
`studio.hypertext.apps.logdate`, team `T95VDD3A4W`. An iOS application target and AppMigrationKit
extension are required, together with the App Store Connect OS data transfer mapping.
AppMigrationKit does not run in the macOS application.
For a database-free Android source, the iOS sender emits no payload only when no transferable
content exists. Profile or canonical-owner preferences first acquire the ordinary encrypted journal
snapshot; unrepresented settings abort before any file is emitted. The iOS path remains disabled by
default until source coverage and native Setup Assistant delivery are accepted.

The two-emulator harness also supports `--exercise-data-only` to prove a signed-in synthetic source
restores journal and media while leaving the destination signed out. `--exercise-credentials-only`
models a partial encrypted cloud delivery in which portable recovery keys arrive without journal or
media data. `--exercise-missing-keys` retains encrypted journal
data without replacing unreadable keys, `--exercise-partial-media` to omit one original while
checking the remaining media and recoverable source reference, and `--exercise-duplicate-restore`
to replay Android restore before app launch and compare durable state. `--exercise-missing-privacy`
retains the journal, keys, canonical owner, and drafts while removing onboarding and security
metadata. It verifies protected startup, retry, Activity recreation, and refusal to back up an
unknown policy, then delivers the complete source backup through Android and checks journal access.
Run filtered payload scenarios
on a fresh destination because they intentionally alter the transported fixture. These modes use
synthetic app data and do not establish Google Restore Credentials or setup-transfer provider
acceptance.

The missing-key probe rejects replacement identity and media keys, including newly written portable
backups, after failed startup and Activity recreation. Credentials-only delivery must remain
`CONTENT_MISSING` even after the empty destination database opens.

### Manual account recovery on a fresh managed pair

`managed-native-restore-acceptance.py --mode manual-account` owns two new Gradle Managed Device
guests and a disposable local passkey account. It first builds the application to derive its
Android signing origin, starts the loopback server, embeds the server-issued synthetic fixture in
the instrumentation APK, and pins both APKs before boot. The source writes an encrypted journal
and original media. The destination signs into that account through Credential Manager while its
journal keys remain absent, enters a different checksum-valid phrase, and must stay on the form
without revealing content. Entering the source phrase then restores the text and media. This
focused mode does not claim Android setup transfer or Google Restore Credentials acceptance.

Build `:integration:server-client-e2e:writeAndroidHistoryHarnessClasspath`. Provide a mode-`0600`
JSON file with `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `SERVER_ENCRYPTION_KEY`, and
`LOGDATE_BLOB_STORAGE_DIR`, using a disposable loopback PostgreSQL database and a Base64-encoded
random 32-byte server key. Give each run a new private output directory; the controller rejects
stale fixture directories and verifies
the final pinned APK signer against the origin accepted by the server:

```sh
python3 tests/e2e/managed-native-restore-acceptance.py --mode manual-account \
  --classpath integration/server-client-e2e/build/android-history-harness.classpath \
  --fixture-environment /private/temporary/manual-recovery-environment.json \
  --output /private/temporary/manual-recovery-result
```

The result has separate inner journey and outer managed-lifecycle receipts. The fixture and test
APK contain disposable credentials; keep the output private during diagnosis, then remove those
assets after retiring the local database and role. A successful local journey does not replace a
test of the real Google provider with a dedicated account.

### Released-version upgrade and native restore

`native-released-upgrade-acceptance.py` verifies two histories in one fresh managed pair: an
installed upgrade of a released encrypted journal, and native delivery of that released app's
backup into the candidate. The released agent must write the transport before installing the
candidate. Compile `NativeReleasedUpgradeProbeTest` against each version separately; changing
production code in the released checkout or running its instrumentation against a different
client invalidates the comparison.

Use `managed-native-restore-acceptance.py --mode upgrade --fixture-environment <private-json>
--output <new-private-directory>`. The mode accepts no follow-ups because their initial APK may
have a lower version than the upgraded app. Both managed installations must be empty and unlaunched.
Their initial APK version must allow an ordinary ascending installation of the baseline and
candidate; the harness never clears app data or enables downgrades.

The mode-`0600` fixture JSON selects `baselineVersion` 205, `baselineSchema` 55, a higher
`candidateVersion`, and `candidateSchema` 57. Its `baselineApp`, `baselineProbe`, and `candidateApp`
objects each contain an absolute `path` and the reviewed APK's `sha256`. Preserve the original
released-source APK and build receipt before adding instrumentation. The controller supplies the
current candidate probe, and the child pins and rechecks the additional APKs before installation.
These debug-signed builds establish released-source compatibility; they do not establish a
Play-signed installation or Google-provider acceptance.

Read-only comparisons validate the portable identity owner and fingerprint, and any installed
identity key. Signed-out startup need not install that identity before opening the local journal;
authenticated sync installs it before encryption. The later restoration/decryption probe exercises
the actual key-installation API. Comparisons retain encryption keys, journal owner, creation/capture timestamps,
deletions, outbox operation identities, and both editor draft stores. Media-path normalization
permits equivalent local and portable references without ignoring draft metadata. The source
keeps its device ID; the destination gets a distinct ID that survives restart. Unfinished recording
metadata survives while cache recording bytes stay outside native backup. Compare all retained
state before existing verification helpers create an offline edit, then check original media bytes,
media-key decryption, and the normal Timeline launch. Duplicate delivery and a cold restart must
pass independently on the destination.

### Privacy metadata during partial delivery

A restored encryption key proves that LogDate can read the journal; it does not establish the
original app-lock policy. The shared database factory validates restored privacy metadata before
returning a database to any consumer, including widgets and background work. Retained journal or
draft content without that metadata enters the protected restore screen. Retry and process restart
retain the original database and key. A later complete delivery supplies the missing policy and
allows normal startup. An empty installation or credentials-only delivery can still onboard.

Backup preparation writes explicit onboarding and security defaults for an established source or a
proven empty installation. It refuses to manufacture defaults for an unverified restored journal.
Existing explicit policies remain unchanged, including biometric protection and unfinished
onboarding. Legacy onboarding metadata without a security field retains its established default.
Native preferences are staged privately, parsed, and committed through the same canonical DataStore
used by application consumers. A live reader therefore observes a transferred biometric policy
instead of a cached unlocked policy. A delivery receipt also prevents old destination preferences
from authorizing transferred content when the source preferences never arrive.
Completed native-delivery fingerprints keep validation active after restore work is acknowledged,
so a second launch cannot bypass the check.

### Editing retained entries

Stored entries use an explicit edit path. Opening an entry captures `EntryEditBase` with its
original note and account scope. Complete draft snapshots preserve that base alongside the edited
content, so reopening a draft retains its editing intent. Ordinary note creation continues to reject
an existing ID with different content; import and recovery callers must not turn that guard into an
unconditional update.

Keep the base captured at opening until the edit is published. Fetching a new expected note at
Save time would let an old editor silently replace another editor's work. Publication compares the
loaded content with the current record and uses the repository's scoped transaction. Capture
provenance, location, and current sync history come from retained storage rather than the editor's
partial presentation model.

```kotlin
// Capture once when opening, and carry this same value through the draft.
val editBase = fetchEntry.forEditing(noteId) ?: return
// After editing:
saveEntry(notes, selectedJournalIds, activeDraftId, editBase = editBase)
```

A concurrent edit or deletion preserves the edited version in Drafts. The editor's existing snackbar
offers Save separately; it never replaces the newer version or recreates the deleted ID. A separate
version receives its own ID, which every publication attempt commits to the draft before writing any
note. A failed draft write therefore remains retryable without producing another copy after process
death. Even a retry finding an identical copy must confirm its record and account inside the scoped
transaction before acknowledging the draft.

`NativeRestoredEntryEditingTest` exercises production storage and editor state, including oversized
text, current-day entries, reopened drafts, concurrent edits, deletion, and failed copy persistence.
For a rendered walkthrough, retain an entry beyond the AI input budget through Backup Manager,
open and edit it, then compare its full stored text and ID. Android accessibility text is capped,
so an accessibility snapshot alone cannot establish preservation of an oversized entry.

### Secure storage availability during recovery

Android dependency graphs and native backup callbacks construct secure storage through
`createAndroidSecureStorage(context)`. This defers encrypted-preference initialization until an
operation needs it, allowing the database provider to try retained portable keys when the local
vault cannot open. Direct reads and writes still fail explicitly: unavailable identity material
must never be treated as an empty vault or authorize a replacement key.

```kotlin
single<SecureStorage> { createAndroidSecureStorage(androidContext()) }
```

Identity observers are cold and wait without publishing missing-key values while initialization
is unavailable. A later successful storage operation reconnects them; they do not poll, terminate
optional watch/archive observers, or manufacture a signed-out identity event. Preference creation
and cache hydration share one sanitized failure boundary. Initialization errors retain neither
private keyset details nor their underlying exception causes, and unreadable preference files
remain unchanged. Only after SQLCipher accepts a retained key may its primary copy be refreshed;
a failed refresh leaves the readable journal available for a later retry.

### Retained-key failure acceptance

`NativeRetainedKeyFailureTest` requires a verified emulator, an unprivileged synthetic debug app,
and `retainedKeyFailures=true`. Its database, portable material, and original media live in an
isolated fixture. A separate prefixed vault exercises real encrypted-keyset initialization failure
through an isolated dependency graph while preserving its original file. Missing, malformed, truncated, mismatched, and file-permission-denied recovery
material must leave the encrypted journal and originals intact. A valid portable key must recover
an invalid or unreadable primary, and a failed primary commit must remain recoverable when the
provider is recreated. Capture diagnostics without disclosing the key or private storage errors.
The fixture restores file permissions before cleanup and never changes the application's keys.
`NativeKeyPersistenceRestartProbeTest` uses the same isolated storage with a unique `keyRestartId`
and `keyRestartStage=seed`, followed by stopping the synthetic app process and a `verify` invocation.
Verification requires a different process ID and opens the unchanged encrypted journal using the
only valid key retained in an interrupted write, before committing that key. Acceptance rejects
both ignored tests and assumption failures; JUnit's `OK` summary alone does not prove execution.

### Reminder schedules on the replacement installation

Reconciliation and immediate evaluation share a scheduler lock and read current persisted reminder
preferences before accepting work. Repeated startup retains an active evaluation. A late fixed-alarm
callback respects its morning/evening switch and the active reminder mode; shared reminder consent
gates moment signals. Cancellation waits for WorkManager persistence before another enqueue can
enter the gate. Ordinary storage failures retry reconciliation and the pending signal without another
preference change or input event; turning reminders off cancels those retries.

Legacy immediate and periodic workers also share a delivery lock across eligibility, notification
posting, and history persistence. A successful post finishes writing its suppression history even
if the worker is canceled. Unreadable history prevents delivery; failed persistence remains retryable.
Pending suppression is shared across reminder modes and retains the original delivery time, using
the same timestamp precision as persisted history so a failed acknowledgement cannot count twice.
The concurrent native fixture uses a private on-disk history store, reloads it after closing the
original store, and checks one actual notification and one post call. It grants notification access
only on its disposable managed installation. These checks cover concurrent workers and coroutine
cancellation; killing the process between the OS post and history commit is a separate boundary.

The managed startup job in `.github/workflows/native-restore-acceptance.yml` selects the notification,
shortcut, and low-storage fixtures explicitly and rejects skipped or missing methods. The notification
fixture's additional `notificationDedupeAcceptance` opt-in isolates its permission grant from the
denied-permission probes. `managedAcceptedArchiveWaitsForStorageThenResumes` checks actual platform
storage transitions, retained archive bytes and request identity, and automatic restoration in one
process. The cold-process acceptance harness additionally tests process termination between stages.
Storage-monitor commands queue their state update; wait for both forced and observed levels before
continuing, and fail if the requested state never appears.

Broadcast receivers release their pending result after asynchronous scheduling, including when the
application scope was already cancelled. `RestoredReminderSchedulingRuntimeTest` runs on a Gradle
Managed Device and checks actual AlarmManager registrations, unique periodic work, both reminder
modes, and disabled callbacks. It restores the original preferences after checking those states.

`RestoredAlarmDeliveryRuntimeTest` retrieves the morning PendingIntent registered by the production
scheduler and brings its trigger forward with AlarmManager. The actual receiver must create exactly
one uniquely named evaluation request. It does not call the receiver or worker directly, grant exact
alarm access, or replace WorkManager with a test implementation. This fresh fixture keeps foreground
observers unstarted so they cannot replace the accelerated trigger. It observes request identity beyond
the receiver's bounded scheduling window. The cold-start fixture separately covers normal foreground
reconstruction. Cleanup restores reminder preferences and their normal schedules.

### Exact managed recovery cases

Named case contracts in `tests/e2e/native-*-cases.json` define the key/session/identity, completed
recording, and alarm-delivery groups. The workflow uses the key contract to select classes and
`native-instrumentation-results.py` to validate the resulting XML. Every required method executes
once; unexpected cases, duplicates, skips, failures, and suite setup errors fail verification.
The harness tests compare contracts with their Kotlin declarations so adding cases cannot silently
leave an obsolete test-count assertion behind.

```sh
classes="$(python3 tests/e2e/native-instrumentation-results.py --expected-cases tests/e2e/native-key-recovery-cases.json --print-classes)"
./gradlew :app:android-main:phoneApi34DebugAndroidTest \
  "-Plogdate.androidTestClass=$classes" -Plogdate.androidTestOrchestrator=false
python3 tests/e2e/native-instrumentation-results.py \
  --managed-results app/android-main/build/outputs/androidTest-results/managedDevice/debug/phoneApi34 \
  --expected-cases tests/e2e/native-key-recovery-cases.json
```

### Completed recording recovery across app processes

`CompletedRecordingColdStartTest` uses two ordered Orchestrator stages without clearing application
data. The first durably stages completed audio and its completion marker beside an unfinished
container. The second requires a different process and launches the real MainActivity. Startup
must publish byte-identical completed audio, remove its consumed staging marker, leave unfinished
container bytes staged, and permit muted offline playback without starting AudioRecordingService.
The fixture restores the exact original security preference and retains its completed recording.
This checks local recording interruption; native transfer excludes temporary recording files.

```sh
./gradlew :app:android-main:phoneApi34DebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.CompletedRecordingColdStartTest \
  -Plogdate.androidTestOrchestrator=true \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false \
  -Pandroid.testInstrumentationRunnerArguments.recordingColdStartAcceptance=true
python3 tests/e2e/native-instrumentation-results.py \
  --managed-results app/android-main/build/outputs/androidTest-results/managedDevice/debug/phoneApi34 \
  --expected-cases tests/e2e/native-recording-recovery-cases.json
```

### Reminder recovery across app processes

`RestoredReminderColdStartTest` adds six ordered stages on a fresh Gradle Managed Device.
Android Test Orchestrator starts a distinct process for each stage while preserving application data.
The fixture creates a journal with an explicit unlocked privacy policy, records synthetic native
delivery state, and launches the real `MainActivity`. It does not call the reminder scheduler directly.
Enabled legacy and moment modes must reconstruct their actual registrations; disabled modes must
cancel the exact delayed work and future alarms seeded by the fixture. Every stage checks the retained
journal and key. The final stage restores and reads back original reminder preferences. Missing stages,
reused processes, skipped tests, and lost receipt state fail acceptance.

```bash
./gradlew :app:android-main:phoneApi34DebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.RestoredReminderColdStartTest \
  -Plogdate.androidTestOrchestrator=true \
  -Pandroid.testInstrumentationRunnerArguments.restoreAlarmColdStartAcceptance=true \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false
```

This verifies foreground reconciliation across process death. It does not exercise Android's backup
transport, reboot delivery, notification posting, or Google credentials; use their respective acceptance
paths for those behaviors. Never enable Orchestrator's application-data clearing for retained-journal tests.

### Recovery accessibility on an emulator

Verify recovery with TalkBack touch exploration enabled, using forward/backward navigation and
double-tap activation of the retry action. At 200% text, allow TalkBack to scroll the guidance
before expecting focus to reach the action. Wait for the recovery state to appear before sending
gestures. Android shell `input` injection bypasses the input-reader gesture path; native emulator
multitouch events exercise TalkBack's recognizer. Capture accessibility focus/click events and verify
the retained journal before and after retry. After a database has opened, compare journal content
and integrity because SQLite index maintenance can change its encrypted file bytes.

The managed TalkBack harness checks seven recovery scenes with forward/backward navigation and
double-tap activation. Three archive scenes use production sheets with synthetic missing-original,
resumable-import, and preview states and callback counters. Four startup scenes use an actual
encrypted journal: retry with its key missing, retry after synthetic delivery of its original portable
key, and app-lock security setup before and after leaving and returning to the app. Canceling Android
security enrollment must keep the journal locked. Private journal text and titles must remain absent
from every app-owned accessibility window, including content descriptions.

The harness requires a fresh evidence directory, an installed Android NDK, and an ARM64 API 34 image
with TalkBack and `su` support. Its input driver selects only a newly started, expected Gradle Managed
Device and validates its private launch receipt, console port, and live Gradle/QEMU birth identities
before every device command. Template boots are ignored until the current fixture is ready. Each run
uses a fresh Gradle daemon and SDK configuration, and runs a small NDK-built input helper
that writes native kernel touch events with bounded gesture timing. Shell `input` and direct
recovery-node activation are not substitutes for this check. System Back/Home events use current
uptime timestamps; zero-timestamp key events are stale and may be dropped by Android.

```bash
python3 tests/e2e/managed-talkback-acceptance.py --font-scale 1.0 --output /tmp/recovery-talkback-normal
python3 tests/e2e/managed-talkback-acceptance.py --font-scale 2.0 --output /tmp/recovery-talkback-large
```

The fixture checks the Activity configuration and Compose density before accepting the requested
font scale. It preserves and restores the accessibility-service, font, Bluetooth, and notification
permission settings it touches. Only the known initial Accessibility Suite notification prompt and
Bluetooth crash dialog are handled during setup; unexpected covering windows fail with a diagnostic
receipt. Archive navigation invokes fixture callbacks without executing an import. Startup recovery
verifies journal contents and original-key retention through normal application initialization. The
late key delivery is synthetic; Google-provider delivery and worker restart require separate fixtures.
The managed device retains the accepted journal and keys; cleanup restores the original preferences
and closes the Activity without deleting journal data or recovery material.
If a host observer fails while Gradle is still running, inspect the saved child-process handle before
starting another run; an observer failure does not mean the managed-device task has stopped.

The modern callback fixture constructs a real framework `FullRestoreDataInput` from a private
file descriptor. Its constructor is hidden from the SDK, so the API 36.1 instrumentation command
uses `--no-hidden-api-checks` in a nested instrumentation launched by the separate managed test
driver. The driver attests APK bytes, checks Android's full SDK value, and requires every named
case in `native-modern-callback-cases.json`. The host also verifies the single outer managed test. This keeps the callback test independent
of MockK's JVMTI agent and the runtime temporary directory, which may be unavailable on a fresh
emulator. Production callback handling uses the public callback and accessors.

Native file restoration accepts modern inputs only when the iOS transfer flag is absent and
`contentVersion` is empty. Unsupported inputs fail before reading their descriptor or replacing
journal files, key backups, preferences, or recovery receipts. A rejection marker in `noBackupFilesDir`
prevents a recreated agent, legacy callback, or completion-only callback from treating the rejected
stream as a native restore. A new agent can admit a later explicitly native modern delivery. The
callback gate serializes file admission and completion; the marker covers interrupted writes through
AtomicFile's base, temporary, and backup files. Files accepted before a mixed stream changes formats
cannot be rolled back by this guard. Portable transfer needs its own importer and version contract.
The managed fixture checks retained encrypted contents and key bindings, partial rejection markers,
failed marker writes, rejection across agent recreation, and subsequent native delivery with
transport flags 0, 1, 2, and 3. If storage cannot create any marker, rejection remains local to that
agent; the incoming bytes still remain unread. A recreated agent can retry an existing valid account
recovery, retaining its tickets and installation identity.

`RestoreCredentialCleanupRuntimeTest` checks encrypted-storage recreation, worker retry, and
production dependency injection and scheduling. Its `cleanupSurvivesAppProcessRestart` test accepts
a unique `cleanupProcessNamespace` and `cleanupProcessStage=seed` for the first instrumentation
invocation. After stopping the synthetic emulator app, invoke the test with the same namespace and
`cleanupProcessStage=verify` to complete the retained failed ticket. These preferences are isolated
from the journal and its credentials. `PlatformCredentialCleanupRuntimeTest` checks delayed writers
and removal of partial account records against the Android account implementation.

`NativeProviderCleanupProbeTest` is opt-in with `nativeProviderCleanup=true`. It calls the actual SDK
clear operation twice on an unseeded emulator. A successful probe establishes SDK availability and
idempotency; deleting a delivered Google restore key still requires the dedicated test account.

## Platform references

- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Restore Credentials implementation](https://developer.android.com/identity/sign-in/restore-credentials-implementation-common)
- [BackupAgent API](https://developer.android.com/reference/android/app/backup/BackupAgent)
- [Testing native backup and restore](https://developer.android.com/identity/data/testingbackup)

### Installation credentials and watch links

The canonical session vault owns access and refresh tokens. Account preferences retain only metadata,
including the account DID used for synchronization, and expose it only while the matching secure
session and backend remain current. Account loading follows asynchronous session initialization and
sign-out; copied metadata cannot establish a session.

Legacy token keys from both cloud-account and DataStore-session implementations are removed for all
server origins on the first normal preferences read, before a native full backup, and during restore
completion. Restore cleanup runs even when the old preferences contain no device ID. Do not migrate
those token copies into secure storage or replace this cleanup with a signed-out-screen assertion.

Watch removal choices remain preferences. Successful watch-link records require the local pair key,
which stays outside native backup. If a copied record has no local key, reconciliation starts a new
handshake using the current account and linking policy. Failed repairs retain retry backoff. A pending record is committed before replacing a pair key,
so an interrupted final write cannot leave an old successful record with a different key. Storage
errors and interrupted observation reconnect automatically with capped backoff. Forget and relink
actions invalidate exchanges already in flight; confirmation must match the current link.
Storage read failures must propagate through trust guards instead of becoming an absent key or a
false removal preference. The installation-trust emulator fixtures use their own preferences files
and uniquely named secure keys, without opening or resetting the journal database.

The installation-trust runtime case also completes a fresh cryptographic peer handshake after
rejecting the copied record. It checks the replacement pair key and link identity, secure-store
recreation, idempotent confirmation, and a fresh publication revision while preserving the old
link's counter and source key. Draft upload integration changes the installation ID after sync
initialization and verifies that the encrypted outbound request uses the replacement ID without
rewriting the retained draft's creation time. These checks cover local protocol behavior; actual
Google credential delivery and setup-wizard transfer retain their separate acceptance requirements.

### Publication after a native transfer

`tests/e2e/native-publication-acceptance.py` combines a populated native transfer with replacement
peer trust and the first encrypted backup publication. Supply explicit source and destination
emulator serials, both acceptance APKs, and a new private output directory. Both emulators must be
fresh; `--resume-seeded-source` permits a retained two-journal native fixture as the source.
The destination must still be empty. The harness never clears application data.

The source negotiates a synthetic peer link and reserves its delivery counter. Android transfers
the journal and peer preferences while excluding the source vault. On the replacement, the test
requires a new installation ID, rejects the copied trust, negotiates a fresh link and counter,
and invokes the production backup worker with a synthetic in-memory session and capture transport.
It decrypts the outgoing archive, checks its replacement device ID, retained notes and offline edit,
and verifies the exact size and SHA-256 of the image, audio and video originals. After a process
restart, the same link, key and counter must remain stable, with no session installed in the app.
Source cleanup verifies that the original pair key was unchanged before removing only its own fixture.

The native CI publication lane runs this combined check. Its captured upload proves the app's
export, encryption and attribution behavior; hosted uploads and Google restore delivery require
their own acceptance runs. The BackupManager transport and emulator setup follow
[Android's backup testing guide](https://developer.android.com/identity/data/testingbackup).

The separate data-only lane starts with a signed-in source, transfers its journal and media,
and verifies that the destination remains signed out. Its source-integrity check also confirms
that backup preserves the source session.

### Instrumentation result validation

Native host harnesses share `tests/e2e/native-instrumentation-results.py` for AndroidJUnitRunner
results. A successful summary must contain the expected completed test count; ignored tests,
assumption failures, test failures, runner errors, and crashes fail acceptance even when a success
summary is present. Harnesses preserve private logs before rejecting results. Network-release and
permission-grant acknowledgements remain required for scenarios that depend on them.

The missing-privacy runtime check observes a new startup failure after retry and activity
recreation, verifies its privacy exception, and then checks the recovery guidance. Its bounded
startup wait is separate from the rendered-text wait. The emulator-only `privacyRetryDelayMs`
argument injects up to 25 seconds of retry latency without changing production behavior.

Run `python3 -m unittest discover -s tests/e2e -p 'test_native*.py'` and
`python3 scripts/tests/native-backup/test-native-acceptance-safety.py`, and
`python3 scripts/tests/native-backup/test-native-instrumentation-results.py` when changing these harnesses.
The result regressions cover standalone entry points and the imported D2D and permission helpers,
including Android's LF and CRLF output. Adding a helper requires updating native workflow path
filters so a helper-only change still exercises emulator acceptance.
