# `:client:sensor`

This module exposes the device's battery state, network data-saver state and gyroscope as small Kotlin interfaces with a Koin binding per platform. The app shells and the UI module read these values to adapt behaviour to the device, for example to tilt a visual with the phone's gyroscope.

## What it contains

| Package                             | Main types                                                                                                                                                                                                                                                                                                                       |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `app.logdate.client.sensor`         | `GyroSensorProvider` exposes a `SharedFlow<GyroOffset>` and a `cleanup()`. `GyroOffset` holds `x` and `y` as floats. `UnavailableGyroSensorProvider` is the implementation for platforms with no gyroscope; it never emits.                                                                                                      |
| `app.logdate.client.sensor.battery` | `BatteryInfoProvider` offers a `currentBatteryState` flow, `getCurrentBatteryState()`, `isPowerSaveMode()` and `cleanup()`. `BatteryState` carries `level` (0 to 100), `isCharging` and `isPowerSaveMode`. `StaticBatteryInfoProvider` reports a full, charging battery and lets tests set another state with `setBatteryState`. |
| `app.logdate.client.sensor.network` | Platform implementations of `NetworkSaverModeProvider`, described below.                                                                                                                                                                                                                                                         |
| `app.logdate.client.sensor.di`      | `sensorModule`, an `expect` Koin module with one `actual` per platform. It binds `GyroSensorProvider`, `BatteryInfoProvider` and `NetworkSaverModeProvider`.                                                                                                                                                                     |

The `NetworkSaverModeProvider` interface, `NetworkSaverState` and `NetworkConnectionType` are defined in `:client:networking`, so that module can consume them without depending on this one. This module supplies the implementations.

Per platform:

- Android: `AndroidGyroSensorProvider` (the `Sensor.TYPE_GYROSCOPE` sensor), `AndroidBatteryInfoProvider` (`BatteryManager` and `PowerManager`) and `AndroidNetworkSaverModeProvider` (`ConnectivityManager`).
- iOS: `IosGyroSensorProvider` (`CMMotionManager`), `IosBatteryInfoProvider` (`UIDevice` and `NSProcessInfo`) and `IosNetworkSaverModeProvider`.
- JVM: `JvmBatteryInfoProvider` reports a default full battery, `JvmNetworkSaverModeProvider` reports no data saver and derives the connection type from whether a non-loopback network interface is up, and the gyroscope binding is `UnavailableGyroSensorProvider`.

## How it fits with other modules

The module depends on `:client:networking`, Kotlin coroutines and Koin; the Android source set adds `kotlinx-coroutines-android` and `koin-android`. `:app:compose-main`, `:app:wear` and `:client:ui` declare a dependency on it in their build files.

## Platforms

Android, iOS (`iosArm64`, `iosSimulatorArm64`) and JVM.

## Android network policy

`AndroidNetworkSaverModeProvider` follows Android's default network, which is the route ordinary LogDate requests use. The capabilities delivered to its network callback decide the connection type, metering and validation state. Code in the callbacks must not reread the synchronous connectivity getters, because Android can still return the previous network at that moment. A newly available default network stays restricted until its own capabilities arrive, and delayed events for the previous network cannot replace it.

Data Saver broadcasts and explicit state reads refresh the app's background restriction separately. Once callback state exists, these reads keep its network capabilities. Synchronous network reads are only the startup fallback before the callbacks arrive.

A test of this behaviour has to deliver callback events while the getters stay stale; a test that only calls `getCurrentDataSaverState()` misses the race. `AndroidNetworkPolicyCallbackTest` covers it.

## Running the tests

```bash
./gradlew :client:sensor:jvmTest
./gradlew :client:sensor:testAndroidHostTest
```

The JVM tests cover the JVM battery and network providers. The Android host tests cover the network provider, including the callback regressions. Tests that need a real device live in the `androidDeviceTest` source set and cover the Android battery and network providers.
