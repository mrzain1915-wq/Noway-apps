# NOWAY Edge Scanner v2.3.0 — Android Mobile

v2.3.0 adds a verified native Android APK with phone-focused performance controls while retaining the Windows GUI, Windows CMD and Termux editions from v2.2.0.

## Mobile performance controls

- Eco, Safe, Balanced, Fast, Heavy and Extreme presets from 8 to 96 workers.
- Balanced now starts at 32 workers and 100 checks/sec.
- Custom mode supports 1–256 workers and 0–2000 checks/sec.
- Clear heat, battery and network guidance beside the selected profile.
- A second confirmation for more than 96 workers, more than 500 checks/sec, or a plan above one million checks.

## Real worker concurrency

- Blocking network probes now use an elastic Android I/O dispatcher limited to the selected worker count.
- The existing semaphore remains a strict upper bound, so the chosen setting controls actual simultaneous probes.
- Pause, resume, foreground-service operation and durable checkpoints continue to work with every profile.

## Mobile UI and Start-flow reliability

- The complete scan form remains vertically scrollable on small screens.
- Worker and rate fields automatically switch the selector to Custom.
- Empty or malformed numeric values now show clear validation messages.
- High-load confirmation failures return to the form instead of silently losing the error.
- The build information is updated to Android v2.3.0.

## Verified APK pipeline

The Android release workflow:

1. Runs the Android unit-test suite.
2. Compiles the debug-signed sideload APK with Java 17 and Gradle 8.9.
3. Validates the ZIP structure, APK signature, package ID, version code and version name.
4. Publishes the APK with a separate SHA-256 checksum file only after all gates pass.

## Download

- `NOWAY-CDN-CIDR-Scanner-v2.3.0-Android.apk` — native Android 6.0+ application.
- `SHA256SUMS-Android.txt` — SHA-256 hash for the APK.

The APK is GitHub Actions debug-signed for direct sideloading. If an older APK was signed by a different build runner, Android may require uninstalling that older build before v2.3.0 can be installed. Export any evidence you need first.

The v2.2.0 Windows GUI, Windows CMD and Termux packages remain available in the previous release and use compatible scan concepts.

## Validation record

- [Android v2.3.0 build, unit tests, signature and package verification: passed](https://github.com/drabkb786/Noway-apps/actions/runs/33950554425)

> Use only on systems, domains and address space you own or have explicit permission to test.
