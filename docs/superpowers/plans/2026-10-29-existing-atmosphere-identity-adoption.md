# Existing Atmosphere Identity Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt an existing Atmosphere DID for an existing private LogDate account without losing journal data, device access or pending edits.

**Architecture:** Preserve the account UUID and keys. A trusted unlocked device prepares and verifies re-sealed records in operation-private staging; a database transaction changes canonical authority, increments its epoch, records retirement and returns an idempotent receipt. Cloud writes participate in the same account-row fence, while clients retain local edits and reconcile them into the new authority.

**Tech Stack:** Kotlin/Ktor, PostgreSQL/Exposed, the confidential OAuth client, existing passkeys and personal-space codecs, native SwiftUI/CryptoKit/SQLite, Android shared sync.

**Spec:** `docs/superpowers/specs/2026-10-28-existing-atmosphere-identity-adoption-design.md`.

## Global Constraints

- One canonical DID; no permanent old-DID space alias, second account or email/handle association.
- Preserve account UUID, passkeys, account-root keys, encrypted key envelopes and device registrations.
- Existing PDS hosting stays unchanged; private data stays sealed in LogDate Cloud.
- Native SwiftUI first; no KMP iOS edits or builds. Android device acceptance uses Managed Devices only.
- Adoption, publication and recovery-key authorization are separate. No broadening the authentication-only OAuth grant silently.
- The user authorizes completing and delivering non-publication changes without additional chat approval. Keep source/runtime capability gates off until their safety and acceptance requirements are met. AT Protocol publication, including PLC recovery-key submission, still requires explicit approval.
- All builds/tests run through `disk-janitor guard`, with explicit owner/output, 20 GiB internal and 100 GiB external reserves, 64 GiB retention, honest retained/write budgets and Gradle `--no-daemon`.
- Use synthetic fixtures and sanitized errors. Secrets, decrypted journals and recovery phrases never enter logs or source.
- Commits use a message file and the exact atomic `git restore --staged . && git add <paths> && git commit -F <message-file>` chain. Required hooks and review remain in force; no dot-path staging.

## Review Focus

- An old instance passed route authorization before fencing: its eventual store transaction must not mutate the old source after verification (Task 2).
- A second offline device has edits based on an old tombstone or unknown record: retain the edit and expose a conflict, never resurrect or omit it (Tasks 4 and 6).
- A commit succeeded but the response or local scope switch was lost: recover the same receipt and destination without recreating authority (Tasks 3 and 5).
- A DID reservation races signup or cancellation from an old initiator: one winner, no orphan account and no release of the replacement reservation (Task 1).
- An external provider cannot register the optional recovery key: journal adoption remains successful and the UI never reports registration (Task 7).

## File and interface map

New server identity-adoption code belongs in `server/src/main/kotlin/app/logdate/server/identity/adoption/`, with PostgreSQL persistence in `server/.../database/`. Shared wire contracts belong in `shared/model/.../identity/`. Existing `SpaceMigrationService` remains the legacy-storage migration service; reuse its inventory concepts without adding a second meaning to its endpoints.

Native files belong in `logdate-apple/LogDate/SharedCore/IdentityAdoption/`; host integration uses the existing Account views and cloud host. Android uses `client/data/.../account/adoption/` and the existing Account/settings and personal-space sync composition. Keep transport, persistence, conversion and presentation in focused files.

### Task 1: Durable identity epochs, reservations and operation state

**Files:** Create `IdentityAdoptionModels.kt`, `IdentityAdoptionRepository.kt` under the server adoption directory; `database/PostgreSQLIdentityAdoptionRepository.kt`; the next available database migration. Modify `database/Tables.kt`, `auth/AccountModels.kt`, `auth/AccountRepository.kt`, `database/PostgreSQLBlueskyAccountEnrollmentRepository.kt`. Test `server/src/test/kotlin/app/logdate/server/database/IdentityAdoptionRepositoryPostgresTest.kt`.

**Interfaces:** `IdentityAdoptionPhase { PREPARING, FENCED, VERIFIED, COMMITTED, CANCELED }`; `IdentityAdoptionBinding(accountId: Uuid, sourceDid: String, targetDid: String, deviceId: String, sourceEpoch: Long)`; `IdentityAdoptionOperation(id: String, binding: IdentityAdoptionBinding, revision: Long, phase: IdentityAdoptionPhase, leaseExpiresAt: Instant)`; `IdentityAdoptionReceipt(operationId: String, accountId: Uuid, sourceDid: String, targetDid: String, identityEpoch: Long, committedAt: Instant)`. Repository produces `begin(binding, now)`, `get(accountId, operationId)` and `transition(accountId, operationId, expectedRevision, nextPhase, now)` with explicit conflict outcomes. No credentials in these types. Account identity epoch starts at zero; every committed DID change increases it.

- [x] Write real-PG tests `targetReservationExcludesSignup`, `staleCancellationPreservesNewReservation`, `onlyOneActiveOperationPerAccount`, `receiptSurvivesRestart`, and `publishedOrUnknownHistorySourceIsIneligible`. Assert one reservation winner and unchanged losing account/passkey rows, not just returned status.
- [x] Run the focused test class against an isolated disposable cluster; require compiling semantic failures before implementation.
- [x] Implement unique reservations shared with enrollment, immutable retirement history and revision-CAS transitions. Validate publication history using durable hosted-operation evidence and consent; missing evidence is a refusal, never assumed private.
- [x] Run repository tests, server lint/detekt and migration compatibility checks. Verify existing external enrollment without adoption is unchanged.
- [ ] Review and commit the default-off persistence capability with exact paths and a validated message file.

### Task 2: Fence all mutations at the database boundary

**Files:** Create `identity/adoption/AccountIdentityFence.kt`; modify `database/PostgreSQLSpaceStore.kt`, blob upload reservation/finalization and purge persistence, account deletion, legacy migration switching, and account mutation repositories found by a complete mutation census. Test `database/IdentityAdoptionFencePostgresTest.kt`.

**Interfaces:** `IdentityWriteExpectation(accountId: Uuid, identityEpoch: Long, operationId: String? = null)`; transaction-local `AccountIdentityFence.lockAndCheck(expectation): IdentityFenceOutcome`, with outcomes allowed, held, changed or deleted. Acquire the account row before space-state, record, blob and storage-total locks. When an operation also needs the credential coordinator, acquire that outer coordinator before opening the main database transaction. No provider request or device transfer runs inside this transaction.

- [ ] Enumerate write entrypoints and their actual lock order before editing. Include upload completion, purge, deletion, account/identity mutation, retired-DID provisioning and existing storage migration; record the resulting lock map in the architecture reference.
- [ ] Write two-instance latch tests `authorizedBeforeFenceCannotCommitAfterVerification`, `purgeCannotRemoveVerifiedDestinationBlob`, `deletionCannotResurrectStaging` and `fenceExpiryInvalidatesVerification`. Assert actual stored bytes/epochs and bounded completion without deadlock.
- [ ] Run those tests RED, then move currentness checks into the same transactions as their writes. Refuse missing legacy epochs after an account has advanced, while preserving epoch-zero account compatibility.
- [ ] Run focused races plus existing space-store/quota/deletion tests and quality checks GREEN. Keep adoption disabled until every mutation path has the fence.
- [ ] Review and commit the fencing contract independently of the UI.

### Task 3: Protected staging, verification and atomic cutover

**Files:** Create `IdentityAdoptionService.kt`, `IdentityAdoptionStaging.kt`, `IdentityAdoptionManifest.kt`, `routes/IdentityAdoptionRoutes.kt`, matching shared DTOs and OpenAPI docs. Modify routing/DI, descriptor gating, token issuance and retired-identity lifecycle guards. Test `routes/IdentityAdoptionRoutesPostgresTest.kt` and `database/IdentityAdoptionCommitPostgresTest.kt`.

**Interfaces:** Authenticated base `/api/v1/account/identity-adoption`: POST `/begin`, GET `/{operationId}`, POST `/{operationId}/hold`, POST `/{operationId}/verify`, POST `/{operationId}/commit`, DELETE `/{operationId}`. Each mutating body carries expected revision. Dedicated passkey purpose `identity_adoption` and operation-bound OAuth purpose prove the account and target separately. `IdentityAdoptionVerification` binds source epoch/sequence, source and target record/tombstone/blob inventories, manifest digest and initiating device. `commit(accountId, operationId, expectedRevision): IdentityAdoptionReceipt` is idempotent. Staging record/blob APIs require the exact operation and device; ordinary XRPC cannot read staging.

- [ ] Write HTTP/PG tests for wrong account/device/proof purpose, extra/broad provider scope, expired leases, moderated/deleted accounts, conflicting DID, staging read isolation, over-quota copies, forged manifest and lost-response receipt replay. Assert zero provider/token/journal authority from malformed requests.
- [ ] Run RED, then implement state transitions and transactional promotion. Keep source identity current through VERIFIED. Atomically promote destination, set external DID/kind, clear public consent/hosted signing authority, increment epoch, retire source and write receipt. Roll back every effect on failure.
- [ ] Bound an unrenewed hold to 120 seconds and release it on expiry while invalidating verification. Renew with current revision and initiating device; takeover requires explicit confirmation and fresh proofs. Use existing account-bound request signing, or add a device challenge if the current registered device key cannot prove manifest authorship; never treat a device ID string as proof.
- [ ] Run tests GREEN, actual Kotlin client DTO decoding, OpenAPI and descriptor tests. Verify default-off runtime allocates no migration worker or staging authority.
- [ ] Review and commit the protected server capability with its rollout and retention requirements.

### Task 4: Cross-platform conversion and complete inventory verification

**Files:** Native `IdentityAdoption/IdentityAdoptionConverter.swift`, `IdentityAdoptionManifest.swift`; Kotlin `client/sync/.../space/adoption/SpaceIdentityConverter.kt`; corresponding Swift/Kotlin tests and synthetic common fixture data. Reuse `SpaceSealedRecordCodec`, `SealedRecordCodec`, media codecs and existing native retained-record mechanisms.

**Interfaces:** Conversion inputs explicitly name source/target space+author and existing identity/media keys; output retains logical collection/rkey, converted envelope, blob references and per-item source/target verification digest. Manifest version 1 has canonical CBOR encoding, deterministic ordering by collection/rkey and explicit live/tombstone entries; no plaintext is sent to the server. Conversion is resumable per item, with fresh nonces only when producing a new ciphertext revision.

- [ ] Write matching Kotlin/Swift tests `newAuthorityOpensAndOldAuthorityFails`, `preservesUnknownFieldsAndRelationships`, `retainsTombstonesAndConflicts`, `missingMediaBlocksVerification`, and `destinationReadbackDetectsTampering`. Compare canonical manifest bytes across languages; independently reopen all destination envelopes/media.
- [ ] Run RED. Implement a schema-field census for embedded space/author references, preserving arbitrary user text. Preserve an unknown payload losslessly or mark it unsupported and block cutover; never drop it from counts.
- [ ] Reuse LDCE2 ciphertext only after authenticated verification, CID verification and explicit target ownership/reference registration. Capture a complete pending-edit/conflict snapshot and keep subsequent edits pending separately.
- [ ] Run cross-codec and native/Kotlin tests GREEN with synthetic extension fields, Unicode, media, unknown collections, purged cursors and deletions. Verify no journal content enters manifests/logs.
- [ ] Review and commit the isolated conversion capability before host wiring.

### Task 5: Native durable orchestration and existing Account surfaces

**Files:** Native `IdentityAdoptionAPI.swift`, `IdentityAdoptionController.swift`, `IdentityAdoptionStore.swift`, `IdentityAdoptionView.swift` in the adoption directory; existing `ReceivingCloudAccountHost`, `JournalStore`, Account views and corresponding `LogDateTests`/iOS UI tests.

**Interfaces:** `IdentityAdoptionController` consumes the Task 3 contract and Task 4 converter. Public actions are `prepare`, `confirm`, `resume`, `cancel` and `refreshStatus`; passive rendering/restoration never starts OAuth or a passkey prompt. Durable local binding includes operation/account/backend/device/source generation and the unchanged original root. Status exposes preparing, paused, verifying, ready, committed and actionable sanitized failure states.

- [ ] Write tests `lostCommitResponseInstallsMatchingReceipt`, `oldCancelCannotUndoCommit`, `scopeSwitchCrashResumesTarget`, `newLocalEditRemainsPending` and `signedOutControllerCannotInstallAuthority` against the actual host/database adapter.
- [ ] Run RED, then implement prepared local scope, verified download and atomic active-scope switch. Keep the original root/keys. A receipt must match the exact operation and current account before activation; create a fresh sync cursor namespace.
- [ ] Integrate with existing Account identity controls. Show current/target identity, unchanged PDS/private storage, sync pause and permanent hosted-DID retirement before the explicit confirmation. Provide bounded progress and cancel/resume without another signup flow.
- [ ] Run strict Swift lint, portable/host tests and owning iPhone/iPad/Mac builds. Inspect populated normal/dark/large-text/error/restart states and accessibility, using only owned headless/offscreen workflows.
- [ ] Review and commit native capability with default-off local and server gates.

### Task 6: Other-device reconciliation and Android parity

**Files:** Shared account/descriptor/token epoch DTOs; Android `client/data/.../account/adoption/IdentityAdoptionController.kt`; existing `SpaceSync`, Account ViewModel/settings; native sync state and historical archive restore acceptance. Add focused tests in existing account/sync/native test targets.

**Interfaces:** Account refresh returns canonical DID, identity epoch and durable adoption receipt bound to account UUID. Old-epoch sync refuses before mutation with a stable update-required contract. Migration-aware clients retain old scope and pending bases until new-scope verification and conflict reconciliation finish; UUID/device/passkey ownership remains unchanged.

- [ ] Write tests `offlineSecondDeviceKeepsEditAgainstDeletedBase`, `unknownRetainedRecordBlocksLossySwitch`, `legacyClientGetsUpdateRequiredWithoutQueueLoss`, and `historicalBackupCannotReviveRetiredDid`. Assert actual pending/conflict rows and reopened content after restart.
- [ ] Run RED, implement receipt-driven refresh and new cursor scope in both clients, and reconcile pending edits against their recorded source base. Do not infer deletion from an absent local row or mark old pending writes uploaded.
- [ ] Add Android existing-Account controls using the same wire states. Keep unavailable platforms/servers safe and require capability agreement from the originating backend.
- [ ] Run affected units/settings contract and actual Managed Device phone/tablet scenarios. Compare populated renders with native and verify journal continuity, including interruption and device approval.
- [ ] Review and commit compatible-device behavior. Incompatible clients remain explicitly blocked from stale cloud writes.

### Task 7: Optional independently authorized recovery key

**Files:** Native existing recovery-key account surface plus `IdentityAdoption/AdoptedIdentityRecoveryKey.swift`; Kotlin existing recovery-key repository/settings; server provider adapter and descriptor only where supported. Tests use an independent mock PLC/PDS fixture.

**Interfaces:** Separate action after committed adoption, with a fresh explicit identity-management authorization. Prepare public recovery key on device, inspect exact proposed PLC operation, confirm priority and unchanged PDS/handle/repository key, then verify externally after submission. did:web reports method-specific unavailability; decline/failure never changes adoption receipt.

- [ ] Write tests `authenticationScopeCannotAddRecoveryKey`, `providerRefusalPreservesAdoption`, `changedPdsOrRemovedExistingKeyIsRejected`, `directoryReadbackRequiredForSuccess` and `didWebNeverClaimsPlcRegistration`.
- [ ] Run RED, implement only provider-supported narrowly authorized steps and device-side verification. Never ask for credentials in chat or transmit derived private keys/phrase. Any required human provider confirmation remains a visible native handoff.
- [ ] Run GREEN with independent signature/key-order verification. Inspect review/error copy and preserve optionality.
- [ ] Review and commit separately from adoption so this extra authority is independently reviewable.

### Task 8: Retention, deletion, rollout and full acceptance

**Files:** Server adoption maintenance/DI, environment/runbook documentation, native sign-in contract/acceptance docs, integration/server-client-e2e adoption journeys.

**Interfaces:** Retained source and staging objects have explicit operation/account ownership. Uncommitted staging expires after seven days without renewal and is cleaned only after reservation/currentness checks; active or committed destination objects are never candidates. Postcommit source cleanup has no automatic destructive default: require an explicit reviewed policy and recovery coverage. All cleanup goes through Task 2 fencing.

- [ ] Write tests `expiredStagingCleanupCannotDeleteCommittedCopy`, `accountDeletionCleansBothNamespacesWithoutResurrection`, `partialCleanupResumesExactOwnedObjects` and full two-device adoption journeys through interrupted commit and backup restore.
- [ ] Run RED, implement cleanup with byte/reference accounting and lifecycle ownership; keep retention behavior observable without account-content logging.
- [ ] Run server/SDK/integration and affected native/Android checks with exact source evidence. Confirm no source DID discovery/publication, no external repo writes and no private markers leaving LogDate Cloud. Inspect all skips and report their actual boundary.
- [ ] Deliver the reviewed default-off changes through ordinary rebased PRs. Complete compatible staging deployment and signed-app/passkey/provider acceptance before real-account migration and device/journal readback. The native flow retains its informed account confirmation; no additional chat approval is required for non-publication work. Keep real recovery-key registration optional, independently verified and subject to explicit publication approval.
- [ ] Audit every spec requirement and acceptance invariant against current evidence before claiming completion. Source tests and mock providers do not prove the real move.

## Self-review

Tasks 1–3 cover eligibility, proofs, reservations, private staging, transactional fencing, commit/retirement and receipts. Task 4 covers complete record/media conversion; Tasks 5–6 cover native/Android presentation, device epochs, pending edits, restart and backup restoration. Task 7 isolates optional identity authority; Task 8 covers retention/deletion and real rollout boundaries. Each Review Focus case has a named owning test. No local check substitutes for deployment or real-account acceptance.
