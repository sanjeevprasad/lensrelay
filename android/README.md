# LensRelay for Android

The native Kotlin application pairs with LensRelay Desktop, captures with
CameraX, hardware-encodes video, and publishes it with MoQ over the local
network. Camera data is never uploaded to LensRelay servers.

Current features include QR pairing and mutual unpair, encrypted local
streaming, front/back preference per desktop, independent video rotation,
CameraX-negotiated dimensions, and an authenticated persistent control channel.
The desktop can control supported camera, focus, zoom, exposure, torch,
resolution, frame rate, bitrate, codec and stabilization
settings. Remote start requires explicit per-desktop consent on the phone.

Requirements: Android API 29 or newer, Android SDK 37, and JDK 17 or newer.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. The control connection is
currently active while the Android activity is in the foreground.

Release builds are signed through `keystore.properties` in this directory
(gitignored, like every keystore pattern). Without it, `assembleRelease` and
`bundleRelease` still succeed but produce unsigned artifacts. The file holds:

```properties
storeFile=/absolute/path/to/upload.jks
keyAlias=upload
storePassword=…
```

Native MoQ libraries from `dev.moq` ship prebuilt for `arm64-v8a`,
`armeabi-v7a`, and `x86_64`; release builds additionally strip them when the
NDK version AGP requests is installed, which is optional because the current
artifacts are already stripped.
