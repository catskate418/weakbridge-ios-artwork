# Testing

## Automated checks

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

The suite currently contains 54 JVM tests. It covers ANCS fragmentation and notification lifecycle, GATT serialization and cancellation, reconnect policy, pairing advertising layout, service status text, AMS metadata recovery and track transitions. Additional regressions cover a burst of truncated AMS updates, expiry/support changes while waiting for the GATT queue, removed/filtered ANCS responses, mirror eviction, grouping, quiet-hour boundaries and cooldown, plus diagnostics reset/toggle timing boundaries. Fake transports and coroutine virtual time make failures reproducible.

Tests do not emulate Samsung's Bluetooth stack, notification rendering or gesture recognition. Reports are written to `app/build/reports/`.

## Device coverage

Galaxy Watch6 (SM-R930) with iPhone 13 / iOS 26.0:

- First discovery and pairing confirmed after adding Android's discoverability prompt.
- Music controls confirmed by the user; an active remote media session was also observed through ADB.
- Track-change session reset fixed in 0.1.3 and covered by regression tests. Longer playback tests are still needed.
- Notification actions, all media apps, reboot recovery and long-term battery consumption are not comprehensively validated.

## Before a release

- Run the automated checks and verify the APK signature, package ID and version.
- Install on a configured watch, pair an iPhone and check a fresh notification.
- Start music, change tracks repeatedly, pause/resume, return to the watch face.
- Test disconnect/reconnect and confirm stale actions cannot control the new session.
- Check screen-off behavior separately from foreground operation.
- Compare battery consumption using [BATTERY_OPTIMIZATION.md](BATTERY_OPTIMIZATION.md).

When reporting a result, include model, firmware, iOS version and the music app used. Never attach unredacted message content, Bluetooth addresses or full bugreports to a public issue.

## 0.2.0 — 2026-11-02

- Clean optimized wear build passed; **54 tests, 0 failures/errors**. Wear lint: **0 errors, 25 warnings** (dependency updates, UseKtx suggestions and launcher-shape heuristic).
- Optimized 0.2.0 installed in place on SM-R930 using the existing private-build certificate. Bond/settings were retained; connection to the saved iPhone restored.
- Connection, Home, Settings and Notifications exercised on the 432×432 round watch. Switching auto-reconnect and low-power settings updated their controls immediately and retained the same media session and playback timestamp. Quiet-hour toggle/hour selection, Help, and diagnostic clear/copy were also exercised; original settings restored.
- Real incoming notification bursts, all action types, extended screen-off playback, reboot/out-of-range recovery and comparative battery drain remain acceptance work. JVM coverage is not a replacement for these checks.

The `wear` build uses a development certificate; `release` uses the public distribution key. Device UI checks were performed on the earlier 0.2.0 wear build. Final manual-reconnect and diagnostic-boundary corrections received code review and regression checks; the final release APK was not separately installed during this run. Normal operation on the reference device was also confirmed in use.

## Public 0.2.0 artifact

[WeakBridge-0.2.0.apk](https://github.com/Weakmafaka/WeakBridge/releases/download/v0.2.0/WeakBridge-0.2.0.apk) uses the same public release certificate as 0.1.4. Both optimized variants built with debuggable disabled. APK contents retain dependency licenses in `assets/licenses/`; local tools, signing keys and private logs are not packaged.

SHA-256:

```text
9c45e912aa5598e7b0c29bc47028af8ae16ea57536e48ea778527ae6f634ee93  WeakBridge-0.2.0.apk
```

Release certificate SHA-256: `26381dcfe860b5592a507aec4dd24d791d38b8fcd1e80b32a6e0b3e94a470839`.

Remaining device checks include high notification volume, app filters/groups and action types, quiet-hour/cooldown signals, permission restoration, prolonged screen-off playback, player changes, out-of-range recovery, Bluetooth off/on and reboot. Battery trials follow [BATTERY_OPTIMIZATION.md](BATTERY_OPTIMIZATION.md).
