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
