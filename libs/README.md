# Vendored libraries

These files are release assets of [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), the
on-device speech recognition library, which is licensed under the Apache License 2.0. Its
project publishes them on its GitHub releases page and not to a Maven repository, so they are
committed here and referenced by path from the build files.

| File                                              | Release  | Used by                                       | SHA-256                                                            |
| ------------------------------------------------- | -------- | --------------------------------------------- | ------------------------------------------------------------------ |
| `sherpa-onnx-1.12.28.aar`                         | v1.12.28 | `:client:feature:speechrecognition` (Android) | `b948066872e698f2e37e3c936c303426e12c5b7f9280e24f18767ed2efa08f8a` |
| `sherpa-onnx-v1.12.35.jar`                        | v1.12.35 | `:client:media` (desktop)                     | `30c53562b5282b4eabc1ec584b04de1536b057462971a2b26583764fd355dcf7` |
| `sherpa-onnx-native-lib-osx-aarch64-v1.12.35.jar` | v1.12.35 | `:client:media` (desktop)                     | `1667127ea6c25fb782b4389432529cb793f659c90f1e881fbb6fedbdf9efc3ac` |

Each checksum equals the digest GitHub reports for the asset of the same name on that release.
To check a file:

```bash
shasum -a 256 libs/sherpa-onnx-1.12.28.aar
```

The desktop native library is the macOS build for Apple silicon, so on-device transcription in
the desktop app runs on Apple silicon Macs.

To move to a newer release, download the three assets from the release page, replace the files,
update the file names in `client/feature/speechrecognition/build.gradle.kts` and
`client/media/build.gradle.kts`, and update this table.
