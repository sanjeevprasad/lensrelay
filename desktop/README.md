# Desktop application

This directory will contain the shared Rust desktop receiver and the
platform-specific virtual-camera adapters.

## Planned layout

```text
desktop/
  crates/
    lensrelay-core/       Session, transport, and frame pipeline
    lensrelay-cli/        Diagnostic command-line client
    lensrelay-linux/      v4l2loopback adapter
    lensrelay-windows/    Media Foundation adapter
```

The first prototype should prove that a generated test pattern can be sent to
the operating system's virtual camera before networking and Android capture are
connected to the pipeline.
