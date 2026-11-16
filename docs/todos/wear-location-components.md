# Wear Location Component TODOs

The Wear app currently removes phone-only location services and receivers that
arrive through transitive client modules. That is the right short-term cleanup:
Wear OS does not currently use location tracking, and the inherited manifest
entries add lint failures plus unnecessary component registrations.

Follow-up implementation work:

- Decide whether Wear should support location timeline capture at all.
- If Wear should not support location capture, split `client:location` so
  phone-only manifest entries are packaged only by the phone app.
- If Wear should support location capture, implement Wear-safe equivalents for
  `OptimizedBackgroundLocationReceiver`,
  `LocationTrackingBootReceiver`, `ActivityAwareLocationService`, and
  `ActivityTransitionReceiver`.
- Add Wear unit or Robolectric coverage proving the chosen location behavior
  does not register unsupported foreground services or boot receivers.
- Remove the `tools:ignore="MissingClass"` suppressions from
  `app/wear/src/main/AndroidManifest.xml` once the component boundary is fixed.
