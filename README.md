# LensRelay

Turn your phone into an open webcam.

LensRelay is a free, open-source project that aims to turn an Android phone
into a system webcam for Windows and Linux over a local Wi-Fi or USB
connection.

> [!IMPORTANT]
> LensRelay is in the planning and prototyping stage. There is no usable
> release yet, and the architecture may change as latency and compatibility
> are measured on real devices.

## Goals

- Keep video local: no account, cloud service, telemetry, ads, or watermark.
- Provide a straightforward setup for non-technical users.
- Treat Windows and Linux as first-class desktop platforms.
- Use Android hardware video encoding when available.
- Keep every LensRelay component auditable and open source.
- Document the wire protocol so independent clients can interoperate.

## Initial scope

The first milestone is deliberately small:

- Android phone as the camera
- Local Wi-Fi transport
- H.264 video at 720p and 1080p
- Windows 11 virtual-camera output through Media Foundation
- Linux virtual-camera output through the standard `v4l2loopback` module
- Front/back camera selection, focus, zoom, and torch controls

USB transport, audio, 4K, multiple phones, Windows 10, and iOS are outside the
first milestone.

## Architecture

```text
Android app                         Desktop app
CameraX -> MediaCodec -> transport -> receiver -> decoder -> frame pipeline
                                                        |-> Windows virtual camera
                                                        `-> Linux v4l2loopback
```

- The Android application will be written in Kotlin using CameraX and
  MediaCodec.
- The shared desktop receiver and platform adapters will be written in Rust.
- Linux will use the existing `v4l2loopback` kernel module; LensRelay does not
  plan to maintain a custom kernel module for the initial release.
- Windows 11 will use the Media Foundation virtual-camera APIs.

See [the architecture notes](docs/architecture.md) and
[roadmap](docs/roadmap.md) for more detail.

## Repository layout

```text
android/   Android camera application
desktop/   Shared Rust receiver and OS-specific virtual-camera adapters
protocol/  Wire protocol specification and interoperability material
docs/      Architecture, roadmap, and project documentation
```

The repository is documentation-first while the initial prototypes establish
the protocol, latency budget, and platform constraints.

## Contributing

Early contributions are welcome, especially compatibility research and small
prototypes. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a
pull request. Architectural changes should begin as an issue so trade-offs can
be recorded before implementation.

## Security

LensRelay handles live camera and microphone data. Please report security
issues privately using the process in [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
