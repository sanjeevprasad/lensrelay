# Roadmap

The roadmap is ordered by technical risk. Dates will be added only after the
end-to-end prototype establishes realistic effort and compatibility.

## Phase 0: feasibility prototypes

- [ ] Feed a generated test pattern into `v4l2loopback` on Linux.
- [ ] Register and feed a Media Foundation virtual camera on Windows 11.
- [ ] Capture and hardware-encode H.264 on representative Android devices.
- [ ] Measure end-to-end latency over a local Wi-Fi network.
- [ ] Select the initial transport and document the decision.

## Phase 1: minimal end-to-end webcam

- [ ] Pair one Android phone with one desktop receiver.
- [ ] Stream H.264 video at 720p and 1080p.
- [ ] Expose the stream as a webcam on Windows 11 and Linux.
- [ ] Support front/back camera selection, focus, zoom, and torch.
- [ ] Recover cleanly from network and application restarts.
- [ ] Test Meet, Zoom, Teams, Discord, OBS, and browser WebRTC capture.

## Phase 2: usable alpha

- [ ] Add authenticated pairing and encrypted sessions.
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
