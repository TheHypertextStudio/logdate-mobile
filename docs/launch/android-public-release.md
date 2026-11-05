# Android public release acceptance

This is the release contract for the first public Android version. Internal Play distribution is the first acceptance environment; a successful upload, build, or server health response does not close a journey. Record the tested commit, Play version code, device or emulator, date, result, and evidence link for every row. An open row blocks public promotion.

## Product shipped

The product promise is **a home to document, reflect on, and share memories**, as defined in the
[product positioning](../product-info/product-positioning.md). Journaling describes functionality,
not the primary market pitch. This release contributes to that purpose through free, offline-capable
text, audio, photo, and video entries, Library, optional LogDate Cloud sync and backup, clean-device
restore, and local weekly Rewind. Annual Rewind, paid subscriptions, Events, and People are deferred.
Public copy and navigation must match that offer. Describe sharing through the concrete workflows
verified for this release; the broader purpose does not imply shared journals or collaboration
have shipped.

## Acceptance matrix

| Gate | Required observation | Evidence | Status |
| --- | --- | --- | --- |
| First use | Fresh offline install opens the production editor; a saved entry appears on Home after restart. Interrupted editing resumes a durable draft. No account, birthday, bio, permission, or profile name blocks the first entry. | Managed-device recording and local database check | Open |
| Journal media | Create, reopen, edit, delete, export, and restore text, audio, photo, and video entries. Missing media and permission denial show recoverable errors. | Emulator matrix across supported API levels | Open |
| Photo import | Offered after the first entry; only selected photos create dated, durable journal entries with managed media copies. A retry does not duplicate completed entries. | Import test and emulator recording | Open |
| Library | Media from entries appears with working search, detail, empty, loading, and retry states. | Library tests and emulator recording | Open |
| Weekly Rewind | A sparse, normal, and media-heavy week generates locally. Every cited item exists; all emitted Android panels render, refresh works after failure, and reduced motion is respected. No paid or annual prompt appears. | Unit tests and seeded emulator recording | Open |
| Cloud identity and sync | Local use remains available signed out. On two clean emulators, the same identity can sign in, sync all entry types and media, retry offline work, and resolve conflicts without silent data replacement. Wrong-identity content is rejected. | Server-client integration results and two-emulator recording | Open |
| Backup and restore | Cloud backup starts only after recovery phrase verification, and new archives are sealed with the recovery identity before upload. Backup state and failures are visible. A new emulator restores all entry types and managed media after phrase entry; a different phrase cannot apply the archive. Quota and authentication failures are recoverable. | Backup worker test, production-account backup ID, clean-device restore recording | Open |
| Release artifact | Install the internal Play artifact, verify Play App Signing certificate against Digital Asset Links, complete passkey sign-in, and test authenticated production Cloud behavior. Promote this exact version code. | Play Console release record and installed-app recording | Open |
| Public trust | Accessibility, screenshots, privacy and deletion behavior, data declarations, support contact, crash and ANR reporting, and backup operations are reviewed against the shipped behavior. | Review links, operator runbook, monitoring screenshots | Open |
| Physical device | Maintainer completes the same first-use, media, Cloud, restore, and Rewind journeys on at least one supported phone. Agents must not interact with a physical LogDate device. | Maintainer-signed device record | Open |

## Build and rollout

Run affected multiplatform tests, `:integration:server-client-e2e:test`, `ktlintCheck`, Android assembly, screenshot validation, and `:app:android-main:smokeDevicesGroupDebugAndroidTest` on a Gradle Managed Device. Record failures by name; an environmental block remains open until rerun in CI or another valid environment.

Only a candidate with all rows closed, no open data-loss or release-blocking defects, and stable internal crash and backup signals may move to production. Promote the tested Play version code at 5%, then 25%, then 100%, reviewing crash, ANR, backup, authentication, and support signals for at least 24 hours at each stage. Halt promotion on a data-loss, restore, sign-in, or material stability regression. Record the stop decision and recovery action before resuming.

### Play operator procedure

1. Record every matrix result and the internal Play version code. Tag **that exact tested commit** as `android-v<major>.<minor>.<patch>`; the tag itself no longer starts production distribution.
2. Manually run **Publish Android to Play** with `start-5`, the candidate tag, the exact tested internal Play version code, and a link to the completed acceptance record. The workflow verifies that version code belongs to the tag's commit and promotes it to 5% without rebuilding it.
3. After at least 24 hours at 5%, add a timestamped crash, ANR, backup, sign-in, and support review to the record. If healthy, run `advance-25` with the same tag, version code, and updated evidence link. Repeat the review after at least 24 hours at 25%, then run `complete-100` with the same tag and version code.
4. On a release-blocking regression, run `halt` with the same tag, version code, and link to the incident record. This stops new installations of the release; already updated devices keep it. Investigate and document recovery before using `resume-5` or `resume-25` at the halted fraction. A fix requiring a new bundle needs a new internal candidate, acceptance pass, version code, and tag.

The workflow verifies the current production stage and exact version code before each change. Its evidence input records a maintainer decision; it cannot independently prove the contents of the linked acceptance record or the 24-hour observation period.
