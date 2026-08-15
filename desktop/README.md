# LensRelay Desktop

LensRelay Desktop is a Tauri 2 receiver for Windows and Linux. Its Tailwind UI
shows the encrypted MoQ preview, paired phones, live phone presence, and
capability-driven camera controls. Rust owns persistent identities, pairing,
the pinned TLS control server, the local MoQ relay, and platform adapter
boundaries.

Local ports are TCP `53417` for pairing, QUIC `53418` for MoQ media, and TCP
`53419` for TLS control. Permit them in the host firewall on trusted local
networks.

Linux uses the standard `v4l2loopback` device; Windows targets the Windows 11
Media Foundation virtual-camera API. LensRelay does not install a custom kernel
module. The actual decoded-frame sinks are still under development.

Install the normal [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/).
On Arch Linux this includes `webkit2gtk-4.1`, `base-devel`, `openssl`, and
`librsvg`; install `v4l2loopback-dkms` for the virtual camera.

```bash
npm install
npm run tauri dev
```

Useful checks:

```bash
npm run check
cargo test --manifest-path src-tauri/Cargo.toml
```
