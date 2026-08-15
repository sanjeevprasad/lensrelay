# LensRelay protocol

The LensRelay wire protocol is not yet specified. This directory will contain
the versioned specification, message schemas, test vectors, and compatibility
fixtures once the initial transport prototype has been measured.

## Requirements

The protocol must support:

- capability negotiation before streaming;
- explicit protocol and message versions;
- H.264 video with timestamps and keyframe identification;
- camera-control commands and acknowledged results;
- connection loss and session resumption;
- authenticated pairing on an untrusted local network;
- forward-compatible optional fields;
- diagnostic errors that do not expose sensitive data.

The protocol must not require a LensRelay cloud service. Transport selection
is intentionally deferred until latency, packet-loss behavior, complexity, and
USB reuse have been compared in a prototype.
