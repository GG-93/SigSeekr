# SIGSEEKER ARCHITECTURAL CONTEXT & INVARIANTS
- **Repository:** https://github.com/GG-93/SigSeekr.git
- **Target Hardware:** Google Pixel 7 (GrapheneOS, Android 14+) & Samsung Galaxy Note 20 Ultra (Android 13+).
- **Core Stack:** 100% Offline Kotlin + Jetpack Compose + SQLite (`SQLiteOpenHelper`, NO Room/KSP) + OkHttp (on-demand map cache only).
- **Strict Invariants:**
  1. Zero external hardware dependencies required (`Esp32UsbBridge` remains isolated/optional).
  2. `AndroidManifest.xml` MUST omit `neverForLocation` on `BLUETOOTH_SCAN`, lock `MainActivity` to `portrait`, and request `READ_PHONE_STATE` only on-demand in the Cellular tab.
  3. Coordinate conversion: `theta = (PI / 2.0) - azimuthRad`; step dead-reckoning uses `x += step * cos(theta)`, `y += step * sin(theta)`.
  4. Kalman smoothed RSSI MUST always be negative (`[-127.0, -1.0] dBm`).
  5. Shield Drop slider range is `0–30 dB` (`Omni 0dB`, `Pocket/Body 16dB`, `Flat Plate 22dB`, `90° Corner 27dB`) with a 5-sample Hampel median filter, tilt/pocket guard (`|roll| > 45°` or `|pitch| < 15°` forces `shieldLoss = 0`), and 2% Laplace smoothing.
