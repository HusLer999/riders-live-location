# Security architecture

This document describes what Rider's Live Location actually protects
against, how, and — just as importantly — what it does not claim to
protect against. It is not marketing copy. No claim here should be read
as "100% secure"; there is no such thing.

## Design principles

- **Least privilege.** Every component gets only the access it needs. A
  relay node moves ciphertext; it cannot read it. A normal rider cannot
  remove participants or change the destination.
- **Privacy by design.** No real names, phone numbers, or accounts are
  ever required. See `PRIVACY.md`.
- **No invented cryptography.** Every primitive is a direct call into an
  audited library — Python's `cryptography` (OpenSSL) in the reference
  implementation, Android Keystore + BouncyCastle's X25519 + `javax.crypto`
  AES-GCM on device. Nothing here is a custom cipher.

## Cryptographic design

| Purpose | Primitive |
|---|---|
| Session key establishment | X25519 (Curve25519 ECDH) |
| Key derivation | HKDF-SHA256, salted with the ride ID, with the ride code mixed into `info` as context binding |
| Packet confidentiality + integrity + authenticity | AES-256-GCM (AEAD) |
| Ride code generation | OS CSPRNG (`secrets`/`SecureRandom`), never `random()`/`Math.random()`, never a timestamp |

The **ride code is not the encryption key.** It's a short, human-typeable
lookup/entry value. The actual session key comes from an X25519 exchange
between the creator's and joiner's ephemeral keypairs, with the ride code
and ride ID folded into HKDF as context — so possessing the 6-digit code
alone does not let an eavesdropper decrypt previously captured traffic;
they would also need to have observed/completed that specific X25519
exchange. See `python/security.py::derive_session_key` for the exact
construction and `python/tests/test_security.py` for the tests that pin
this behavior down.

Every location packet is authenticated (AES-GCM tag over lat/lon/rider
ID/timestamp/sequence number/destination). Any single-bit modification
in transit is rejected at decrypt time — see
`test_tampered_packet_bytes_rejected` and `test_tampered_ciphertext_is_rejected`.

## Replay protection

Two independent checks must both pass (`protocol.ReplayGuard`):
1. The packet's random 128-bit `packet_id` has not been seen in the last
   60 seconds (stops naive replay/duplicate relay delivery).
2. The `sequence_number` for that `rider_id` strictly increases (stops
   replaying an old-but-not-yet-expired packet_id/timestamp pair).

Packets outside a 30-second freshness window, or timestamped more than 5
seconds in the future, are also rejected.

## Key rotation and revocation

When the primary rider removes a participant (`Ride.remove_participant` /
`RideManager.kt`'s equivalent), every remaining participant's session key
is rotated using a **fresh ephemeral X25519 keypair** — not just a new
symmetric key derived from the same DH secret. This gives forward secrecy
across the rotation: even if a future compromise exposed key material, it
would not retroactively decrypt traffic protected under the pre-rotation
key.

## Threat model

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| 1 | Someone guesses/brute-forces the ride code | Code entry does not by itself grant decryption — see above | A guessed code plus a completed handshake with a malicious creator could still occur; `requireJoinConfirmation` and short ride lifetimes reduce the window |
| 2 | Bluetooth traffic is captured | AES-256-GCM end-to-end | Traffic analysis (who is near whom, roughly when) is not hidden — BLE advertisement/connection metadata is inherently observable over the air |
| 3 | A packet is modified in transit | AEAD authentication tag | None known, given correct key handling |
| 4 | An old packet is replayed | Sequence numbers + packet IDs + timestamps | A very short replay window around the freshness boundary exists by design (any system with clock skew tolerance has one) |
| 5 | An unauthorized device tries to join | Ride-code + X25519 handshake + primary confirmation | Social engineering (tricking a real participant into sharing the code) is out of scope for a cryptographic system |
| 6 | A removed rider keeps receiving updates | Key rotation on removal | The removed device retains whatever it captured *before* removal — this is inherent to any group-messaging revocation and is disclosed, not hidden |
| 7 | Someone obtains the physical phone | Local storage encrypted at rest (Android Keystore-backed); session keys held only in memory, wiped on ride end | An unlocked/compromised device defeats most app-level protections — this is a device-security problem, not one this app can solve |
| 8 | Someone tries to identify riders from traffic | Random rider IDs, user-chosen aliases, no PII collected | Physical/visual identification of a rider by someone standing next to them is obviously out of scope |

## What this app deliberately does NOT do

- It does not use nearby strangers' phones as hidden location sources.
  Only devices that complete the application-level ride handshake are
  ever treated as a source of location data; uninvolved nearby BLE
  devices are invisible to it (see `docs/privacy.md`).
- It does not depend on OS-level Bluetooth pairing/bonding for security.
  Application-layer authentication (the X25519 handshake) is the actual
  security boundary, independent of whatever pairing state the OS shows.
- It does not claim a real Bluetooth mesh radio mode — see
  `docs/architecture.md` for what "relay" actually means here and its
  real, hardware-imposed connection-count limits.
- It does not silently drop suspicious location jumps and pretend
  everything is fine; `security.is_plausible_movement` flags them for
  the UI to show "Location data may be inaccurate" without accusing
  anyone of cheating.

## Known limitations

- This code has not undergone a third-party security audit.
- The X25519 handshake as scaffolded in `BleMeshManager.kt` is described
  but not fully wired end-to-end in this delivery (see README's phased
  status) — treat the Python reference implementation as the correct
  specification to finish that wiring against.
- Traffic-analysis resistance (hiding *that* a BLE exchange is happening,
  as opposed to its content) is not a goal of this design.
- Device compromise (malware, physical access to an unlocked phone,
  Android Keystore extraction on a rooted device) is outside this app's
  threat model, as it is for essentially all mobile apps.
