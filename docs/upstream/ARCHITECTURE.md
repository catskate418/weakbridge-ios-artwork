# Architecture

`BridgeForegroundService` owns Main-dispatcher structured concurrency, Bluetooth broadcasts, one `BleConnectionManager`, protocol clients and Android integration. Activity uses a plain Compose lazy list without row scaling and renders process StateFlows and dispatches user intents. No Activity reference enters the transport.

```text
Wear Compose UI → foreground service → connection manager → one BluetoothGatt
                                        ↓ serialized queue
                                  ANCS client / AMS client
                                        ↓
                          NotificationManager / MediaSessionCompat
```

## State and ownership

Disconnected → scanning/pairing or connecting → discovering → authorizing → subscribing → connected. Failures close the GATT and cancel all connection work before reconnecting. Explicit disconnect also cancels selection, bond wait, scan, advertising and retry jobs, clears private mirrors and deactivates the media session. Bluetooth OFF cancels active work; ON can restore the selected bond if enabled. Bond loss requires reselection.

Each GATT has a monotonic generation and callback identity. Closing invalidates the generation before cancelling its queue; stale callbacks cannot operate on the replacement connection. Android API 33 value-carrying callbacks avoid mutable characteristic-value races; the minimum SDK means deprecated duplicate callback overrides are unnecessary. Bluetooth callbacks are delivered on the main Handler. Other callbacks marshal into the service scope. Runtime permission checks guard entry; security failures are handled without retry storms.

`BleGattOperationQueue` serializes discovery, reads, writes, CCCD changes and optional MTU negotiation. It correlates callback operation type and characteristic UUID and gives operations 12 seconds. Cancellation/timeout poisons that queue: a late callback can never satisfy the next operation on that link. Immediate submission rejection and explicit status errors propagate. GATT protocol status failures and link disconnect statuses have separate error namespaces, so link status 8 (timeout) is not mistaken for ATT authorization status 8.

No large MTU negotiation by default: standard commands fit a 20-byte payload. ANCS fragmented notifications are assembled independently of MTU. Android's characteristic read procedure supplies long AMS values as supported by the stack. Optional app-name commands that exceed the default payload are skipped rather than sent as invalid chunks. A serialized MTU operation is available for measured future changes; Android may request a different actual MTU than the hint.

## Discovery and identity

Bond list first. A user-selected bond address is a reference to Android's bonded device record, not a claim that a raw observed BLE address is a permanent identity. OS identity resolution handles bonded privacy addresses. If that bond reference disappears, require explicit reselection; never guess by name or automatically use the first LE device.

Initial pairing advertises ANCS solicitation with an empty GATT server for the incoming iPhone link. Bluetooth link role and GATT role are separate: after bonding, the watch is the ANCS/AMS GATT client even if the iPhone initiated the physical link. Advertising is user initiated and expires in two minutes. Version 0.1.1 places the unchanged watch name in the primary packet; solicitation shares it if the UTF-8 name is at most eight bytes, otherwise solicitation is in the scan response. It does not continue to keep the bridge alive. The discoverability prompt and pairing flow have been exercised on SM-R930 with iPhone 13 / iOS 26.0. Other firmware combinations remain unverified.

## ANCS

Notification Source events are eight-byte little-endian records. A bounded repository deduplicates additions, revisions updates and invalidates removals. At most 64 pending UID requests are coalesced; at most 100 protocol identities are retained. Android presentation is separately capped at 40 content cards and 6 app summaries, leaving slots for connection and media. Pre-existing events are skipped unless already tracked. Removal is processed synchronously, so a response completing after removal cannot resurrect its mirror.

The worker owns one request/response assembler at a time across both notification and optional app-name attributes. It validates exact response prefix, attribute order, lengths and total memory. Malformed/timeout responses invalidate the link because continuing an unframed stream would be unsafe. No content is logged. Supported actions use UID, command 2 and action 0/1; immutable internal PendingIntents carry a per-connection token, and a stopped bridge never restarts to replay an action.

## AMS and remote media

Parser constants follow Apple's appendix (player 0, queue 1, track 2; artist/album/title/duration 0/1/2/3). Player playback is state/rate/elapsed seconds. Playback timestamps use elapsedRealtime. Android controllers can extrapolate from the last genuine position/rate; no ticking coroutine fabricates positions. Empty strings overwrite stale metadata. Player changes clear stale track state. Queue index changes preserve playback status and metadata, but invalidate position until fresh PlaybackInfo arrives. Metadata may arrive before the queue update; clearing it at that point would erase the new track. Truncated updates coalesce to the newest revision per entity/attribute. A conflated worker signal drains that bounded map. Revision predicates run after acquiring the GATT mutex, so obsolete selectors and expired/unsupported commands never reach the radio. Late reads cannot overwrite newer values.

Remote command notifications replace, rather than accumulate, the advertised support set. Commands have a bounded buffer, expiry and support checks; disconnect discards all queued commands. GATT acknowledgment is not proof of playback execution. MediaSessionCompat uses a relative VolumeProviderCompat and genuine AMS metadata. Media3 would require an appropriately implemented remote Player; the compat session is a smaller direct remote-state surface here and remains available on SDK 33+. No mediaPlayback foreground-service claim is made: the service communicates with a connected device, not a local audio player.

## Privacy and lifecycle

No INTERNET, location, exact-alarm, battery-exemption or wake-lock permission. Only selected bond and settings persist. Backup is disabled. Notifications use private lock-screen visibility. Data visible to system notification/media consumers is necessary for native integration. The app does not export a data provider or content service. Diagnostics store counters only in memory. External app names/messages are text, never commands.

## Configuration and notification presentation

Selecting the same address during a live connection is idempotent; explicit Reconnect bypasses this guard. Low-power, auto-reconnect, notification and diagnostic settings apply to the active service without replacing the link. Disabling auto-reconnect cancels pending recovery, not an established connection. Changing the media module deliberately reconnects to obtain a clean subscription lifecycle.

Notification settings filter by category and observed app identifier. Names use ANCS and a local name catalog; bundled glyphs describe categories, not vendor logos. Quiet hours and per-app cooldown suppress signals while preserving new content. Updates and preference refreshes are silent. Group summaries are limited to six apps with multiple records. A dismissed child/group is removed from the RAM store; settings refresh does not resurrect it. Notification permission restoration reconciles actual active cards before silent republishing.

Observed app names and message contents are RAM-only; selected bond, preferences and blocked identifiers persist. Unblocking can restore a retained card or allow the next event, but does not fetch an iPhone notification archive. Diagnostics are opt-in, event-driven, bounded, and copied only by an explicit user action.

## Protocol and platform references

- [Apple Notification Center Service](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html): notification events, attributes and supported actions.
- [Apple Media Service](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/Specification/Specification.html): metadata and remote playback commands.
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): connected-device communication and permission requirements.
