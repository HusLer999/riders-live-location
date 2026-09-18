# Offline map data

This folder is where downloaded offline map regions (e.g. `.mbtiles`
files for MapLibre GL, per `mobile/android/app/build.gradle.kts`'s
`org.maplibre.gl:android-sdk` dependency) are stored on-device once a
user downloads a region before a ride.

No map tiles are bundled in this repository — they're large binary
files that should be fetched by the app itself (see the "Offline map"
requirements in the project brief: users download/store map data
*before* going offline, and the app must clearly distinguish "Offline
map available" from "Map data unavailable" rather than silently failing
or falling back to an online tile fetch). A reasonable source for
regional `.mbtiles` extracts is https://download.geofabrik.de/ combined
with an offline tile-generation tool (e.g. `tilemaker` or `planetiler`)
run as part of a build/release pipeline, not at app runtime.
