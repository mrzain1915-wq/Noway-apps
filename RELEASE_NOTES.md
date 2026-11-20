# NOWAY Edge Scanner v2.3.1 — Android Mobile

v2.3.1 fixes the two things reported after v2.3.0: scans now cover only the range you give, and history can be cleared from inside the app.

## Scan only the range you give

- The Targets box no longer comes pre-filled with example targets, so a scan can only cover what you type.
- A live "Scan plan" card under the Targets box shows the first target, the last target and the total number of checks before you start, so you can see the exact range that will be scanned.

## Clear history

- The History screen has a Clear history button (with a confirmation step). It deletes every saved scan job and its stored results. It is disabled while a scan is active.

## Install fix

- The debug build now signs with one pinned debug key committed in the project, so future cloud builds carry the same signature and install as updates instead of failing with "App not installed". This is a debug key only, never for a Play Store release.

## Download

- `NOWAY-CDN-CIDR-Scanner-v2.3.1-Android.apk` — native Android 6.0+ application.
- `SHA256SUMS-Android.txt` — SHA-256 hash for the APK.

> Use only on systems, domains and address space you own or have explicit permission to test.

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
