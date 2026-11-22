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
  6. **Persistent BLE Scanner (`StandaloneRadioDataSource`):** Single class-level `BluetoothLeScanner` session (`SCAN_MODE_LOW_LATENCY`, `emptyList()` filters) emitting into a `MutableSharedFlow`; never stopped/restarted on `PURGE SCANS` or tab switches, with `onScanFailed` backoff (`30s` on `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, `3s` transient).
  7. **35s Rolling Cell Tower Cache (`CellularMonitor`):** Keyed by `(generation, pci, arfcn)` via `mergeCellTowerCache()` to prevent serving-only baseband sweeps from dropping neighbor towers.
  8. **Safe RPA-Only MAC Collapse (`DeviceFingerprintEngine` / `RadarViewModel`):** Collapse into `BLE_FP:...` ONLY when BLE address is Randomized/RPA AND has `>= 6` non-flags payload bytes; Wi-Fi BSSIDs and Public/Static BLE MACs key uniquely by MAC/BSSID while displaying `FP-XXXXXXXX`.
  9. **Strict Drone Signatures (`DroneDetectorEngine`):** Zero `|dRSSI/dt|` fast-fader heuristics. Detect ONLY on BLE `0xFFFA` (`0x16` AD), Wi-Fi Vendor IEs `FA:0B:BC:0D` / `26:37:12`, or verified IEEE Drone OUIs (DJI, Parrot, Skydio `38:1D:14`, Autel, Yuneec, Hubsan) gated by Drone SSID prefixes.
  10. **Unclamped `5m..200m` UI Camera Lens (`RadarCanvas` / `BackgroundEmitterTracker` / `BayesianGridFilter`):** `userPose` and emitter projections (`0.5m..250m`) are unbounded; `BayesianGridFilter` dynamically re-anchors `(gridOriginX, gridOriginY)` on lock/`RST HEATMAP`; camera pan/zoom is a pure UI viewport offset with floating `⌖ RE-CENTER`.
  11. **Unified SIGINT Forensic Dossier (`SignalDossierSheet` / `SigintForensics`):** Displays `RF SIGNAL ENVELOPE`, `LOCALIZATION & CONVERGENCE`, `RF FINGERPRINT & TIMING` (real `Δt`, sample `σ`, `ppm` skew, Wi-Fi IE tags & Vendor OUIs), `THREAT & MAC HISTORY` (`Tail-Chase` 0–100 score), and sticky `◎ Fox Hunt` / `👁 Watch` / `🚫 Mute` actions.
  12. **SQLite v2 Schema (`SessionLogDbHelper`):** `DATABASE_VERSION = 2` preserving `scan_snapshots` while adding `watchlist_entries` and `perimeter_events` (3-packet hysteresis `PerimeterGuardEngine`).
