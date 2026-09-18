# Architecture

## Component map

```
frontend/ (HTML5/CSS3/JS)
    │  runs inside a WebView, talks only through window.AndroidBridge
    ▼
mobile/android/ (Kotlin)
    ├── MainActivity.kt          WebView host + JS bridge
    ├── RideManager.kt           Ride lifecycle/roles (mirrors python/ride_manager.py)
    ├── ble/BleMeshManager.kt    Scan/advertise/GATT/relay
    ├── ble/PacketFramer.kt      Fragmentation + reassembly for MTU limits
    ├── ble/ReplayGuard.kt       Replay/duplicate protection (mirrors python/protocol.py)
    ├── gps/GpsManager.kt        Adaptive-interval FusedLocationProviderClient wrapper
    ├── security/CryptoManager.kt  X25519 + AES-256-GCM + Keystore-backed storage
    ├── permissions/PermissionsManager.kt
    └── service/RideForegroundService.kt  Ties the above together while a ride is ACTIVE

python/  — reference implementation + spec: protocol.py, security.py,
           ride_manager.py, location_manager.py, distance.py, eta.py,
           and the test suite that pins their exact behavior.

database/schema.sql — local-only SQLite schema, shared conceptually by
           both the Android Room/SQLCipher layer and this documentation.
```

## Why this split of Python / Kotlin / JS

- **Python** carries the protocol logic that most benefits from being
  written once, precisely, and tested exhaustively without a device —
  packet format, crypto construction, replay rules, distance/ETA math,
  ride state machine. It's the spec other implementations are checked
  against, and it's directly useful for desktop tooling (e.g. a test
  harness that fakes a multi-device ride).
- **Kotlin/Android** is where GPS, Bluetooth LE, the OS permission
  model, Keystore-backed encryption, and background execution actually
  live — none of that is reachable from Python or from a browser.
- **HTML/CSS/JS** is the UI layer, chosen so the interface can be
  iterated on and literally demoed in a browser (see the in-browser
  simulator in `frontend/js/bluetooth-ui.js`) without needing a device
  for every UI change, while still running for real inside a WebView on
  the actual phone.

## What "Bluetooth relay/mesh" actually means here

Android has no consumer-app-accessible Bluetooth Mesh profile comparable
to the dedicated Bluetooth Mesh specification, and no OS-level primitive
that transparently routes packets across arbitrary nearby phones. What
this app implements instead, and what "relay" means throughout this
codebase, is **store-and-forward over ordinary BLE GATT connections**:

1. Every device both advertises a custom GATT service (so ride
   participants can find each other) and scans for the same service
   UUID.
2. On finding another participant of the same ride, it opens a normal
   BLE GATT connection and completes an application-level handshake.
3. Encrypted location packets are pushed as GATT writes/notifications
   to every directly connected peer.
4. A device that receives a packet not authored by itself re-forwards
   it to its *other* connected peers, decrementing a hop budget and
   deduplicating via `ReplayGuard`/`packet_id` so the same packet isn't
   forwarded forever.

This genuinely gets a location update from rider A to rider C via rider
B without B doing anything manual — but it inherits a real hardware
limit: **a single Android BLE adapter typically supports somewhere
around 4-8 simultaneous GATT connections**, varying by chipset/OEM/OS
version. This is why `BleMeshManager.MAX_RECOMMENDED_PARTICIPANTS` is
set to 8 rather than an arbitrary marketing number — past that, some
participants will only be reachable via relay hops rather than direct
connections, and relay reliability degrades as hop count and total
group size grow. The app surfaces this as a recommended size, not a
hard technical ceiling that's hidden from the user.

## MTU and packet size

An unnegotiated BLE ATT write is usable for roughly 20 bytes of
payload; this app requests an MTU of 247 on every connection (the
practical ceiling most modern stacks support) but cannot guarantee the
peer grants it. `ble/PacketFramer.kt` fragments any encrypted packet
into chunks that fit whatever MTU was actually negotiated, and
reassembles them on the receiving end — see that file's header comment
for the exact frame layout, and note that `hop_count` is deliberately
carried *outside* the encrypted blob so a relay can update it without
holding the session key or invalidating the AEAD authentication tag.

## Recommended group size

Given the connection-count ceiling above, this project recommends
**up to ~8 participants per ride** for reliable direct+relay coverage.
Larger groups are not blocked outright, but expect some participants to
depend on multiple relay hops, with correspondingly higher latency and
lower delivery reliability — this is a property of BLE hardware, not a
limitation this app's software could remove by being written
differently.

## Offline maps

The Android build depends on MapLibre GL (`org.maplibre.gl:android-sdk`)
rendering locally downloaded map tiles (`maps/offline/`), never an
online tile service — the app must work with Wi-Fi, mobile data, *and*
any cloud server all switched off during a ride. An internet connection
is only ever useful *before* a ride, to download a map region. See
`README.md` for why the browser demo in `frontend/` uses a minimal
canvas renderer instead of a real basemap.

## Test plan (requires physical devices)

The following scenarios, listed in the original design brief, are the
right acceptance tests once this runs on real phones — none of them can
be meaningfully executed in a cloud sandbox with no BLE radio:

- Two riders: direct exchange both directions.
- Three riders, chain topology: C receives A's location via B.
- Group ride with dynamic topology (a relay device leaving/rejoining).
- Unauthorized join attempts (wrong/expired code) are rejected.
- Packet tampering is rejected (already covered at the protocol level
  by `python/tests/test_protocol.py`; device tests should additionally
  confirm the UI surfaces this without crashing).
- Replay of an old packet is rejected (protocol-level tests exist;
  device tests confirm end-to-end).
- Airplane mode / no Wi-Fi / no mobile data: ride continues working.
- Bluetooth toggled off/on, or phones moved out of and back into range:
  reconnection succeeds and state resynchronizes without dumping stale
  history.
- GPS accuracy degrading (e.g. indoors): UI reflects reduced accuracy
  rather than showing a falsely confident position.
