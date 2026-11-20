# NOWAY Android — install and use

NOWAY v2.3.1 is a native Android app for authorized CDN, CIDR, HTTP, TLS and WebSocket diagnostics. It runs locally, keeps resumable checkpoints, and does not require a NOWAY account.

> Scan only systems, domains and address space you own or have explicit written permission to test.

## Requirements

- Android 6.0 (API 23) or newer.
- Wi-Fi is recommended for Fast, Heavy and Extreme modes.
- Enough free storage for the APK plus locally saved scan evidence.

## Install the APK

1. Open the repository's [Releases](https://github.com/drabkb786/Noway-apps/releases) page on the phone.
2. Open **NOWAY v2.3.1 — Android Mobile**.
3. Download `NOWAY-CDN-CIDR-Scanner-v2.3.1-Android.apk`.
4. If Android asks, allow **Install unknown apps** for the browser or file manager you used.
5. Open the downloaded APK, choose **Install**, then launch **NOWAY**.

The GitHub Actions build is debug-signed for direct sideloading. If Android says an older NOWAY build has an incompatible signature, export any evidence you need, uninstall the older build, and then install v2.3.1.

## Verify the download

Download `SHA256SUMS-Android.txt` from the same release. In Termux, compare it with:

```bash
cd ~/storage/downloads
sha256sum NOWAY-CDN-CIDR-Scanner-v2.3.1-Android.apk
cat SHA256SUMS-Android.txt
```

The two SHA-256 values must be identical.

## First authorized scan

1. Open **Scan**.
2. Paste one URL, hostname, IP, inclusive range or CIDR per line, or tap **Import TXT / CSV**.
3. Choose ports, path, protocols and probe mode.
4. Select a mobile performance profile.
5. Tick **I am authorized to check every target**.
6. Tap **Start scan**. High-load plans require one extra confirmation.
7. Use **Dashboard** for progress, **Results** for evidence, and **History** to reopen or resume checkpoints.

Examples:

```text
https://example.com
scan.example.net
192.0.2.10
192.0.2.20-192.0.2.30
192.0.2.0/28
```

## Mobile performance profiles

| Profile | Workers | Rate limit | Best use |
|---|---:|---:|---|
| Eco | 8 | 20/s | Long jobs and minimum heat/battery use |
| Safe | 16 | 50/s | Older or low-memory phones |
| Balanced | 32 | 100/s | Recommended starting point for most phones |
| Fast | 48 | 160/s | Modern phones on stable Wi-Fi |
| Heavy | 64 | 220/s | Charging, strong Wi-Fi, short sessions |
| Extreme | 96 | 320/s | High-end phones with cooling |
| Custom | 1–256 | 0–2000/s | Manual tuning and controlled testing |

`0` rate means no app-side rate cap. It does not guarantee that speed: DNS, latency, router/NAT limits, phone temperature and the target service still control real throughput.

For a typical phone, start at **Balanced**. Move to **Fast**, then **Heavy**, only while timeout, reset and network-error counts remain stable. Custom values above 96 workers or above 500 checks/sec require a second confirmation.

## Background and long scans

- Accept notification permission so Android can show the foreground scan service.
- In **Advanced edge routing**, enable **Run on Wi-Fi only** or **Run only while charging** when useful.
- Pause saves the current checkpoint; Resume continues from it.
- Android battery optimization may still stop long background work on some brands. If that happens, allow unrestricted battery use for NOWAY in Android App info.

## Results and privacy

- Matching responses and CDN evidence are saved locally by default.
- **Store all responses** saves non-matching responses too and can use much more storage.
- Saved authentication-related headers are redacted.
- Use the download icon in **Results** to export CSV evidence.
- NOWAY includes no ads, analytics, account login, exploit execution or remote-control feature.

## Troubleshooting

| Problem | Fix |
|---|---|
| Start does nothing | Scroll to the authorization card, tick it, and correct the red validation message if shown. |
| Install is blocked | Allow **Install unknown apps** for the app that opened the APK, then retry. |
| App will not update | Uninstall the older debug-signed build, then install v2.3.1. |
| Phone becomes hot | Pause, select Balanced/Safe, lower workers, and use Wi-Fi while charging. |
| Many timeouts/resets | Reduce workers and rate before increasing timeout or retries. |
| Scan pauses in background | Allow notifications and unrestricted battery use for NOWAY. |

## Build from source

Use Java 17 and Gradle 8.9:

```bash
gradle :android:testDebugUnitTest :android:assembleDebug --stacktrace --no-daemon
```

The APK is created at `android/build/outputs/apk/debug/android-debug.apk`. The release workflow also checks the APK archive, Android package ID, version metadata and APK signature before publishing it.
