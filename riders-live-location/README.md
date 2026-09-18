# Rider's Live Location

A privacy-first, offline location-sharing app for groups of riders. Two or
more people create a private ride session and see each other's live GPS
position over Bluetooth Low Energy — **no mobile data, Wi-Fi, or internet
connection required during the ride.**

```
GPS → local validation → AES-256-GCM encryption → BLE → nearby rider
      → optional relay → another rider → decrypt + verify → local DB → map
```

## What's actually in this folder

This project ships in three tiers of completeness, and it's important to
be honest about which is which:

| Layer | Status |
|---|---|
| `python/` — protocol, crypto, ride lifecycle, distance/ETA | **Complete and tested.** 42 unit tests, real AES-256-GCM + X25519 via the `cryptography` library. This is the reference spec for the wire format and security rules. |
| `database/schema.sql` | **Complete and validated** against SQLite. |
| `frontend/` — HTML/CSS/JS UI | **Complete and runnable**, including an in-browser Bluetooth-mesh **simulator** so you can click through the whole app (create ride, see a group, live map, dashboard, settings) without any hardware. Open `frontend/index.html` directly in a browser to try it. |
| `mobile/android/` — Kotlin BLE/GPS/crypto/service layer | **Real, production-shaped source code** implementing the actual Android APIs (BluetoothLeAdvertiser/Scanner, GATT server/client, FusedLocationProviderClient, Android Keystore, a foreground service, a WebView↔JS bridge). **It has not been compiled or run on a device** — this sandbox has no Android SDK, emulator, or real BLE radio. Opening it in Android Studio, resolving dependencies, and testing on physical phones (per the multi-device test plan in `docs/`) is the remaining work, exactly as phased in the "Development approach" below. |

In short: the security/protocol core is genuinely finished and tested. The
mobile shell is a strong, realistic starting point, not a shipped app —
getting from here to an installable APK needs Android Studio and real
hardware, which no cloud sandbox can substitute for.

## Project structure

```
riders-live-location/
├── frontend/            HTML5 + CSS3 + JS UI (also runs standalone in a browser)
├── python/               Reference implementation: protocol, crypto, ride logic, tests
├── mobile/android/       Android app (Kotlin) — BLE mesh, GPS, crypto, service, bridge
├── database/schema.sql   SQLite schema, local-only
├── docs/                 Architecture, security, privacy, protocol docs
├── SECURITY.md
├── PRIVACY.md
└── README.md             (this file)
```

## Running what's runnable today

### Python core + test suite
```bash
cd python
pip install -r requirements.txt
pytest tests/ -v
```
All 42 tests should pass. This is the best place to read to understand the
exact protocol, since it's the part that's fully verified.

### Frontend demo (no phone needed)
```bash
cd frontend
python3 -m http.server 8000
# open http://localhost:8000 in a browser
```
Click **CREATE RIDE** — the app will spin up 3 simulated riders moving
around a demo location, complete with live map, connection status,
relay-hop indicators, and a group dashboard with distance/ETA. This is the
same UI code that ships inside the Android WebView; it detects whether
`window.AndroidBridge` exists and only falls back to the simulator when it
doesn't (see `frontend/js/bluetooth-ui.js`).

### Android app
```bash
cd mobile/android
# Open this folder in Android Studio (Koala or newer), let it sync Gradle,
# then Run on a physical device with Bluetooth LE (an emulator cannot
# exercise real BLE radios). minSdk 26.
```
Expect to need to: resolve/adjust dependency versions against whatever
Android Studio/AGP version you're on, finish the handshake wiring noted as
TODOs in `RideForegroundService.kt`, and supply real app icons.

## Development approach (as originally phased)

1. Project scaffold, Create/Join Ride, random ride code — **done** (Python + Kotlin `RideManager` + frontend screens).
2. Local GPS + map + current-location marker — **done** (`GpsManager.kt`, `frontend/js/map.js`).
3. BLE discovery, secure handshake, rider authentication — **scaffolded** (`BleMeshManager.kt`, `CryptoManager.kt`); handshake wiring is the next concrete task.
4. Two-rider location exchange — protocol complete and tested in Python; Android wiring pending device testing.
5. Group rides — data model and UI complete; multi-connection GATT behavior needs device testing.
6. Multi-hop relay — protocol logic complete and tested (`protocol.should_relay`, `ReplayGuard`); Android relay forwarding implemented in `BleMeshManager.handleIncomingFragment`, needs device testing.
7. Destination sharing — complete end-to-end (Python, Kotlin, frontend).
8. Offline map / routing / distance / ETA — distance & ETA complete and tested; offline *routing* (road distance) is left as a documented extension point (`distance.OfflineRoutingProvider`) since it depends on which offline map data you bundle.
9. Security hardening (rotation, revocation, replay) — complete and tested in the Python reference; ported to Kotlin.
10. Testing — Python unit tests complete; the multi-device Bluetooth/battery/security test plan in `docs/architecture.md` is written but requires physical phones to execute.

## Known limitations (see docs/ for detail)

- No true "Bluetooth mesh" radio mode is used — this is store-and-forward relay over ordinary BLE GATT connections, bounded by how many simultaneous connections a phone's BLE stack supports (typically ~4-8). See `docs/architecture.md`.
- Offline road-routing/ETA precision depends on what offline map data you bundle; without it, distance falls back to honest straight-line distance, never mislabeled as road distance.
- The frontend's map is a minimal canvas renderer for demo purposes, not a real basemap; production should point `frontend/js/map.js`'s render target at MapLibre GL reading locally downloaded MBTiles (the Android Gradle file already depends on `org.maplibre.gl:android-sdk`).
- The QR "Show QR" screen renders a visual mockup of a QR-style grid, not a scannable ISO/IEC 18004 code — wire in a real QR encoder (e.g. ZXing on Android) for production; see the comment in `frontend/js/map.js`.
- This has not been security-audited by a third party. Do not treat it as audited before real-world use — see `SECURITY.md` for the documented threat model and its limits.
