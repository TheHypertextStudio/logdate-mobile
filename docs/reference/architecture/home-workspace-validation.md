# Home workspace validation

Validated October 2, 2026. The workspace is the only Home presentation and no flag switches it off; native-map acceptance remains unresolved. This is a presentation migration; it introduces no server or storage migration.

## Automated checks

The affected desktop suites pass for UI, core, journal, library, location timeline, rewind, and timeline. Build logic passes eight architecture-rule tests. The final changed UI, journal, and location suites were rerun after visual corrections. Scoped Kotlin lint, affected-module Detekt, `checkHomeWorkspaceContract`, and `:app:android-main:assembleDebug` pass.

The populated workspace catalog renders and validates 121 screenshots. The consolidated comparisons use those rendered PNGs at matching sizes, rather than independently designed mockups.

Gradle Managed Devices on API 36 phone and API 35 tablet pass detail Back dismissal and map-unavailable browsing/return. Compact 320dp recovery at 200% text passes on both, including entering history, scrolling to Resume recording, and returning to the map. The compact fixture was corrected to supply compact bounds on tablets; that repair changed the test harness only. Native Maps tests explicitly skip without debug configuration.

The follow-up managed-device run passes four scenarios on each target, adding account-menu backup and streak navigation. Each target reports five tests with zero failures/errors and one native-map skip. Runtime account-menu captures advance the Compose clock and wait for drawing before export; immediate captures otherwise miss the popup entrance frame.

## Visual inspection

Inspected populated phone, compact phone, landscape, tablet portrait and landscape, book, tabletop, dark, 200% text, RTL, reduced-motion, recovery, and immediate-detail scenes. Search belongs to the shared app bar above all panels; collections and viewers do not own replacement destination search bars.

Inspection identified and repaired physical RTL displacement, compact search truncation, oversized compact journal covers, and recovery content displacing history controls. Shared physical composition uses absolute top-left coordinates while panel content retains logical RTL layout. Recovery appears in scrollable supporting content; explicit browsing remains available when the map minimum cannot fit.

The follow-up refinement removes streak and dedicated backup controls from the shared header, retaining one account target with a quiet status marker and an on-demand menu. Journal covers grow with local panel width. Medium portrait uses a full-width map and transforming supporting content; wide layouts retain a dominant map and narrower history. Rewind's snapping story-card browsing and next-card action are restored. Inspected regenerated phone, compact, tablet portrait/landscape, dark, RTL, and 200% text comparisons. Rewind preview fixtures explicitly disable entrance motion so their populated content is captured deterministically.

Follow-up desktop verification passes 112 UI, 267 core, 23 journal, and 3 rewind tests, including account-menu navigation, cover sizing, visual/supporting fit, and historical Rewind navigation. Scoped Kotlin lint, affected Detekt, architecture enforcement, debug assembly, and all 121 screenshot validations pass. The final comparison artifacts are in `home-workspace-refined-final` in this task's visualization directory.

The preview map substitutes prove layout only. They do not establish native map availability or interaction correctness.

## Rewind and header refinement

The user rejected simulated stacked covers and exaggerated depth. The current presentation removes stacked edges, perspective, and cover scaling. Ordinary Rewind covers use 1–2dp separation, retain their actual artwork and story metadata, and use a flowing typographic layout when no photo is available. Tests first failed for overly strong elevation, then passed after reducing it. Reduced motion retains static separation. Individual story titles and dates remain; redundant section headings do not.

Shared search now aligns with the leading content edge instead of following a wide-screen destination label; the account action stays at the trailing edge. The desktop geometry regression failed with search at 159dp instead of the 16dp framing inset, then passed after the shared header repair. Timeline's redundant “Your days” heading is removed. The shared primitive and repository guidance own this rule for every destination.

The final focused verification passes 113 UI, 267 core, and eight Rewind desktop tests, scoped Kotlin lint, affected Detekt, architecture enforcement, and debug assembly. All 121 populated workspace renders regenerate and validate without reported test failures. Inspected matching phone/tablet comparisons, compact, portrait, dark, RTL, and 200% text scenes in `workspace-leading-search` in this task's visualization directory. Evidence: `/tmp/workspace-leading-search-green.log` and `/tmp/workspace-header-acceptance.log`.

The Rewind browsing interaction test passes on both Managed Devices with one test and zero failures/errors/skips per target. Runtime captures are not accepted as visual proof: a Digital Wellbeing system ANR obscures the tablet capture, and the phone capture catches an unsettled entrance frame. Rendered layout acceptance and passing interaction assertions must not be presented as completed runtime visual acceptance. These capture limitations and the existing native-map configuration boundary keep the workspace flag disabled.

## Collection and large-screen refinement

The shared shell now uses a 240dp expanded navigation sidebar on standard windows at least 1200dp wide. Intermediate and book layouts retain the rail; phones and tabletop retain bottom navigation. An opened Timeline day uses the 320dp browse panel beside a larger reading focus. Timeline without an opened day is capped at 560dp instead of stretching across the workspace; it does not reserve an empty detail panel. The shared resolver enforces maximum widths in ordinary and hinge-safe composition.

Locations section controls are content-sized and wrap when needed. Inspection caught a half-width control viewport clipping Your places on compact and large-text scenes; the caller now gives the controls the available width, and the shared control can wrap. Your places binds its existing bounded filter to the single shell search field, preserving its query when switching sections. Other contexts retain the existing global search action. Collection panels with already-contained items inherit the canvas instead of painting another surface; reading panels and map-overlaid sheets retain their working surface. Journal filters inherit that background at rest and use the shared raised backdrop only while sticky content overlaps.

Behavior tests first failed for the reading collection width cap, expanded navigation selection, and scoped search lifecycle. The full affected desktop verification passes UI 119, core 267, location timeline 22, journal 23, library 30, and Rewind 8 tests. After final control wrapping, UI and location timeline suites, scoped Kotlin lint, affected Detekt, the architecture check, and debug assembly pass again. Evidence: `/tmp/workspace-collection-green2.log`, `/tmp/workspace-collection-polish.log`, and `/tmp/workspace-collection-fit-green.log`.

All 121 regenerated workspace screenshots validate with zero failures/errors/skips in `/tmp/workspace-collections-screenshot-acceptance.log`. An earlier four-case validation failure reflected the inspected journal-detail filter-background removal; those references were regenerated with the complete catalog. Inspected matching phone/tablet comparisons, opened-day tablet, compact, 200% text, tablet portrait, dark, and RTL renders in `workspace-collections`. The preview map remains a deterministic layout substitute.

The new Places search/section-return interaction passes on phone API 36 and tablet API 35 Managed Devices, one test and zero failures/errors/skips per target, in `/tmp/workspace-collection-final-acceptance.log`. That command's overall exit was initially nonzero for the four screenshot differences above; both device test XML reports independently pass, and the subsequent screenshot-only command passes. These tests establish the search interaction, not native map acceptance. Existing native-map configuration and Rewind runtime-capture limitations remain. Nothing was deployed or committed by this refinement.

## Your day containment and single-row app bar

Locations > Your day retains the shared working panel around its date, visits, journeys, and replay controls in wider layouts. Your places remains a collection of contained items. Phone supporting sheets continue to use the working surface over the map.

The shared app bar keeps search and account actions on one row at every width and font scale. The previous large-text branch placed the account action below search; two compact 320dp/200% tests failed on vertical alignment before that branch was removed. Scoped search hints now have one line with ellipsis. The layout retains natural text height and the account touch target rather than imposing an arbitrary height cap.

The final run passes all 121 UI and 22 location-timeline desktop tests, scoped Kotlin lint, affected Detekt, the workspace architecture check, and debug assembly in `/tmp/workspace-day-appbar-green2.log`. All 121 populated screenshots regenerate and validate with zero failures/errors/skips in `/tmp/workspace-day-appbar-acceptance.log`. Inspected the restored tablet day container and compact/global and large-text/scoped search renders, with refreshed comparison sheets in `workspace-day-appbar`. Preview maps still establish layout only. This refinement adds no new native runtime evidence; the flag and release boundaries remain unchanged.

## Places destination and supporting header

The Home destination is named Places; existing location-history route keys and deep links remain unchanged. Your day and Your places now share a compact dropdown inside the supporting panel header. The separate padded mode row above the map is removed. The shared supporting-sheet header integrates accessible expansion and focused-content actions on phones and contextual actions in both arrangements. Your day retains its working surface.

The panel-header placement test first failed when the new header slot was not rendered, then passed with the integrated header. The affected desktop suites pass UI 122, core 267, and location timeline 22 tests, with scoped Kotlin lint, affected Detekt, the architecture check, and debug assembly in `/tmp/workspace-panel-selector-green.log`. All 134 workspace and human-history screenshots initially validated with zero failures/errors/skips in `/tmp/workspace-places-header-acceptance.log`. The final Places fixture also includes its overflow action so its header spacing matches production.

Inspected the compact phone, 200% text, tablet, and mirrored wide header arrangements and refreshed matching destination comparison sheets in `workspace-places-header`. The map in these previews remains a layout substitute. This refinement was validated locally before mainline integration; the workspace flag remains disabled.

The two focused Managed Device scenarios pass on both phone API 36 and tablet API 35: scoped Places filtering survives section changes, and compact 200% text recovery resumes recording and returns to the map. Each target reports two tests and zero failures/errors/skips in `/tmp/workspace-places-header-recheck2.log`. The initial recovery assertion still sought a text button; it now targets the new icon's accessible description. The initial section test ended without a completed result; the final rerun establishes its passing result. No physical device or native-map acceptance is claimed. Kotlin lint passes for the changed Android test and screenshot fixture. The final nine Places fixtures validate with zero failures/errors/skips in `/tmp/workspace-places-header-fixture-acceptance.log`.

## Release boundaries

GitHub supplies the existing debug Maps configuration for native acceptance. Native-map acceptance remains required before production promotion; it has not passed on the managed targets. Map failure must continue to leave history browsing available.

This validation does not establish field recording reliability or battery life. No physical device was used. Apple presentation changes remain outside this delivery.

The migration landed on published mainline as `baff3f6b802bc793749c4f7e267c2169022f64ab`. Unpublished sync changes and dirty files in other checkouts were preserved. [Play internal publication](https://github.com/WillieCubed/logdate-mobile/actions/runs/37055279106) succeeded with the presentation still gated.

## Deployment preparation

The migration is rebased onto published mainline without including unpublished work from other checkouts. The required push hooks exposed the screenshot source hierarchy mismatch and a legacy Rewind width regression. Workspace scenes now live under `screenshots/audit/workspace`, and Rewind's new width constraint is restricted to the workspace path; the legacy path retains its original modifier order. Existing screenshot organization and desktop render checks define these contracts.

`home-workspace-acceptance.yml` uses the existing debug Firebase and Maps configuration on GitHub to run the native-map and Rewind browsing checks on the managed phone and tablet. It rejects missing configuration and skipped native-map tests, and exports XML and runtime captures. Local Google Cloud reauthentication is unavailable, so that gate runs in CI before activation. The presentation remains gated during this verification.

## Native acceptance harness repair

The [initial configured run](https://github.com/WillieCubed/logdate-mobile/actions/runs/37055279091) completed the phone Rewind interaction, then its standalone instrumentation process crashed loading `androidx.startup.InitializationProvider`. The merged test manifest included an OkHttp initializer while the provider class belongs to the target APK. A test-only manifest override removes this provider from the instrumentation package; application startup remains unchanged. The workflow continues after a target failure so both managed targets can export diagnostic results.

The exported phone Rewind capture is obscured by a System UI ANR and is not accepted as visual proof. The native-map test did not complete, so activation remains blocked pending the repaired run. Desktop CI renders, static analysis, Android release assembly, and server/client integration passed. Unit-test and Android/Wear screenshot runners received simultaneous shutdown signals; the former was restarted. The screenshot log also reported older Wear/adaptive mismatches and missing references before shutdown; those reports are not a passing gate.

The [repaired orchestrated run](https://github.com/WillieCubed/logdate-mobile/actions/runs/37058215099) still crashed during per-test process handling and produced an incomplete native result. The focused native-map/Rewind workflow now uses the repository's existing `logdate.androidTestOrchestrator=false` mode, matching earlier local Managed Device acceptance. This does not remove either test or relax the no-skip assertion.

## Expedited internal activation

The user reprioritized immediate access to the new UI after the delayed deployment. The default is now enabled for internal testing, with explicit saved opt-outs and the presentation rollback switch preserved. This does not change data, tracking preferences, or production promotion. The default/rollback regression first failed with the old disabled default, then passed after activation. Affected datastore/core tests, scoped lint and Detekt, architecture enforcement, and debug assembly pass.

The [standard-instrumentation run](https://github.com/WillieCubed/logdate-mobile/actions/runs/37059421218) completes both targets without skips: Rewind interaction passes, but native map loading times out after 30 seconds on both. This is an unresolved map/configuration/runtime issue, not a passing native acceptance result. Historical browsing and the map-unavailable recovery path have earlier Managed Device acceptance. Native maps, unobscured captures for both targets, and the broad inherited screenshot failures remain release evidence limitations; no production promotion or physical-device acceptance is claimed.

The [enabled internal publication](https://github.com/WillieCubed/logdate-mobile/actions/runs/37060787909) completed successfully for `117264f`, version `0.1.0-main.173+117264f`, Play version code **43**. Play reports the release as completed on the internal track. Installation on a user's device has not been verified.

## Maps SDK 20 compatibility repair

A local API 35 Managed Device reproduction exposed `NoClassDefFoundError` for
`org.apache.http.ProtocolVersion` while Google Play services loaded the legacy
Maps renderer. The application now declares the optional `org.apache.http.legacy`
library, as required by the [Maps SDK 20 release notes](https://developers.google.com/maps/documentation/android-sdk/release-notes#january_31_2026).
The native acceptance workflow uses a dedicated CI certificate and a Maps-only
key restricted to that certificate and the debug package. Timeout diagnostics
catch Compose's `Throwable`-derived timeout without relaxing map-loaded or no-skip
assertions. These changes do not authorize a broader release.

After the manifest repair, the local renderer no longer crashes. Full map loading
still timed out on the local emulator, whose runtime log reports DNS resolution
failures and `ERR_INTERNET_DISCONNECTED`. Hosted map-loading acceptance remains
pending; the crash repair alone is not a passing map-loading claim.
