# Settings UI composition

Settings pages use the same row grouping as the main Settings page. Start with
`SettingsOverviewSections.kt` before adding a new presentation. Use settings rows
for actions and preferences; explanatory content uses ordinary text layouts.

## Choose an existing component

| Content                   | Component                  | Interaction                                                                                        |
| ------------------------- | -------------------------- | -------------------------------------------------------------------------------------------------- |
| Settings destination      | `SettingsScaffold`         | Owns page title, back action, scrolling and insets                                                 |
| Related rows              | `SettingsSection`          | Owns heading, container shape and spacing between rows                                             |
| Opens another screen      | `SettingsNavigationItem`   | Whole row is clickable; supplies the trailing chevron                                              |
| Specialized compound row  | `SettingsRow`              | Shared layout and text roles; use only when an ordinary wrapper cannot express independent actions |
| An inline action          | `SimpleSettingsItem`       | Supply the real action and its callback                                                            |
| One of several choices    | `SettingsChoiceItem`       | Whole row selects one value and owns radio-button semantics                                        |
| Boolean preference        | `ToggleSettingsItem`       | Represents a preference the app can actually change                                                |
| Linked preference         | `LinkedToggleSettingsItem` | Uses the established linked-toggle behavior                                                        |
| Feature-wide enablement   | `MasterFeatureToggle`      | Enables or disables a real feature, not a status indicator                                         |
| Account or backup summary | `MaterialCard`             | Uses theme shape and filled-card defaults; semantic colors are paired                              |

Export and import options are destinations, including when they open sheets. Use
`SettingsNavigationItem` for the entire row rather than a button beside a passive
label. Keep one export action per page. `NativeBackupSettingsSection` contains
platform coverage only; place archive navigation in the page's data-management
section or its separate archive row.

Android cloud backup and device transfer are platform-managed capabilities. Their
coverage is explanatory text, without a settings container, row background or
navigation affordance. `NativeBackupSettingsSection` keeps actual recovery actions
interactive. Backup's separate “Export and import” destination comes before the
platform explanations so both saving and restoring a file are discoverable.
Keep LogDate Cloud account actions in the shared `SyncPromotionCard` and signed-in
status in `BackupStatusItem` rather than recreating those cards in each page.

## One base for each function

`SettingsRow` is the only settings implementation allowed to call Material 3
`ListItem`. `SimpleSettingsItem`, `SettingsNavigationItem`, `ToggleSettingsItem`,
`SettingsChoiceItem` and the navigation portion of `LinkedToggleSettingsItem` all delegate to it.
Every title uses `bodyLarge`; every supporting description uses `bodyMedium`;
overlines use `labelSmall`. Do not override supporting text styles in a feature.
A passive description, navigation description and toggle description have the
same role even when they appear on different pages.

Use `SettingsNavigationItem` for navigation, including destinations without an
icon. Do not recreate its chevron or clickable modifier. `ToggleSettingsItem`
owns one switch semantics node for the entire row; its visual switch delegates
to that action. `LinkedToggleSettingsItem` intentionally keeps navigation and
preference changes as separate targets. The master feature control measures the
label's widest word and stacks its switch below the label when that word cannot
fit beside the switch, giving long words the full label width. A word wider than
the whole control first drops its letter spacing, then shrinks only as far as
needed, so no font's metrics break it mid-word. It retains one toggle action in
either layout. A passive `SimpleSettingsItem` has no
click action unless a real callback is supplied.

A button does not make an ordinary settings destination a compound row. Use the
slot-based `SettingsRow` for compound controls such as passkey removal,
checkbox confirmation or a time-picker button, keeping each action's enabled
state explicit. Editors, segmented choice forms, diagnostic log previews,
calendar event lists, journal insights and destructive confirmation dialogs keep
their specialized content: they are not interchangeable preference rows.

```kotlin
SettingsNavigationItem(title = title, description = description, onClick = openDetails)
ToggleSettingsItem(title = title, description = description, checked = enabled,
    onCheckedChange = setEnabled)
SimpleSettingsItem(title = title, description = description, action = {})
```

The contract rejects raw `ListItem`, `Switch` and `RadioButton` calls in settings
packages and sources using settings scaffolds or sections, including aliased
imports. It also rejects local supporting text styles in `SettingsRow`. Its grouping rules
also recognize `SettingsRow`. Cards that group multiple shared rows must use a
`SettingsSection`; summary cards without a preference group remain valid. Extend
its tests when introducing a new component
rather than bypassing the shared base. Standalone button controls directly inside
`SettingsSection`, including conditional and aliased calls, are rejected; actions
inside shared rows and specialized forms remain supported, including sections
whose direct children include editable fields or form inputs.

## Emit rows directly

`SettingsSection` delegates to `MaterialContainer`, which spaces its **direct layout
children** by `Spacing.xs`. These visible gaps separate rows; the component does not
insert `HorizontalDivider` lines. Each row must remain a separate child.

```kotlin
SettingsSection(title = stringResource(Res.string.data_management)) {
    ExportDataItem(onShowExportOptions = onShowExportOptions)
    ImportBackupItem(onShowRestoreSheet = onShowRestoreSheet)
}
```

This wrapper loses the spacing because the container receives one `Column`:

```kotlin
// Incorrect: multiple rows become a single child of the settings container.
SettingsSection(title = stringResource(Res.string.data_management)) {
    Column {
        ExportDataItem(onShowExportOptions = onShowExportOptions)
        ImportBackupItem(onShowRestoreSheet = onShowRestoreSheet)
    }
}
```

Remove the wrapper to repair grouping. Do not compensate with dividers, nested
containers, per-row backgrounds or a new section API. `ServerSelectionCard` uses
`SettingsSection` with direct `SettingsChoiceItem` children, including when embedded
in account setup. Keep this composition when reusing preferences outside Settings;
a local card around shared rows can introduce conflicting surfaces and lose their gaps.

When a destination title already describes its only action group, use
`MaterialContainer` with the shared rows directly instead of repeating the page
title in a `SettingsSection`. Export & Import uses this for its export and import
actions; the main Data Settings page keeps its Data Management section heading.

```kotlin
SettingsSection(title = stringResource(Res.string.server)) {
    SettingsChoiceItem(
        title = cloudTitle,
        selected = preset == ServerPreset.PRODUCTION,
        onClick = { selectPreset(ServerPreset.PRODUCTION) },
    )
    SettingsChoiceItem(
        title = customTitle,
        selected = preset == ServerPreset.CUSTOM,
        onClick = { selectPreset(ServerPreset.CUSTOM) },
    )
}
```

A form with segmented choices, explanatory text or controls may still need its
own layout inside a section. Radio preferences use `SettingsChoiceItem`.
The export date-range form uses connected choices in two rows on ordinary phone
widths, one row on wide sheets, and full `SettingsChoiceItem` rows when text is
enlarged or the available width cannot hold two complete labels. Keep all four
choices available without abbreviating their date units.
Export and import use the shared `DataTransferModal`: a centered, 640dp-wide
dialog on spacious windows (at least 840×600dp), and a bottom sheet on compact
or short windows. Both use `DataTransferSheetScrollContent`. Its bounded scroll
area keeps options reachable when content grows or text is enlarged. Keep
confirmation actions in its padded footer, outside the scrolling options, so
they remain visible at the bottom edge.

`checkSettingsUiContract` parses Kotlin and rejects layout wrappers around multiple
known settings rows. It runs with the core module's `check` and `ktlintCheck` tasks.
Its fixtures in `SettingsUiContractTest.kt` cover direct rows, import aliases,
conditional rows, forms, comments and strings. Extend the row-name set when adding
a shared row primitive. This check cannot inspect rows emitted by arbitrary helper
functions; review the emitted layout when extracting a helper.

## Archive options inside sheets

Export and restore category preferences use the same section and toggle rows as
Settings, even though the containing destination is a sheet. Emit each category
directly inside `SettingsSection`; do not recreate `ListItem`, `Switch`, supporting
text styles or divider rules. Keep the section inside the sheet's scrolling
content, and retain the sheet's confirmation actions outside the section.

```kotlin
SettingsSection(title = stringResource(Res.string.restore_import_options_label)) {
    if (preview.hasDrafts) {
        ToggleSettingsItem(
            title = stringResource(Res.string.export_category_drafts),
            description = pluralStringResource(Res.plurals.export_category_item_count, preview.stats.draftCount, preview.stats.draftCount),
            checked = options.includeDrafts,
            onCheckedChange = { updateOptions(options.copy(includeDrafts = it)) },
        )
    }
}
```

Omit the restore section when the archive has no optional categories. Export
keeps all four categories available and passes its `buttonsEnabled` state to
every row while the destination picker is active. Each callback copies only its
own preference so toggling drafts cannot alter media, journals or notes.
`RestorePreviewOptionsTest` and `ExportCategoryOptionsTest` exercise label and
switch targets, independent option updates, absent categories and disabled input.
`checkSettingsUiContract` recognizes `RestoreBottomSheet.kt` and
`ExportOptionsBottomSheet.kt` even if a local implementation removes all shared
settings imports.

Compose the page scaffold first and its sheet overlays afterward inside a
full-size `Box`. `PlatformSheet` renders inline during inspection, so a scaffold
composed last can cover the entire sheet in screenshots. The real-touch overlay
regression in `ArchiveSettingsNavigationTest` renders `ExportSettingsContent`
with inspection enabled and confirms both sheets remain above their background.

## Archive result hierarchy

Export and import completion share a sheet-level result hierarchy: a status icon
and `headlineSmall` title, followed by a `bodyLarge` explanation. Keep the
archive name, metadata and one compact category-count summary subordinate to
the outcome. Recovery actions appear before archive details when the user needs
to continue work. Do not nest a result card inside the sheet, split the same
counts into stat tiles, or repeat them in a second sentence. The import preview
contains that single shared archive summary in `MaterialCard`; result states use
the same summary typography without a second enclosing result surface.
The archive name wraps fully so a long filename keeps its distinguishing suffix.
An export confirmation offers only Done after the system file picker has saved
the archive; do not add an Open action without a wired file opener.

The icon must describe the actual result. A finished, complete archive may use a
success icon; paused work uses pause, active recovery uses history, and missing
or unavailable content uses information. When metadata is still incomplete
while media is recovering, describe the active recovery first. Preserve its
resume/recovery action and never label a partial result as complete.

```kotlin
DataTransferResultHeader(
    title = stringResource(resultTitle),
    description = stringResource(resultDescription),
    icon = statusIcon,
    iconTint = statusTint,
)
DataTransferArchiveSummary(
    fileName = archiveName,
    fileDetails = localizedMetadata,
    countsSummary = localizedCounts,
)
```

## Use theme roles and tokens

Use `Spacing` for padding and gaps, and `MaterialTheme.typography` for text roles.
`MaterialCard` defaults to the theme's large shape, `surfaceContainerHighest` and
zero elevation. A semantic card may override both container and content colors;
LogDate Cloud pairs `secondaryContainer` with `onSecondaryContainer`. Preserve
those paired roles in loading, signed-in and signed-out states.

Use string resources for user-facing copy. Keep status descriptions short and put
recovery in the underlying platform. A status row should explain what is covered
or what happens next without exposing internal exceptions or offering choices the
app can resolve automatically.

## Examples and visual verification

`SettingsRowScreenshotScenes.kt` is a runnable shared example containing passive,
navigation, toggle, linked-toggle and disabled rows with matching descriptions.
Render the `settings-components` scene to compare their typography together.
`ArchiveSettingsNavigationTest` checks one export per populated Data Settings page
and whole-row export/import navigation. `ArchiveSettingsVisualAcceptanceTest`
compares Data Settings and Backup with Android coverage and recorded quota feedback
on phones, tablets and at 200% text. A recorded quota failure describes a past
provider report; it does not establish whether the latest Android backup succeeded.
The visual test writes initial, archive-action and native-coverage views to
`LOGDATE_SETTINGS_FOLLOWUP_RENDER_DIR` (default `/tmp/logdate-settings-followup-renders`).
`SettingsRowsTest.kt` verifies the supporting text role, passive semantics,
disabled preferences and independent linked actions. `SettingsMasterLayoutTest`
checks long-word wrapping, including a word wider than the whole control, and the
single toggle target at 200% text.

`NativeBackupScreenshotScenes.kt` supplies the same signed-out, signed-in and
quota-limited states to Android previews and desktop screenshot tests. It explicitly
passes `NativeBackupStatus.Available`; the default `Unsupported` hides Android rows
and cannot demonstrate their layout. These are presentation fixtures, not evidence
of a Google provider backup or setup transfer.

Run the composition check and shared scene tests:

```sh
./gradlew :client:feature:core:checkSettingsUiContract :client:screenshot-scenes:desktopTest
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew :client:feature:core:desktopTest \
  --tests '*ArchiveSettings*' --tests '*NativeBackupSettingsTest'
```

Render and validate the shared native states with the desktop harness:

```sh
./gradlew -Dlogdate.desktopScreenshots.sceneFilter=native-backup \
  :app:compose-main:updateDesktopScreenshotTest --tests '*shared catalog matches baselines*'
LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true TZ=America/Los_Angeles \
  ./gradlew -Dlogdate.desktopScreenshots.sceneFilter=native-backup \
  :app:compose-main:desktopTest --tests '*shared catalog matches baselines*'
```

Inspect the images before accepting references. Compare the changed page with the
main Settings page at matching dimensions. Check row separation, card roles,
leading icons, action placement and readable wrapping in light, dark, compact,
landscape, tablet, RTL and 200% text configurations. Also inspect other callers when
changing a shared primitive: `MaterialCard` is used by audio recording displays.
See [Screenshot tests](../../testing/screenshot-tests.md) for the Android harness.

Validate Android references with
`./gradlew :app:android-main:validateDebugScreenshotTest --tests '*NativeBackup*'`.
For an intentional visual change, record candidates with the corresponding
`updateDebugScreenshotTest` task and inspect them before committing.
`NativeBackupSettingsTest.largeTextKeepsTheAccountFreeExportReachable` checks that
scrolling still reaches the archive action at 200% text, including quota feedback.
