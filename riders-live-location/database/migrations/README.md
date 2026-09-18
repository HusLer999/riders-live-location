# Migrations

`schema.sql` in the parent folder is the current schema. As the app
evolves, put numbered migration scripts here (e.g. `0001_add_column.sql`)
rather than editing already-released devices' databases in place, and
apply them via Room's Migration API (or an equivalent SQLite migration
runner) so upgrading the app never silently drops a user's local ride
data outside the deletion rules described in PRIVACY.md.
