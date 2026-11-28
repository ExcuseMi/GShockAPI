# Watch drivers: a worked example

An example to discuss, not a migration. It answers the question in discussion #177 ("adding individual watches could
be implemented as a plugin") with the H5600 family (GBX-H5600) as the worked case. Requests it should make easy:
B2100 (#36), GST-B100 (#128). Steps 1 to 3 below are done for the H5600 settings: `WatchApi.get` and `set` work on a
live link, the old calls stay as they are.

Files: `api/src/main/resources/org/avmedia/gshockapi/driver/h5600.json` (the family data file, shipped in the library),
`api/src/main/java/org/avmedia/gshockapi/driver/` (the engine and the drivers),
`api/src/test/java/org/avmedia/gshockapi/driver/` (`H5600DataFileTest`: the file against today's codecs;
`H5600SettingsTest`: every setting against its old codec, the checked write on a fake watch; `ModelFilesTest`: merging
and refusals).

## Parts

```kotlin
interface WatchConnection {                 // engine side, one connected watch
    val name: String; val state: StateFlow<LinkState>
    suspend fun readFrame(code: Int): ByteArray?
    suspend fun modify(code: Int, touched: Set<Int>, expectedBack, matches, change: (ByteArray) -> ByteArray?): SettingWriteResult
}
interface WatchDriver {                     // one family, stateless
    fun matches(advertisedName: String): Boolean
    fun capabilities(conn: WatchConnection): Set<String>
    suspend fun <T : Any> get(conn: WatchConnection, key: Key<T>): DriverResult<T>
    suspend fun <T : Any> set(conn: WatchConnection, key: Key<T>, value: T): DriverResult<Unit>
    fun events(conn: WatchConnection): Flow<WatchEvent>
    suspend fun onConnected(conn: WatchConnection)
}
class Key<T : Any>(val id: String, val type: Class<T>, val writable: Boolean)   // Keys.AUTO_LIGHT, Keys.ALARMS, ...
sealed interface DriverResult<out T> { Ok, Unsupported, Failed, Unconfirmed }
sealed interface WatchEvent { Connected, Disconnected, ButtonPressed, Steps, Battery, Data }
```

- **Engine**: mostly there already. `WatchLinks` (one link per watch), `WatchJobQueue` (one job at a time, priorities),
  the link's setup, keep-alive and reconnect. `LinkConnection` is the adapter from a link to `WatchConnection`.
  `DriverEngine.bind(connection)` gives a `BoundWatch` with `get`, `set`, `events` and `capabilities`.
- **Driver**: `DataDriver` is a driver made from a data file alone. A code driver (`H5600Driver`) extends it.
- **Unsupported is a value**: a missing key, a model without it, a driver that needs the link engine, or no driver at
  all give `DriverResult.Unsupported` with a reason. Nothing throws, nothing is sent.

## Finding a driver

1. The advertised name minus `CASIO ` is the model name; only exact names match (as `WatchInfo` does today).
2. Built-in drivers are asked first, in order, then discovered ones. First match wins, so an outside driver cannot take
   a watch a built-in one knows.
3. A separately published library (for example on JitPack) ships a `WatchDriverProvider` and names it in
   `META-INF/services/org.avmedia.gshockapi.driver.WatchDriverProvider`; `java.util.ServiceLoader` finds it. An app
   can also hand drivers in by hand (`DriverRegistry(builtIn, discovered = { ... })`).
4. A family with only simple settings needs no code beyond `DataDriver`. A model of a known family needs only a data
   file (next section).

## App files: a model the library does not know

An app can add a model, or change one, with its own data file. It works only for a family whose code driver exists:
the file names it (`family` in a family file, `extends` in a model file). Code drivers for new families are found the
same way as drivers, through `ServiceLoader` (`DriverFamily`).

- **Found** on the classpath: every `META-INF/gshockapi/watches.index` lists its files (a class loader cannot list a
  folder), for example `META-INF/gshockapi/watches/b2100.json`. Or **registered at run time** (`ModelFiles.register`),
  for a file the app downloaded. A downloaded file is the app's to verify first (squarewave signs its definitions).
- **Checked**: the schema version and the layout (types, positions, masks, references) are validated; a bad file is
  refused with a clear reason and a log line, never a crash, and the rest keeps working.
- **Merged, not replaced**: family settings and capabilities are inherited; a model file states only what differs.
  Order, later wins with a log line: library family, library model, app family additions, app model. Removing an
  inherited capability or setting is explicit (`"supported": false`).

```json
{ "schema": 1, "kind": "watch-model", "id": "gbx-h5600x", "extends": "h5600",
  "models": [ { "name": "GBX-H5600X", "faces": 4 } ],
  "capabilities": [ "face.tide", { "id": "hr.measurement", "supported": false } ],
  "settings": [ { "key": "light.duration", "byte": 3 } ] }
```

A made-up variant: every H5600 setting, without HR measurement, with the light duration one byte later (mask and
values inherited). `ModelFilesTest` checks this merge, the order, the refusals and the classpath index.

## Data or code

| In the data file | In code |
|---|---|
| model names, module, face count, per-model extras | the connection setup (0x39 read and written back, 0x49, 0x47) |
| the capability list | time sync (fixed write order, home location in the middle, stamped on its turn) |
| settings that are bits or bytes in one frame: position, mask, polarity, value names, ranges, allowed values | multi-packet reads: LifeLog, sleep (0x5A, 0x53), workouts, tide send |
| small rules: "off also clears bit 0x02", "only while X is on", "only while the rest of the frame is readable" (`valid_with`), "also sets its user flag", a command byte | anything with timing, retries, channels or state across frames |
| fixed-size records (alarm slots) | notification text (gate, MTU, chunks), battery scaling |

The rule: if a setting is "read frame, change my bits, write, read back", it is data. A data file is safe to accept
from outside: the codec never touches bytes outside the setting, and every write goes through the existing checked
read-modify-write. Code is needed when the watch's answer decides the next step.

Today's app already keeps texts, buttons, pairing steps and measurements as data (`watch-families.json`) and the
setting order and conditions in its catalog. Those stay in the app. Only the byte layouts move into the library file.

## Threading and the queue

- One `WatchJobQueue` per watch. Every `readFrame` and `modify` is a job on it, so a driver, a plugin and the app
  never write at the same time, and a transfer gives the link up only at its block boundaries.
- Drivers hold no state and no coroutine scope; they run on the caller's coroutine and suspend on the queue.
- The reconnect and keep-connected policy are the engine's (the link's), not the driver's. `onConnected` would run
  as part of the setup job, before any other job (not wired in the sketch).
- Events are a cold `Flow` per bound watch; the engine merges the link's state with the driver's own events.

## Moving today's code over, area by area

Each step builds and ships on its own; the app keeps working after each.

1. **Add the package** (this branch): interfaces, `DataDriver`, `H5600Driver` delegating to today's code, tests that
   the data file gives the same bytes as the codecs.
2. **Ship the H5600 file** as a library resource. `WatchApi` gets `get(key)` and `set(key, value)` that call the bound
   driver. The old calls stay.
3. **Settings, one at a time**: auto light, light duration, units, HR measurement, smart alarm, step goal, sleep times,
   face, sound mode. For each: the app reads it through `get` and `set`, the old codec stays as the test oracle, then
   the old call becomes a wrapper (see below).
4. **Capabilities**: `WatchInfo` model checks (about 140 references) are replaced by `has(key)`, one feature area at a
   time (light, alarms, reminders, world cities, battery, temperature...). `ModelInfo` flags are generated from the
   data files until the last reference is gone.
5. **Protocols to drivers**: `H5600Protocol`, `MipProtocol`, `AnalogueProtocol`, `OceanusProtocol` and
   `StandardProtocol` each become a driver; the frame routing (`linkKey`, `linkHandlers`) moves with them.
6. **Code parts last**: time sync, LifeLog, workouts and tide move into `H5600Driver` from the `io/` objects they use
   now. These have the most tests and the most timing; they move only after steps 2 to 5 are in a release.
7. **New watches**: B2100, GST-B100 as data files first, with a code driver only where the file is not enough.

## Deprecation path

An old call stays as a thin wrapper over the engine, with the same name and types, so callers change nothing until
they choose to. Example (only in `LegacyWrappers.kt`, the real calls are not annotated on this branch):

```kotlin
@Deprecated("Use the engine's get", ReplaceWith("watch.get(Keys.ALARMS)"), DeprecationLevel.WARNING)
suspend fun getAlarms(): ArrayList<Alarm> = ...
```

- First minor release with the engine: `WARNING`, `ReplaceWith` pointing at `get` or `set`.
- A later minor release: `ERROR` (still in the binary, so compiled callers keep working).
- Next major release: removed.

## Alarms and time (done)

- `Keys.ALARMS` is the data file's records: each frame (0x15, then 0x16) read fresh, only the slots' fields and fixed
  byte changed, written, read back. Unknown bits stay. As the old writer: "one time" only on an alarm that is on,
  never on the first. A checked write of 0x15 or 0x16 drops the old alarm read's cached answer.
- Code keys on the link, delegating to today's per-link code: `Keys.TIME` (`TimeSync` with the zone, the clock and the
  offset; DST, cities, home location and the time stamped ahead by the Bluetooth lead, as `WatchApi.setTime`),
  `Keys.HOME_TIME`, `Keys.worldCity(i)`, `Keys.dstWatchState(state)`. A key with an index needs its family's capability
  (`Keys.capabilityOf`). `Drivers.bindCurrent()` binds the single-watch link.

## Health data (done)

Keys on the link, delegating to today's transfers: `Keys.LIFELOG` and `Keys.LIFELOG_PEEK` (the LifeLog read, as
`getStepCount(peek)`), `Keys.SLEEP` (the nights of a LifeLog read, 0x5A and 0x53), `Keys.WORKOUTS` (a workout read
now), `Keys.BATTERY`; `Keys.CONDITION` is a plain 0x28 read on any connection. The copy of every watch read
(`WatchDataTap`) is also a flow (`WatchDataTap.reads`); a driver's `events` carry its watch's reads as
`WatchEvent.Data(Keys.WATCH_DATA, read)`. `Drivers.currentEvents()` follows the single-watch link, bound again when the
model changes. The workout sink stays: it says whether a workout was stored before the watch drops it.

## Notifications and the button (done)

`Keys.NOTIFICATION` sends a phone notification with today's sender on the link (gate, MTU, chunks): Ok when sent,
else Failed with the sender's outcome name. `Keys.BUTTON` is the button of this connection (its `IO.WatchButton`
name); the driver's events carry it as `WatchEvent.ButtonPressed` at each connection.

## Open questions

1. Alarms: the per-frame write that keeps unknown bits is to be checked on a watch (alarm 1 and the three in 0x16).
2. Auto light polarity (bit set means off) is not confirmed on this family.
3. Should a data file be allowed to add a capability the library has no key for (shown as raw)?

Settled: the H5600 family has 4 alarms (1 in 0x15, 3 in 0x16, so 3 records written is right);
data files are JSON; key ids are one shared list in the library (`Keys`), so apps and plugins use the same names.

Settled in step 3 (as safe as the old codecs, checked by `H5600SettingsTest`):
- `write_over_unknown`: only for a choice with a mask (light duration), as the light codec does; the file is refused
  for any other setting. Only the mask bits change and `valid_with` still holds.
- Cross-field checks: `valid_with` names the settings of the same frame that must be readable (when the frame holds
  their bytes) before a setting is read or written, as each old codec checks its whole layout (the night window, the
  sleep times, the smart alarm time, the notification modes and pattern). `requires` stays for "only while X is on".
- Profile (0x45) and the activity list (0x53) are code in `H5600Driver` (inverted BCD in cm or inches; a list toggled
  in the fresh frame); they are keys like the others.
- Not moved yet (own requests per activity or unit): the per-activity items (0x54), the tide settings (0x2E), the
  notification event masks.
