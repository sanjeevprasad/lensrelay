# Contributing to LensRelay

Thank you for helping build LensRelay. The project is pre-alpha, so the most
valuable early work is small, measurable, and easy to replace as we learn.

## Before contributing

1. Search existing issues before opening a new one.
2. Open an issue before large changes or new dependencies.
3. Keep pull requests focused on one problem.
4. Include tests or reproducible verification steps where practical.
5. Do not commit credentials, signing material, recordings, or personal data.

## Design principles

- Camera data stays local unless a user explicitly configures otherwise.
- The default setup should work without an account or cloud service.
- Prefer existing operating-system interfaces over custom kernel code.
- Measure latency, CPU use, memory use, and power consumption before
  optimizing.
- Keep platform-specific code behind small, documented interfaces.
- Avoid proprietary runtime components in the default build.

## Commit and pull request guidance

Use short, imperative commit subjects, for example:

```text
Add Android camera capability probe
Handle desktop receiver reconnect
Document H.264 timestamp semantics
```

Pull requests should explain:

- the problem being solved;
- the approach and important trade-offs;
- how the change was tested;
- any affected platforms or devices.

By submitting a contribution, you agree that it is licensed under the Apache
License 2.0 used by this repository.
