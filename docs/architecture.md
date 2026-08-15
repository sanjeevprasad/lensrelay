# Architecture

## Status

This document records the initial direction, not a frozen specification. Major
decisions should be captured in short architecture decision records as the
prototypes produce evidence.

## System overview

LensRelay has three logical layers:

1. The Android capture application selects a camera, captures frames, encodes
   video, and accepts remote camera controls.
2. The desktop core pairs with the phone, receives and decodes video, manages
   timing, and produces normalized frames.
3. A platform adapter presents those frames as a system camera.

```text
┌──────────────────────── Android ────────────────────────┐
│ CameraX -> hardware H.264/H.265 -> MoQ media            │
│ signed TLS control client                               │
└──────────────────────────────┬───────────────────────────┘
                               │ local Wi-Fi (MVP)
┌──────────────────────────────▼───────────────────────────┐
│ Rust desktop core                                        │
│ pairing + control -> MoQ receive -> decode -> frames     │
└─────────────────────┬──────────────────────┬─────────────┘
                      │                      │
              ┌───────▼────────┐     ┌───────▼────────────┐
              │ Linux adapter  │     │ Windows 11 adapter │
              │ v4l2loopback   │     │ Media Foundation   │
              └────────────────┘     └────────────────────┘
```

## Component boundaries

### Android application

The Android application owns camera permissions, capture lifecycle, encoding,
device capability reporting, and user-visible privacy indicators. It should
send encoded video rather than raw frames to reduce bandwidth and use the
phone's hardware encoder.

### Desktop core

The desktop core owns discovery, pairing, session state, transport, decoding,
frame pacing, reconnection, diagnostics, and shared configuration. It must not
depend directly on a Linux or Windows virtual-camera API.

### Local media and control transport

The MVP uses MoQ over QUIC for encoded media on the local Wi-Fi network. A
separate persistent TLS connection carries presence, capabilities, state and
camera commands. Pairing pins the desktop TLS certificate; the phone signs its
control hello with its persistent Android Keystore identity. The phone is the
authority for permissions and applied camera state.

The Tauri preview validates media capture and transport. It is a prototype
boundary, not the final virtual-camera frame path.

### Virtual-camera adapters

Platform code receives normalized video frames through a small internal sink
interface. Linux writes frames to a device provided by standard
`v4l2loopback`. Windows 11 registers and feeds a Media Foundation virtual
camera. No custom LensRelay kernel module is planned for the MVP.

## Preliminary frame-sink boundary

The precise Rust types will be chosen during prototyping, but the conceptual
boundary is:

```rust,ignore
trait VirtualCameraSink {
    fn start(&mut self, format: VideoFormat) -> Result<()>;
    fn send_frame(&mut self, frame: VideoFrame<'_>) -> Result<()>;
    fn stop(&mut self) -> Result<()>;
}
```

This interface should remain narrow. Device installation, permissions, and
platform capability discovery may require separate interfaces.

## Security and privacy baseline

- Bind only to required interfaces and ports.
- Require explicit pairing before a receiver can access camera data.
- Display capture state on the phone.
- Do not depend on internet access for discovery, pairing, or streaming.
- Restrict listeners to the required local-network services.
- Do not log video, audio, credentials, or reusable pairing secrets.
- Threat-model local-network attackers before stabilizing the protocol.

## Open decisions

- Local discovery mechanism
- Background Android control lifecycle
- Decoder implementation and distribution strategy
- Pixel format at the frame-sink boundary
- Android minimum version after device testing
- Packaging and signing for Windows and major Linux distributions
