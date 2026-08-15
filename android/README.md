# LensRelay for Android

The Android application captures the phone camera for LensRelay. The current
pre-alpha build establishes the camera and UI foundation; it does not stream to
a desktop yet.

## Current features

- Camera permission flow with a clear denied state
- CameraX lifecycle-aware preview
- Front/back camera switching when both are available
- Torch control when supported by the active camera
- Tap-to-focus and exposure metering
- Zoom slider using CameraX's normalized linear zoom
- Camera lifecycle and error status
- QR pairing with a signed phone identity and desktop acknowledgement
- Android 17 local-network runtime permission handling

## Requirements

- Android 8.0 (API 26) or newer
- Android SDK 37
- JDK 17 through 26 (the project wrapper supports the current JDK 26)

## Build

From this directory:

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Next milestone

The next Android milestone is a capability probe followed by a MediaCodec H.264
encoder whose output is independent of the preview surface. Networking will be
added only after encoded frames and timestamps have deterministic tests.
