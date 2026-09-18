# Bluetooth protocol

## Service and characteristics

```
Service UUID:            7d2b7c10-9c1e-4b7a-8f2e-1a2b3c4d5e6f
  Location characteristic:   7d2b7c11-...  NOTIFY, WRITE
  Handshake characteristic:  7d2b7c12-...  READ, WRITE
```

Only the service UUID is ever placed in a BLE advertisement packet — no
ride ID, rider ID, or location data is broadcast in the clear (see
`SECURITY.md` threat #2 and `PRIVACY.md`).

## Join flow

```
Primary rider                              Joining rider
─────────────                              ─────────────
generate_ride_code()  ────(spoken/typed/QR)──▶  enters code
generate X25519 keypair                          generate X25519 keypair
share public key (out of band / QR)  ◀────────▶  share public key
                     ── BLE discovery + GATT connect ──
                     ── application-level handshake ──
     both sides: derive_session_key(private_key, peer_public, ride_id, ride_code)
                     ── session key now shared, ride code no longer needed ──
```

Both sides independently compute the same 32-byte AES key via X25519
ECDH + HKDF-SHA256 (`python/security.py::derive_session_key`,
`CryptoManager.kt::deriveSessionKey` — kept behaviorally identical).
The human-entered ride code is folded into HKDF's `info` parameter as
context binding, not used as key material directly.

## QR payload

If "Show QR" is used, the QR payload is the minimum needed to join:

```json
{ "code": "583921", "rideId": "<uuid>" }
```

It **never** contains a private key, a session key, or any rider's
location. `frontend/js/map.js`'s `renderQrMockup` is a visual mockup for
this demo, not a scannable encoder — production should generate a real
ISO/IEC 18004 QR code (e.g. via ZXing on Android) carrying exactly this
payload.

## Location packet wire format

Plaintext fields (`python/protocol.py::LocationPacket`), JSON-encoded
then encrypted:

```json
{
  "ride_id": "...", "rider_id": "...", "packet_id": "...",
  "latitude": 0.0, "longitude": 0.0,
  "speed_mps": 0.0, "heading_deg": 0.0,
  "timestamp": 0.0, "sequence_number": 0,
  "hop_count": 0, "hop_limit": 5,
  "destination": null
}
```

Encrypted wire format (`build_encrypted_packet`):

```
[1 byte key_epoch] [4 bytes sha256(ride_id)[:4]] [12-byte AES-GCM nonce] [ciphertext+16-byte tag]
```

The key epoch and a truncated ride-ID hash travel outside the
ciphertext as associated data (AEAD "AAD") — authenticated but not
secret — so a receiver can cheaply reject wrong-ride or stale-key-epoch
traffic before attempting a decryption.

## BLE fragmentation frame (per GATT write)

```
[1 byte fragment_index] [1 byte fragment_count] [1 byte hop_count]
[2 bytes total_length_hint] [chunk of the encrypted blob above]
```

`hop_count` here is a **separate, mutable, unencrypted** byte — a relay
increments only this byte on its outgoing copy of each fragment. It
never touches, decrypts, or needs the session key to do this, and the
AEAD tag protecting the actual location fields is untouched by
relaying. See `ble/PacketFramer.kt`.

## Relay / forwarding rule

On receiving a fully reassembled encrypted blob that isn't from a
packet this device has already forwarded (`ReplayGuard`/`packet_id`
dedup) and whose `hop_count` hasn't hit its limit, a device re-sends it
to every *other* currently connected peer, with `hop_count` incremented
by one. This is what allows `A → B → C` delivery without B needing to
manually relay anything, subject to the connection-count limits
discussed in `docs/architecture.md`.

## Revocation

On participant removal, remaining devices generate fresh X25519
keypairs and re-derive session keys (`rotate_session_key` /
`RideManager.kt::removeParticipant`). The removed device's old key
epoch is rejected by `open_encrypted_packet`'s epoch check going
forward.
