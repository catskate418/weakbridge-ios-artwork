# Changelog

## 0.2.0 — 2026-11-02

- Reorganize the watch interface into Connection, Settings, Notifications, Diagnostics and Help, with Russian/English resources and rotary navigation.
- Add per-app filters, category channels, grouping, hidden previews, quiet hours and per-app alert cooldown. Keep content in memory and cap mirrors/groups below the notification budget.
- Coalesce truncated AMS reads by attribute and recheck command validity after waiting for the GATT queue. Skip obsolete ANCS app-name reads and known blocked updates.
- Apply ordinary settings without reconnecting; preserve explicit manual reconnect. Avoid repeating the foreground and unchanged notification publications.
- Add GATT payload/operation counters, duration accounting and a bounded private-content-free diagnostic journal with explicit copy/clear.


## 0.1.4 — 2026-11-02

First public beta. Dedicated release signing, optimized release build, English and Russian setup guides, and MIT license. Includes the pairing and media fixes from private testing.

## 0.1.3 — 2026-10-31

- Keep the media session active when the queue index changes.
- Preserve metadata that arrives before the queue update; mark position unknown until fresh playback information arrives.
- Avoid republishing unchanged media notifications on position and volume updates.

## 0.1.2 — 2026-10-31

- Request system Bluetooth discoverability before starting pairing advertisements.

## 0.1.1 — 2026-10-31

- Optimize the watch build and simplify scrolling in the main menu.
- Separate saved devices from ANCS scan results.
- Show permission and service availability separately.
