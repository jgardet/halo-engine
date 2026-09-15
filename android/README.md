# Android Halo Transport

This module contains the Android `BluetoothGatt` implementation of the shared `HaloBleTransport` interface.

## Build

An Android SDK installation is required. Set `ANDROID_HOME` or create a local `local.properties` file with `sdk.dir=...` outside source control.

```bash
gradle :android:assembleDebug
gradle :android:connectedDebugAndroidTest
```

## Connection lifecycle

Scanning and the initial `connectGatt()` call belong to the app's foreground service or activity because Android requires runtime Bluetooth permissions. The callback object must be supplied to `connectGatt()`:

```kotlin
val callbacks = BluetoothGattChannel.Callbacks()
device.connectGatt(context, false, callbacks)
val channel = callbacks.awaitChannel()
val transport = AndroidBleTransport(channel)
transport.connect()
```

The transport then:

1. Discovers the Halo service and TX/RX characteristics.
2. Enables RX notifications through the CCCD.
3. Requests MTU 512 and uses the negotiated value, capped at 512.
4. Serializes all writes with a coroutine `Mutex`.
5. Awaits `onCharacteristicWrite` completion for every packet.
6. Frames `sendMessage()` using the official message format.
7. Waits for the Halo data ACK (`0x01 0x00 0x00`) after each data packet.
8. Releases the GATT connection on disconnect.

The instrumentation tests use a fake `GattChannel` so packet framing, serialization, MTU limits, and ACK waiting do not require physical hardware. A physical-Halo test still needs to verify Android permissions, scanner behavior, connection timing, and firmware-specific notification behavior.

## Runtime install

`HaloRuntimeInstaller` uploads `he_runtime.lua` (LZ4-compressed when the device
reports `frame.compression`, with legacy fallback) and can write a `main.lua`
autorun shim (`autorun = true`) so the runtime starts on power-on/reset without
a host. `AssetHaloRuntimeSource` loads the bundled runtime from app assets, and
`HaloRuntimeSource.versionOf` extracts its `RUNTIME_VERSION` so hosts can
compare `;rt=` from a STATUS probe and skip re-installation when current.
