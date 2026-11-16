# `:client:theme`

This module supplies the LogDate look: color schemes, typography, spacing values and the workspace surface tokens. App code wraps its content in `LogDateTheme`, and the UI and feature modules read colors, type and spacing from `MaterialTheme` and the helpers here. Reusable UI components live in `:client:ui`.

## What it contains

All code is in the package `app.logdate.ui.theme`.

| File                    | Contents                                                                                                                                                                                                                                                                                                       |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Theme.kt`              | `LogDateTheme`, the entry point. It resolves a color scheme, layers platform colors on it, and wraps the content in `MaterialExpressiveTheme` and a root `Surface` that sets the background and content color. Also `LightColorScheme` and `DarkColorScheme`, and the `expect` function `rememberColorScheme`. |
| `Color.kt`              | The graphite tonal palettes that both fallback schemes are built from, as top-level colors named by palette and tone, for example `GraphitePrimary40`, `GraphiteSecondary90`, `GraphiteTertiary30`, `GraphiteNeutral98` and `GraphiteNeutralVariant50`.                                                        |
| `Type.kt`               | The shared Material 3 `Typography` value.                                                                                                                                                                                                                                                                      |
| `PlatformTypography.kt` | The `expect` function `platformTypography()`.                                                                                                                                                                                                                                                                  |
| `PlatformColors.kt`     | `PlatformSemanticColors`, the `expect` function `rememberPlatformSemanticColors`, and `ColorScheme.applyPlatformSemantics`, which replaces scheme roles with any non-null platform token.                                                                                                                      |
| `Spacing.kt`            | The `Spacing` object: `xs` 4dp, `sm` 8dp, `md` 12dp, `lg` 16dp, `xl` 24dp, `xxl` 32dp, `xxxl` 48dp.                                                                                                                                                                                                            |
| `WorkspaceTokens.kt`    | `WorkspaceSurface` (`Canvas`, `Panel`, `Group`, `Raised`, `Selected`) and the `ColorScheme.workspaceContainer` and `workspaceContent` functions that map each surface meaning to a Material color role.                                                                                                        |

`LogDateTheme(dynamicColor = true, darkTheme = isSystemInDarkTheme(), content)` is the call to use. Every role in `LightColorScheme` and `DarkColorScheme` except the error roles is set from the graphite palettes.

## Platform behavior

| Platform | Color scheme                                                                                                        | Typography                                                                                      | Platform semantic colors                                                                                            |
| -------- | ------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Android  | Wallpaper-derived dynamic color on Android 12 and later when `dynamicColor` is true; the graphite schemes otherwise | The shared `Typography`                                                                         | None                                                                                                                |
| Desktop  | The graphite schemes; `dynamicColor` is ignored                                                                     | The shared `Typography`                                                                         | None                                                                                                                |
| iOS      | The graphite schemes; `dynamicColor` is ignored                                                                     | Apple's text-style sizes, scaled by the user's Dynamic Type setting and updated when it changes | Fixed values for Apple's light and dark system backgrounds, labels and separators; the primary color stays graphite |

## How it fits with other modules

The module depends only on Compose Multiplatform (runtime, foundation, material, material3, ui, resources and tooling preview); desktop adds `compose.desktop.currentOs`. It applies the `app.logdate.kmp.library` convention plugin from `build-logic`.

`:app:compose-main`, `:client:feature:android-widgets` and `:client:ui` (as an `api` dependency) declare a dependency on it, and `:app:android-main` uses it in its instrumented and screenshot test configurations. The module publishes consumer keep rules from `proguard-rules.pro` that keep every class in `app.logdate.ui.theme`.

## Platforms

Android (namespace `app.logdate.client.theme`), iOS (`iosArm64` and `iosSimulatorArm64` through the convention plugin) and desktop (JVM target `desktop`). Source sets are `commonMain`, `androidMain`, `desktopMain` and `iosMain`, with `expect`/`actual` pairs for `rememberColorScheme`, `rememberPlatformSemanticColors` and `platformTypography`.

## Tests

`FallbackColorSchemeTest` in `commonTest` checks that the fallback schemes stay nearly gray outside the error roles, that the Material template purple is absent, and that each text color meets the WCAG AA contrast minimum on its surface. Run it on the desktop target with:

```
./gradlew :client:theme:desktopTest
```
