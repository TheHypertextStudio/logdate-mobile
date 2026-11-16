# Media quality checklist

> Standing rules for image and video pipelines. Anyone adding a new media surface
> — gallery, picker, viewer, editor, export, widget — should be able to tick
> every box here without thinking about it.

## Why this exists

Media UX on Android often ignores the platform: storage permissions for what
should be a permission-free PhotoPicker, photos crammed into wrong aspect ratios with center-crop, no
EXIF rotation, blocking the main thread for metadata, custom pickers that
re-decode everything on every recomposition. This checklist exists so we don't
drift back into those habits.

The framing is "high-quality, platform-native UX." Each rule below has a reason
tied to that.

## Image loading

- [ ] Use `AsyncImage` (or `SubcomposeAsyncImage`) — never `BitmapFactory.decodeStream`
      or `Image(bitmap=…)` for content URIs. The shared `ImageLoader` configured
      in each platform's app entry handles caching, crossfade, EXIF, color
      management. Bypassing it loses every one of those.
- [ ] Pass an explicit `contentScale`. `Crop` for grid thumbnails, `Fit` for
      full-screen viewers. The default `Inside` is rarely what you want.
- [ ] Pass a `Size` hint via `ImageRequest.Builder.size(...)` when the layout
      gives the image a concrete pixel size. Saves decoding 12 MP source for a
      112 dp grid tile.
- [ ] Provide a meaningful `contentDescription`. `null` is correct _only_ for
      decorative imagery (parallax backgrounds, etc.); for photos, profile
      avatars, journal covers, etc., write a sentence TalkBack can read.
- [ ] If you must hand-decode a bitmap (postcard export, widget rendering,
      share-asset rendering), use a two-pass decode with `inSampleSize`, and
      apply EXIF orientation via `ExifInterface.getRotationDegrees()` +
      `Matrix.postRotate()` before drawing. See `AndroidExportEngine` for a
      reference implementation.

## Video playback

- [ ] Use Media3 (`ExoPlayer`, `PlayerView`). Never `android.widget.VideoView` —
      it's deprecated and lacks support for modern codecs, HDR, and DRM.
- [ ] Acquire players from `ExoPlayerPool` and release them back when the
      surface leaves composition. The pool reuses warm players and wires the
      shared `MediaCache`, so a recently-watched video replays instantly.
- [ ] Drive the player container's aspect ratio from
      `Player.Listener.onVideoSizeChanged` (with a sensible default while
      metadata loads). Never hard-code 16:9.
- [ ] Set `PlayerView.resizeMode = RESIZE_MODE_FIT`. `RESIZE_MODE_ZOOM`
      center-crops the video to fit, which chops the top and bottom off a
      portrait phone video — the Instagram-on-Android complaint.
- [ ] `MediaMetadataRetriever.setDataSource` is blocking IO. Wrap it in
      `withContext(Dispatchers.IO)` or expose the duration via a suspending
      function — never call it from a Compose render scope or an
      `ActivityResultContracts` callback.

## Picker & permissions

- [ ] The in-app recent-photos grid is intentional first-party UX. Asking
      for `READ_MEDIA_IMAGES` to power it is justified. The platform critique
      applies to _how well_ we use the picker, not whether we ask for access.
- [ ] On Android 14+, request `READ_MEDIA_VISUAL_USER_SELECTED` alongside
      `READ_MEDIA_IMAGES` (via `RequestMultiplePermissions`) so the user
      gets the three-way prompt (All / Select / Don't allow). Treat any of
      `{READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED}` as "granted."
- [ ] For _fallback_ "Browse all" entry points that don't have a first-party
      grid yet, use `PickVisualMedia` + the correct `VisualMediaType`
      (`ImageOnly` / `VideoOnly` / `ImageAndVideo`). It needs no permission,
      hides the file-browser SAF UI, and looks like the system picker.
      Never `ActivityResultContracts.GetContent("image/*")` — that's the
      pre-2022 contract.

## Capture

- [ ] CameraX video recording: `QualitySelector.fromOrderedList(UHD, FHD, HD, SD)`
      with a `FallbackStrategy`. `Quality.HIGHEST` alone silently fails on
      sensors that don't expose that profile.
- [ ] Set `targetRotation` explicitly on `Preview`, `ImageCapture`, and
      `VideoCapture` from the display's current `Display.rotation`. Without
      it foldables produce sideways output mid-session.
- [ ] Tap-to-focus performs a haptic (`HapticFeedbackConstants.CONTEXT_CLICK`)
      so users feel the tap landed.
- [ ] Audio recording: use a foreground service with the
      `microphone|mediaPlayback` types; the service notifications must include
      a meaningful title so the user can find their way back.
- [ ] Long voice notes: amplitude/waveform extraction must surface progress.
      Call `AudioContextProcessor.processProgressively(...)` and `collect`
      the flow on the UI side rather than awaiting a single result — the
      waveform should fill in from start to end while decoding runs.

## Export and sharing

- [ ] Photo-bearing exports should choose JPEG quality 92, not PNG quality 100. PNG matters when the canvas is flat colors and crisp text; for
      anything photo-heavy it bloats the file 5–10× for no visible quality.
      See `AndroidExportEngine.saveBitmapToCache(asJpeg = …)`.
- [ ] When drawing photos onto a fixed-size destination rect, use a center-
      crop `src` rect (`centerCropSquare(...)`) — never pass `src = null`
      with a non-matching destination, which stretches the source to fit.

## Cross-platform

- [ ] Coil 3's `ImageLoader` is configured per platform in the app's entry
      point: `LogDateApplication.newImageLoader` on Android,
      `IosImageLoader.kt` + `MainViewController` on iOS,
      `DesktopImageLoader.kt` + `main.kt` on Desktop. Don't add a parallel
      loader instance for new features.
- [ ] Video playback is wired on every platform. iOS uses `AVPlayer` via
      UIKit interop; Desktop uses VLCJ when libVLC is present on the host
      (and falls back to the system player otherwise). New video features
      get controls (play/pause, scrubber, mute) on all three platforms
      from day one.
- [ ] Recent-photos queries on platforms with big libraries push a `limit`
      into the underlying cursor (Android: `ContentResolver.QUERY_ARG_LIMIT`).
      Never materialize the full library and then slice in memory —
      it's the difference between a sub-200ms first paint and a noticeable
      stall on phones with 10k+ photos.

## Accessibility

- [ ] Every `AsyncImage` that carries meaning has a `contentDescription`.
      TalkBack walks should never read "image, image, image."
- [ ] Honor `Settings.Global.ANIMATOR_DURATION_SCALE` (or the corresponding
      Compose accessibility flag) when auto-advancing Rewind story panels.
- [ ] Pinch-to-zoom, double-tap-to-zoom-to-point, two-finger pan, and
      vertical fling-to-dismiss are the gesture set users expect from any
      photo viewer. The zoomable-image library on `MediaDetailScreen` covers
      these — don't roll a new viewer without them.

## Color and HDR

- [ ] Full-screen photo viewers should opt the activity into
      `ACTIVITY_COLOR_MODE_HDR` on capable displays (API 26+) and request
      `Bitmap.Config.RGBA_F16` for the image request when the source is
      wide-gamut. Everywhere else stays on `ARGB_8888`.
- [ ] Don't strip EXIF orientation on import. Don't drop the color profile
      when re-encoding for share. Honor the source's intent.
- [ ] Don't strip EXIF metadata at sync or upload either. GPS coordinates
      feed the Rewind algorithm's location-aware beat clustering; capture
      timestamps drive timeline ordering and accessibility descriptions;
      orientation is required for postcard round-trips. The server already
      encrypts blobs before persistence, so there's no plaintext-leak
      threat model to defend against. Treat EXIF as product data, not
      noise.

## Camera, microphone, and output routes

A route is the camera, microphone, or speaker that capture or playback currently uses. Android
decides most routes: it picks the headset when one connects and sends audio to the speaker when it
disconnects. The app can ask for a route, and sometimes it cannot force one. These rules keep the
selector honest about which of the two is happening, and keep capture and playback working when a
device appears or disappears.

- [ ] Use the shared route model and selector, not a surface-specific one. `MediaDeviceUiState` and
      `MediaDeviceSelectionUiState` (in `client/media`) describe devices and the current choice, and
      `MediaDeviceSelector` and `MediaDeviceSelectorSheet` (in `client/ui`) render it. Phone, Wear,
      inline audio cards, the immersive player, video players, and remote camera all share the
      same notion of selected, unavailable, and system-controlled, so a user learns the control
      once.
- [ ] Keep the primary action dominant. The shutter, record, play, and save controls stay the
      largest and most reachable controls on the surface. Device selection is secondary, but it is
      visible where the user is already acting, labelled with the active device ("Back camera",
      "USB camera", "Phone speaker").
- [ ] Be honest when the platform owns the route. If the app cannot force a microphone or output on
      the current API or device, keep the active-route label visible and show that the route is
      system controlled. A selector that looks like it works but is ignored is worse than none.
      CameraX video recording is the standing example: `withAudioEnabled()` takes no preferred
      `AudioDeviceInfo`, so the camera's video microphone is shown as the detected,
      system-controlled route. Audio-note recording, by contrast, passes the selection to
      `MediaRecorder.setPreferredDevice`.
- [ ] Leave the `systemSettingsAction` default on `MediaDeviceSelector` and
      `MediaDeviceSelectorSheet` alone. The default is `rememberMediaRouteSettingsAction(kind)`,
      which gives every system-controlled microphone or output sheet a working next step: the
      Sound settings screen for input, and `MediaRouter2.showSystemOutputSwitcher()` on Android 14
      and later for output, falling back to Sound settings. The user opened the sheet to change a
      route, so a sheet that only explains why they cannot would strand them. Requiring each caller
      to pass the action makes the common path the easy one to forget. Pass `null` or a different
      action only for previews, screenshot fixtures, tests, or a surface where opening settings
      would mislead. Cameras have no settings handoff, so the default is `null` for them.
- [ ] Persist the preference, never the device instance. The stored value is a preferred device id
      (`AndroidAudioRouteRepository` for input and output, `AndroidCameraDeviceSelection` for the
      camera). When that device is missing, fall back to the first available device (normally the
      system default) and show the unavailable-route copy naming it rather than failing: `MediaDeviceSelectionResolver` holds the fallback copy and the restore-on-return
      behaviour, and is covered by common tests.
- [ ] Show the route the platform actually applied. `AudioRecordingService` reapplies the preferred
      microphone when the route list changes during a recording and reports a failure to apply it,
      and the selector reflects the routed device rather than the requested one.
- [ ] Never stop playback from feature cleanup. Closing a viewer must not stop audio that is
      playing. Audio-note playback runs in `AudioPlaybackService`, a Media3 `MediaSessionService`
      that is started before the controller plays, posts a persistent media notification, and
      exposes pause, play, seek, and stop to the notification, the lock screen, and the app. The
      app-wide `AudioPlaybackProvider` and the mini-player are the control surface once a viewer
      closes. Only an explicit Stop ends playback.
- [ ] Video continues only while it is visible. `VideoPlayerContent` keeps playing in
      multi-window and Picture-in-Picture, tries to enter Picture-in-Picture instead of pausing
      when the user leaves during playback, and pauses when no visible surface remains. Video never
      continues as invisible audio-only playback, so a video surface needs no foreground media
      service. The activities that host video declare `supportsPictureInPicture`. Foldable behavior in
      Picture-in-Picture is covered in [Foldable quality](../../testing/foldable-quality.md).
- [ ] Reach the same route control from every playback surface. Saved-audio editor blocks, the
      mini-player, the immersive audio screen, current journal audio cards, current timeline
      snippets, video players, the external-display controls on the controlling phone, and the Wear
      day-detail output picker each expose the selector. The selector never crowds the title or transport controls; in the
      mini-player it stacks below them on compact widths and sits inline on expanded widths.
      The external display itself shows no route controls.
- [ ] Wear remote camera is controlled from the watch and runs on the phone. The phone opens
      `RemoteCameraActivity`, which uses the same capture content as the editor, publishes its
      camera list over the Data Layer (`/logdate/camera/devices`), and reports the saved or failed
      outcome (`/logdate/camera/capture-result`). The watch shows "Capturing" until that
      acknowledgement arrives, and selects exact cameras with `device:<id>` rows. The watch never
      falls back to the generic system camera intent.

## Verification

For media changes, the bar for "done" is:

1. Open a portrait phone video → letterboxed, not cropped.
2. Pick from the photo picker on Android 14+ → three-way prompt available;
   selecting partial gives a working in-app grid.
3. Export a postcard with 5+ photos including a portrait → no OOM, photos at
   their natural aspect ratio, EXIF orientation respected, share-file
   sub-megabyte.
4. Scroll a multi-video screen → `dumpsys media.metrics` shows ≤ pool-size
   ExoPlayer instances.
5. TalkBack walk-through of timeline, journal detail, Rewind → every image
   announces meaningful context.
6. StrictMode (debug builds) reports no main-thread disk reads from any
   media path.
7. On a fresh debug install, opening the picker on a device with a
   synthetic large library (`adb shell content insert ...`) shows a
   first paint under 200ms — the query cap holds.
8. iOS + Desktop video tiles play in place with working play/pause,
   scrubber, and mute controls; navigating away pauses playback so audio
   doesn't keep going behind the user's back.
9. For a change to a route, camera, or playback surface, run the matching emulator suites in the
   [camera and audio emulator test matrix](../../testing/camera-audio-emulator-test-matrix.md),
   and check by hand what an emulator cannot model: an external USB camera, a USB or Bluetooth
   microphone, and wired, USB, or Bluetooth output. A route sheet that renders is not proof that
   audio moved; the route has to change.
