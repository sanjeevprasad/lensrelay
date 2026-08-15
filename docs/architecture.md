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
│ CameraX -> hardware H.264 -> WebRTC media + DataChannel │
└──────────────────────────────┬───────────────────────────┘
                               │ local Wi-Fi (MVP)
┌──────────────────────────────▼───────────────────────────┐
│ Rust desktop core                                        │
│ pairing -> WebRTC receive -> decode -> timing -> frames  │
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

### Local WebRTC transport

The MVP uses peer-to-peer WebRTC over the local Wi-Fi network. H.264 is the
preferred video codec so the phone and desktop can use hardware acceleration.
A WebRTC data channel carries session state and camera controls such as lens,
torch, and quality changes.

Discovery and signaling remain local. The MVP does not use public signaling,
STUN, TURN, or cloud media relays. WebRTC supplies encrypted DTLS/SRTP media,
packet-loss handling, congestion feedback, and codec negotiation; it does not
own desktop decoding or the virtual-camera frame sink.

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
- Restrict WebRTC to local candidates for the Wi-Fi-only MVP.
- Do not log video, audio, credentials, or reusable pairing secrets.
- Threat-model local-network attackers before stabilizing the protocol.

## Open decisions

- Local discovery and WebRTC signaling mechanism
- Rust WebRTC implementation and Android native integration
- Pairing and session key exchange
- Decoder implementation and distribution strategy
- Pixel format at the frame-sink boundary
- Android minimum version after device testing
- Packaging and signing for Windows and major Linux distributions
