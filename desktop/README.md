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
module. The Linux adapter can publish a native 1280x720 YUY2 test pattern to
validate the loopback device and receives decoded preview frames from the
desktop media surface for the current end-to-end prototype.

Install the normal [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/).
On Arch Linux this includes `webkit2gtk-4.1`, `base-devel`, `openssl`, and
`librsvg`; install `v4l2loopback-dkms` for the virtual camera.

Load one output-only device with the label LensRelay discovers:

```bash
sudo modprobe v4l2loopback devices=1 card_label="LensRelay Camera" exclusive_caps=1
```

After a kernel upgrade, reboot into the kernel for which DKMS built the module
before running `modprobe`. To load the device at boot, put `v4l2loopback` in
`/etc/modules-load.d/lensrelay.conf` and the options above (without `modprobe`)
in `/etc/modprobe.d/lensrelay.conf`.

```bash
npm install
npm run tauri dev
```

Useful checks:

```bash
npm run check
cargo test --manifest-path src-tauri/Cargo.toml
```
