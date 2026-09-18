# Privacy model

## What this app never asks for

Real name, phone number, email address, home address, contact list, or
any social-media account. None of these are required by any feature in
this app. There is no account system and no server to register with.

## What identifies you inside a ride

A randomly generated identifier, e.g. `RIDER-7F31`, plus a display
alias you choose yourself (e.g. "Rider B", "Sam"). Other participants
see only the alias. The random ID is scoped to that one ride and is not
a persistent cross-ride identity.

## What is collected, and why

| Data | Why | Where it goes |
|---|---|---|
| GPS coordinates, speed, heading | To show your position to the group you're actively riding with | Encrypted, sent only to authenticated participants of the ride you joined, over Bluetooth. Never to a server — there isn't one. |
| Ride code | To let others join the same ride | Shared by you, out of band (spoken, typed, or QR) |
| Display alias | Shown to other riders in your group | Local to the ride; not persisted beyond it unless you choose to reuse it |

Speed and heading sharing can each be turned off independently in
Settings without leaving the ride.

## What is never collected

- Your real identity, unless you choose to type it as your alias (not
  recommended, and not required).
- The location of anyone who has not explicitly joined your ride. A
  nearby stranger's phone is never treated as a location source, even
  if it happens to relay encrypted traffic for others — see
  `docs/bluetooth-protocol.md`.
- Any data from your contacts, camera, microphone, or files — the app
  never requests those permissions because no feature uses them.

## Local storage and retention

Everything lives in a local, encrypted-at-rest SQLite database on your
device (`database/schema.sql`). There is no cloud sync and no automatic
backup of ride data.

- **Live location** (the `locations` table) is a rolling buffer for the
  *current* ride only — it is overwritten in place, not appended to.
- **Movement history** (`location_history`) is written only if you
  explicitly turn on "Save Location History" in Settings for that ride.
  It is never uploaded automatically, and can be deleted at any time.
- **When a ride ends**, all ride-scoped data (participants, locations,
  destination, session keys) is deleted by default. You can opt out of
  this per-ride if you want to keep a record, but the default is
  deletion.
- **"Delete All Local Ride Data"** in Settings wipes everything
  immediately, regardless of ride state.

## Your controls (Settings → Privacy)

- Location Sharing: on/off
- Save Location History: off by default
- Show My Name: choose your alias
- Share Speed: on/off
- Share Heading: on/off
- Automatically Delete Ride Data: on by default
- Clear All Local Ride Data: immediate, irreversible local wipe

## Relay privacy

When your phone relays another rider's encrypted packet toward a third
rider, it forwards opaque ciphertext it cannot decrypt — only the
sender's and the intended recipients' session keys can open it. Acting
as a relay does not give your phone (or its owner) visibility into
location data that isn't already yours to see as a ride participant.

## Data minimization in practice

Every location packet on the wire carries only what's needed to display
a rider on the map and compute distance/ETA: ride ID, rider ID, packet
ID (random, not identifying), coordinates, optional speed/heading,
timestamp, and a sequence number. Nothing else rides along.
