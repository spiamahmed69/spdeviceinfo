# SP Device Info

Premium Android app that shows ALL real device information, using only real
Android APIs — never fake or simulated values.

This is the fix for the Device Info web app: Chrome freezes the User-Agent
string to `Linux; Android 10; K` for every Android phone, so no website can
see the real model, Android version, CPU, RAM or storage. A native app can.

## What it shows

- **Device:** model, manufacturer, brand, device, product, board, hardware,
  fingerprint, serial (where Android exposes it)
- **System:** Android version, SDK, security patch, bootloader, radio version,
  kernel, uptime
- **CPU:** supported ABIs, processor count, /proc/cpuinfo
- **Memory:** total / available RAM, low-memory state
- **Storage:** internal + external total / free space
- **Battery:** live level, health, temperature, voltage, technology, charging
  status (live via BroadcastReceiver)
- **Display:** resolution, density, DPI, refresh rate
- **Network:** connection type, Wi-Fi SSID, local IP, public IP
- **Sensors:** full SensorManager list with name, vendor and type

Plus search/filter, Refresh, Copy report, Share report and Export .txt.

## Permissions

Only `INTERNET`, `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE`.
No `READ_PHONE_STATE` / IMEI — Android 10+ blocks it. Anything Android
hides is labelled "Not exposed by Android" honestly.

## Build

GitHub Actions (`.github/workflows/build.yml`, JDK 17, Gradle 8.7, AGP 8.5.2)
builds the debug APK on every push to `main` and publishes a release.
First release is v1.0.1.

Package: `com.spsiam.spdeviceinfo`, version 1.0.0.
