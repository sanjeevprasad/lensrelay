# Local streaming and control

LensRelay sends encoded Android camera video to the desktop with Media over
QUIC (MoQ). Both devices connect directly on the local network; LensRelay has
no account, cloud signaling, or media server.

## Connections

- TCP `53417`: short-lived QR pairing and authenticated unpair fallback.
- QUIC `53418`: encrypted MoQ media. The QR pins the desktop TLS certificate.
- TLS/TCP `53419`: persistent control connection initiated by the phone.

The control hello is signed by the phone's Android Keystore P-256 identity.
The desktop accepts it only for a previously paired phone. The phone also
checks the certificate fingerprint saved from the pairing QR, so both ends are
authenticated.

The phone publishes capabilities and current state. The desktop enables only
controls reported by that phone. Torch, zoom, exposure and focus are applied
live; camera, size, frame rate, bitrate, codec and
stabilization restart the media session. Requested size is a preference and
CameraX reports the size actually negotiated with the device.

Desktop preview rotation and mirroring are local output preferences. They are
never sent to the phone and do not restart camera capture. The phone preview
always follows CameraX's natural display orientation.

Remote camera start is disabled per desktop by default. Android prompts the
user to allow once or always, and the normal Android camera privacy indicator
remains visible. The control connection currently lives while the Android app
is in the foreground; background control requires a dedicated foreground
service and is a later milestone.

The Tauri preview validates the transport. Feeding decoded frames to Linux
`v4l2loopback` and the Windows Media Foundation virtual-camera sink remains
separate work.
