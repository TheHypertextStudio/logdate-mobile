# `:client:sharing`

This module sends LogDate content out of the app: plain text and media through the system share sheet, journals as links, QR codes or Instagram Stories, memory days, and photos or videos to Instagram. Feature modules call the `SharingLauncher` interface and never touch platform share APIs themselves. The module also draws the image cards that accompany shared Rewind content.

## What it contains

All code is in the package `app.logdate.client.sharing`, with Koin wiring in `app.logdate.client.sharing.di`.

| Type or file                                                                       | What it does                                                                                                                                                                                                                                                                                                                        |
| ---------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SharingLauncher`                                                                  | The entry point. It shares generic text and media, a memory day, a journal as a link, a journal as a QR code, a journal to Instagram Stories with a `ShareTheme`, and a photo or video to the Instagram feed. `getUriFromMedia` turns a media id into a platform URI. `NoOpSharingLauncher` is the do-nothing version for previews. |
| `ShareAssetInterface`                                                              | Generates the images a journal share needs: a background layer, a sticker layer that may be transparent, and a QR code for the journal's public URL. Files are written to the cache directory and the result is their URI.                                                                                                          |
| `ShareTheme`                                                                       | Sealed interface with `Light`, `Dark` and `Custom(background, foreground)`.                                                                                                                                                                                                                                                         |
| `JournalLinkSharingAvailability`, `ServerJournalLinkSharingAvailability`           | Report whether link and QR code sharing may be offered. The server implementation is true only when the connected server advertises `ServerProtocolFeature.JOURNAL_SHARE_LINKS_V1`.                                                                                                                                                 |
| `RewindQuoteCardRenderer`, `RewindPanelCardRenderer`, `RewindStatsSummaryRenderer` | Render a `RewindQuote`, `RewindPanel` or `RewindStatsSummary` as a styled bitmap and return a content URI string, or null when rendering is unavailable. Callers fall back to plain-text sharing on null. Each has a `NoOp` object that always returns null.                                                                        |
| `journalShareUrl` (in `ShareUrls.kt`)                                              | Builds `https://<host>/journal/<id>`, where the host is the owner's full handle (for example `alice.logdate.app`) when one is passed and `logdate.app` otherwise. The Android, iOS and desktop launchers call it without a handle, so they produce the `logdate.app` form.                                                          |
| `memoryDayShareText`                                                               | Builds the localized text for a shared memory day, from the summary when there is one.                                                                                                                                                                                                                                              |
| `sharingModule`                                                                    | An `expect` Koin module with an `actual` per platform.                                                                                                                                                                                                                                                                              |

Per platform:

- Android: `AndroidSharingLauncher`, `AndroidShareAssetGenerator`, `AndroidRewindQuoteCardRenderer`, `AndroidRewindPanelCardRenderer` and `AndroidRewindStatsSummaryRenderer`. `ShareSheet.kt` builds the system chooser with custom copy-link and QR code actions. `IntentGenerators.kt` builds the Instagram story, image and video intents. `ShareReceiver`, declared in the module's manifest, receives the share sheet's own callbacks (copy link, share QR code, which app was chosen). `ShareAssetFormats` holds the file extension, compression format and MIME type used by every generated asset. QR codes use ZXing.
- iOS: `IosSharingLauncher` and the three `Ios...Renderer` classes.
- Desktop: `DesktopSharingLauncher` opens links in the default browser; the Rewind renderers are bound to the no-op versions.

## How it fits with other modules

The module depends on `:client:media`, `:client:repository`, `:shared:config` and `:shared:model`, plus Compose resources, Koin and Napier. The Android source set adds Koin Android and ZXing. The build files of `:app:android-main`, `:app:compose-main`, `:client:feature:core`, `:client:feature:journal` and `:client:feature:rewind` declare a dependency on it.

## Platforms

Android, iOS (`iosArm64`, `iosSimulatorArm64`) and desktop. The desktop target is declared as `jvm("desktop")`, so its source set is `desktopMain`.

## Things to know

- Instagram requires `content://` URIs for story assets, so generated files are shared through the app's file provider rather than as file paths.
- The build reads `logdate.metaAppId` from the root `local.properties`. It becomes the `META_APP_ID` build config field and a generated `facebook_app_id` Android string resource, which the manifest references in the `com.facebook.sdk.ApplicationId` meta-data. When the property is absent the value is empty.
- `ShareTheme.cacheKey` gives each theme a stable ASCII name used in cached file names.
- Share text and the memory-day strings are in `src/commonMain/composeResources/values/strings.xml` and `strings_share_text.xml`.

## Tests

The module has no test source sets of its own.
