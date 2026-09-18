-- Rider's Live Location — local SQLite schema
--
-- Design notes:
--   * This database lives ONLY on the device (Android: app-private storage,
--     opened via SQLCipher or Room+SQLCipher so the file is encrypted at
--     rest — see docs/security.md §"Local data at rest").
--   * No table here is ever synced to a server; there is no server.
--   * `locations` is a rolling buffer for the ACTIVE ride only, not a
--     permanent history — see the cleanup triggers/queries at the bottom
--     and docs/privacy.md.
--   * Foreign keys use ON DELETE CASCADE so ending/deleting a ride cleans
--     up everything that hung off it in one statement.

PRAGMA foreign_keys = ON;

-- One row per ride this device has created or joined, past or present.
CREATE TABLE IF NOT EXISTS rides (
    ride_id             TEXT PRIMARY KEY,          -- random UUID, not sequential
    state               TEXT NOT NULL CHECK (state IN ('CREATED','WAITING','ACTIVE','PAUSED','ENDED')),
    created_at          INTEGER NOT NULL,           -- unix epoch seconds
    ended_at            INTEGER,
    local_role          TEXT NOT NULL CHECK (local_role IN ('PRIMARY','RIDER')),
    -- The plaintext ride code is intentionally NOT stored once the ride
    -- becomes ACTIVE; only a salted hash is kept, for optional "did I
    -- join the right ride" confirmation. See security.py.
    code_hash           TEXT,
    key_epoch           INTEGER NOT NULL DEFAULT 0,
    max_participants    INTEGER NOT NULL DEFAULT 8,
    retain_history_opt_in INTEGER NOT NULL DEFAULT 0  -- 0 = delete on end (default), 1 = user opted to keep
);

-- Participants of a given ride, as known to this device. Session keys are
-- NEVER stored in this table — they live only in the Android Keystore
-- (or in-memory for the Python reference implementation) and are wiped
-- when the ride ends or a participant is revoked.
CREATE TABLE IF NOT EXISTS participants (
    rider_id            TEXT NOT NULL,              -- e.g. RIDER-7F31, never a real name
    ride_id             TEXT NOT NULL REFERENCES rides(ride_id) ON DELETE CASCADE,
    display_name        TEXT NOT NULL,              -- user-chosen alias only
    role                TEXT NOT NULL CHECK (role IN ('PRIMARY','RIDER')),
    joined_at           INTEGER NOT NULL,
    revoked             INTEGER NOT NULL DEFAULT 0,
    share_speed         INTEGER NOT NULL DEFAULT 1,
    share_heading       INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (rider_id, ride_id)
);

-- Rolling buffer of the most recent known fix per rider, for the
-- currently active ride. This table is NOT a movement history —
-- it is overwritten in place (see the upsert pattern in ride_manager
-- integration code), and rows are deleted when the ride ends unless
-- the user opted in to keep history for that ride.
CREATE TABLE IF NOT EXISTS locations (
    rider_id            TEXT NOT NULL,
    ride_id             TEXT NOT NULL REFERENCES rides(ride_id) ON DELETE CASCADE,
    latitude            REAL NOT NULL,
    longitude           REAL NOT NULL,
    speed_mps           REAL,
    heading_deg         REAL,
    accuracy_m          REAL,
    recorded_at         INTEGER NOT NULL,           -- device-local timestamp of the fix
    received_via        TEXT NOT NULL CHECK (received_via IN ('DIRECT','RELAY')),
    hop_count           INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (rider_id, ride_id)
);

-- Optional, strictly opt-in movement history. Only written to if the
-- user has explicitly enabled "Save Location History" in Privacy
-- Settings for this specific ride. Never uploaded automatically.
CREATE TABLE IF NOT EXISTS location_history (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    rider_id            TEXT NOT NULL,
    ride_id             TEXT NOT NULL REFERENCES rides(ride_id) ON DELETE CASCADE,
    latitude            REAL NOT NULL,
    longitude           REAL NOT NULL,
    recorded_at         INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS destinations (
    ride_id             TEXT PRIMARY KEY REFERENCES rides(ride_id) ON DELETE CASCADE,
    latitude            REAL NOT NULL,
    longitude           REAL NOT NULL,
    name                TEXT,
    set_by_rider_id     TEXT NOT NULL,
    updated_at          INTEGER NOT NULL
);

-- Dedup/replay-protection bookkeeping, mirrors protocol.ReplayGuard.
-- Rows older than DEDUP_CACHE_TTL_S are purged periodically; this
-- table is never allowed to grow unbounded.
CREATE TABLE IF NOT EXISTS packets (
    packet_id           TEXT NOT NULL,
    ride_id             TEXT NOT NULL REFERENCES rides(ride_id) ON DELETE CASCADE,
    rider_id            TEXT NOT NULL,
    sequence_number     INTEGER NOT NULL,
    seen_at             INTEGER NOT NULL,
    PRIMARY KEY (packet_id, ride_id)
);

-- Known nearby peers usable for relay, and the last time we successfully
-- exchanged data with them. Used to pick relay paths and detect stale
-- topology; never stores a peer's location, only connectivity metadata.
CREATE TABLE IF NOT EXISTS network_peers (
    peer_device_id      TEXT NOT NULL,              -- app-level session identity, not a raw MAC address
    ride_id             TEXT NOT NULL REFERENCES rides(ride_id) ON DELETE CASCADE,
    last_seen_at        INTEGER NOT NULL,
    signal_strength_dbm INTEGER,
    is_direct           INTEGER NOT NULL DEFAULT 1,  -- 1 = directly connected, 0 = known-of via relay only
    PRIMARY KEY (peer_device_id, ride_id)
);

-- App-wide user settings (privacy + security toggles from master
-- prompt §50-51). Single-row table.
CREATE TABLE IF NOT EXISTS settings (
    id                          INTEGER PRIMARY KEY CHECK (id = 1),
    location_sharing_enabled    INTEGER NOT NULL DEFAULT 1,
    save_location_history       INTEGER NOT NULL DEFAULT 0,
    display_alias               TEXT NOT NULL DEFAULT 'Rider',
    share_speed_default         INTEGER NOT NULL DEFAULT 1,
    share_heading_default       INTEGER NOT NULL DEFAULT 1,
    auto_delete_ride_data       INTEGER NOT NULL DEFAULT 1,
    require_join_confirmation   INTEGER NOT NULL DEFAULT 1,
    auto_expire_ride_hours      INTEGER NOT NULL DEFAULT 12,
    rotate_session_keys         INTEGER NOT NULL DEFAULT 1,
    hide_rider_identity         INTEGER NOT NULL DEFAULT 1,
    battery_saver_interval_s    INTEGER NOT NULL DEFAULT 20
);
INSERT OR IGNORE INTO settings (id) VALUES (1);

-- -----------------------------------------------------------------------
-- Indexes for the read patterns the live map/dashboard actually uses
-- -----------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_participants_ride ON participants(ride_id) WHERE revoked = 0;
CREATE INDEX IF NOT EXISTS idx_locations_ride ON locations(ride_id);
CREATE INDEX IF NOT EXISTS idx_packets_seen_at ON packets(seen_at);
CREATE INDEX IF NOT EXISTS idx_peers_ride_last_seen ON network_peers(ride_id, last_seen_at);

-- -----------------------------------------------------------------------
-- Maintenance queries (run by a periodic background job, not ad hoc):
--
--   Purge expired replay-protection entries:
--     DELETE FROM packets WHERE seen_at < strftime('%s','now') - 60;
--
--   Purge stale peer records:
--     DELETE FROM network_peers WHERE last_seen_at < strftime('%s','now') - 300;
--
--   On ride end, for rides that did NOT opt into history retention:
--     DELETE FROM locations WHERE ride_id = ?;
--     DELETE FROM location_history WHERE ride_id = ? AND
--       (SELECT retain_history_opt_in FROM rides WHERE ride_id = ?) = 0;
-- -----------------------------------------------------------------------
