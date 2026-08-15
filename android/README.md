# Android application

This directory will contain the LensRelay Android camera application.

## Planned responsibilities

- Discover and report camera capabilities.
- Capture video using CameraX, with Camera2 interop only where required.
- Encode H.264 using MediaCodec and hardware acceleration when available.
- Pair with a desktop receiver on the local network.
- Send video and receive camera-control commands.
- Keep capture active through a visible foreground service when required.
- Expose clear controls for camera, focus, zoom, exposure, and torch.

The minimum supported Android version and exact transport will be chosen after
the first compatibility prototype. The initial target is Android 8 or newer,
but this is not yet a compatibility guarantee.
