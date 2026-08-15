# Roadmap

The roadmap is ordered by technical risk. Dates will be added only after the
end-to-end prototype establishes realistic effort and compatibility.

## Phase 0: feasibility prototypes

- [ ] Feed a generated test pattern into `v4l2loopback` on Linux.
- [ ] Register and feed a Media Foundation virtual camera on Windows 11.
- [ ] Capture and hardware-encode H.264 on representative Android devices.
- [ ] Measure end-to-end latency over a local Wi-Fi network.
- [x] Select MoQ over QUIC as the initial media transport.

## Phase 1: minimal end-to-end webcam

- [x] Complete mutual pairing between one Android phone and one desktop receiver.
- [ ] Validate the Android-to-Tauri MoQ preview on representative devices.
- [ ] Stream H.264 video at 720p and 1080p.
- [ ] Expose the stream as a webcam on Windows 11 and Linux.
- [x] Add capability-driven front/back, focus, zoom, exposure, torch, and quality controls.
- [ ] Recover cleanly from network and application restarts.
- [ ] Test the virtual camera in Meet, Zoom, Teams, Discord, OBS, and browsers.

## Phase 2: usable alpha

- [x] Add authenticated pairing, mutual unpair, certificate pinning, and encrypted sessions.
- [ ] Add automatic local discovery with a manual fallback.
- [ ] Add installers and dependency diagnostics.
- [ ] Add hardware decoder selection and performance telemetry that remains
      entirely local.
- [ ] Publish compatibility and troubleshooting documentation.

## Later possibilities

- USB transport
- Virtual microphone and audio synchronization
- 4K and high-frame-rate modes
- Multiple concurrent phones
- Windows 10 support
- OBS-native source
- Additional mobile and desktop platforms
