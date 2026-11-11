# Changelog

All notable changes to this fork. Every entry is a delta against
**Weakmafaka/WeakBridge 0.2.0**; the upstream file is kept at
[`docs/upstream/CHANGELOG.md`](docs/upstream/CHANGELOG.md).

> All changes below were authored by an AI agent (ZCode) in a single session and have **not** been
> reviewed by a human.

## Unreleased — fork

### Added

- **Album artwork.** New package `dev.weakbridge.artwork` (5 files, ~800 lines) resolves the cover
  the watch needs and AMS cannot carry:
  - `ArtworkService` — pipeline, memory + disk cache, per-track and per-album keys, in-flight
    deduplication, connectivity check.
  - `RemoteCatalog` — Deezer first (better Russian coverage, direct CDN image), Apple iTunes second
    (storefronts `ru` and `us`).
  - `CoverSources` — local audio files and the watch's own media library, so some tracks need no
    network at all.
  - `CoverQueue` — tracks heard while offline, stored in `files/cover_queue.tsv`.
  - `JsonCursor` — small JSON reader, so no dependency had to be added.
- **Real app icons in notifications.** `NotificationGlyph` (new, 200 lines) builds an in-memory
  small icon from either initials or a silhouetted App Store icon, and renders a 320 px large icon
  for the card. Icons are fetched by bundle id with `country=ru`, falling back to an exact-name
  search, and cached in `files/icons/`.
- **Explicit-content badge.** iOS appends U+20DD to explicit titles; the character is stripped, kept
  as a flag, and drawn as an "E" badge on the artwork and on the media icon.
- **Offline replay.** `drainQueue()` runs on connect and after each successful lookup, filling
  covers for tracks that played without a network.
- **Tests** (71 total, all passing):
  - `JsonCursorTest` — nested objects, escaped quotes, key/value disambiguation.
  - `DisplayableTest` — explicit mark handling, variation selectors, Cyrillic.
  - `CatalogParseTest` — title normalisation and field extraction.
  - `AppGlyphLabelTest` — initials for known and unknown bundle ids.

### Changed

- `integration/NotificationBridge.kt` (+93 lines) — one `IMPORTANCE_HIGH` channel per iPhone
  bundle id instead of five shared category channels; real app icon as the card's large icon;
  silhouetted app icon in the status bar; silent repaint after a late icon download.
- `integration/MediaSessionBridge.kt` (+120 lines) — real playback position and rate instead of
  `PLAYBACK_POSITION_UNKNOWN`; artwork attached to `MediaMetadata`; three compact actions; 320 px
  large icon; badge applied to both card and session metadata; `applyCover` repaint path.
- `service/BridgeForegroundService.kt` (+26 lines) — wires `ArtworkService` in, warms icons and
  channels for known apps on connect, drains the cover queue, connects to a bonded phone from a
  single command.
- `ams/AmsParser.kt` (+42 lines) — `explicit` flag on `MediaState`; undrawable characters removed
  from title, artist, album and player names.
- `BridgeApplication.kt` (+21 lines) — observed app list persisted, so icon warm-up survives a
  restart.
- `integration/NotificationAppCatalog.kt` (+34 lines) — short initials per bundle for the fallback
  icon.
- `ui/MainActivity.kt` (+23 lines), `ui/BridgeWatchApp.kt` (+9 lines) — "connect to a paired iPhone"
  action, useful when the pairing window times out.
- `AndroidManifest.xml` (+2 lines) — `INTERNET` and `ACCESS_NETWORK_STATE` for artwork lookups.
- `values*/notification_strings.xml`, `values*/ui_strings.xml` — strings for the new channel
  description and for the connect action.

### Fixed

- **Notification icons were always the launcher arrow.** Slots were pre-registered XML drawables
  resolved by name; a `<bitmap>` pointing at a vector decodes to `null`, so every card fell back to
  the app mark. Replaced with `IconCompat.createWithBitmap`.
- **Channel ids could double their prefix** (`app.app_app_swiftgram_ios`), leaving two channels per
  app. The id is now built from a de-duplicated slug, and stale `iphone.*` / `app.app_*` channels
  are deleted on start.
- **Cover lookups failed for multi-artist tracks.** AMS sends `"ICEGERGERT, OBLADAET"` as one
  string while catalogs list artists separately; a whole-string containment test rejected the
  correct match.
- **Partial AMS metadata poisoned the cover cache.** Artist, album and title arrive as separate
  events, so a lookup fired with an empty title and its failure was remembered for the session.

### Not working / not attempted

- **Volume control.** An absolute provider over a 16-step scale is registered and translates slider
  moves into relative AMS commands, but it could not be exercised without touch input, and the user
  reports it still does not work.
- **Samsung media widget cover.** Its controls work; the image stays blank because the system
  controller expects a media3 session while this app exposes a compat wrapper. A media3 port was not
  attempted.
- **Apple Health export.** Requires a separate iOS companion; HealthKit does not accept data from
  Wear OS.
- **Artwork for never-played tracks while offline.** No source exists: Apple withholds artwork from
  Bluetooth accessories, and the Yandex Music offline cache is encrypted.

---

## 0.2.0 — 2026-11-02 (upstream)

See [`docs/upstream/CHANGELOG.md`](docs/upstream/CHANGELOG.md).
