# Distributing LogDate

Store listings and release materials follow the
[product purpose and positioning](./product-info/product-positioning.md): **LogDate is a home to
document, reflect on, and share memories.** Lead with that purpose and demonstrate the capabilities
available in the advertised release. Journaling is functionality, not the primary category pitch.
Use the same direction for screenshots, feature graphics, banners, and preview videos, adapting
the composition and specific feature claims to each platform.

For more information on how to build Compose Multiplatform apps,
see [this][compose-publishing-tutorial]
tutorial.

## Building the app

### Android

This assumes you have Android Studio installed and set up.

#### Main App

For information on how to build the app for Android, see
the [Android documentation][android-publishing-tutorial].

For LogDate's automated Play Console publishing flow, required GitHub secrets,
promotion-based production releases, and Android release provenance/versioning, see
[`docs/reference/google-play-publishing.md`](./reference/google-play-publishing.md).

#### Wear OS App

```shell
./run build:wear:release
```

The Wear OS app ships on the phone app's Play listing, under the phone's package name and signing
key, as a Wear OS form factor. See [Wear OS](./reference/google-play-publishing.md#wear-os) for how it
publishes. For development installation on a physical watch, see the
[Wear OS README](../app/wear/README.md#installing-on-a-physical-watch).

### Desktop (macOS, Linux, Windows)

To build the app, the app must be packaged per platform.

Run the following commands at the root of the project to build the app for each platform:

#### macOS

```shell
./gradlew :app:compose-main:packageDmg
```

#### Linux

```shell
./gradlew :app:compose-main:packageDeb
```

#### Windows

```shell
./gradlew :app:compose-main:packageMsi
```

[compose-publishing-tutorial]: https://github.com/JetBrains/compose-multiplatform/tree/master/tutorials/Native_distributions_and_local_execution

[android-publishing-tutorial]: https://developer.android.com/studio/publish/preparing
