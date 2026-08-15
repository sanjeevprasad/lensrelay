# LensRelay Desktop

The LensRelay desktop receiver is a small [Tauri 2](https://v2.tauri.app/)
application for Windows and Linux. Its frontend uses Tailwind CSS through
Vite. This first scaffold establishes the UI,
typed IPC boundary, and platform-adapter seam; it does not receive video yet.

## Current behavior

- Shows the receiver and phone connection state.
- Listens on TCP port `53417` for the signed, one-time Android pairing acknowledgement.
- Reports the host OS and the planned virtual-camera backend.
- Keeps platform-specific code behind a Rust `VirtualCameraAdapter` trait.
- Exposes serializable receiver types through two Tauri commands:
`get_receiver_status` and `get_platform_info`.

The operating-system firewall must allow LensRelay Desktop to accept local
network connections on TCP port `53417`.

On Linux the planned output backend is the system `v4l2loopback` device. On
Windows the planned backend is the Windows 11 Media Foundation virtual-camera
API. The scaffold does not install a driver or kernel module.

## Development

Prerequisites are the standard Tauri dependencies for your operating system,
plus a current Node.js release and the Rust stable toolchain. See the official
[Tauri prerequisites](https://v2.tauri.app/start/prerequisites/).

For Arch Linux, install the official prerequisite set with:

```bash
sudo pacman -Syu
sudo pacman -S --needed \
  webkit2gtk-4.1 \
  base-devel \
  curl \
  wget \
  file \
  openssl \
  librsvg
```

For Debian or Ubuntu, the official prerequisite set is:

```bash
sudo apt install libwebkit2gtk-4.1-dev build-essential curl wget file \
  libxdo-dev libssl-dev libayatana-appindicator3-dev librsvg2-dev
```

```bash
npm install
npm run tauri dev
```

Useful non-GUI checks:

```bash
npm run check
cargo test --manifest-path src-tauri/Cargo.toml
```

Build distributable packages with `npm run tauri build`. Linux and Windows
packages should be produced on their respective operating systems rather than
cross-compiled.

## Layout

```text
desktop/
  src/                    Framework-free TypeScript UI
  src-tauri/src/          Rust application and command boundary
  src-tauri/src/platform/ Platform virtual-camera adapters
```

The video transport and decoder will live below the Rust boundary. Video
frames must not be sent through JSON IPC; the frontend should receive only
lightweight session state and control events.
