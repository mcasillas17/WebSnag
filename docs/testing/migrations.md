# Synthetic migration and persistence tests

MIG-001A exercises WebSnag's production identity conversion, Preferences DataStore transactions,
backup codec/repository, and NFC authorization with synthetic data. It does not prove a signed
Android package upgrade, physical NFC behavior, clone resistance, or cross-device credential
portability. Signed `adb install -r` testing belongs to MIG-001B.

## Unmet runtime acceptance criterion

**MIG-001A is incomplete and must not be merged as completion evidence.**
`MigrationEnforcementAcceptanceTest.failedMigrationMustNotSilentlyDisableRuntimeBlocking` remains
an enabled, failing Android acceptance test. Its bound-duration control starts blocking; the
`duration-unbound` input makes the native migration fail while retaining the original preferences.
The actual `EnforcementEngine` profile/recovery observers then terminate and the engine remains
inactive. Its `isPackageBlocked` decision, used by `WebSnagAccessibilityService`, allows a package
that the persisted active profile should block. Disk preservation and absence of an NFC unlock
result therefore do **not** establish runtime enforcement preservation.

Production always opens the migrating DataStore singleton. The test harness can bypass migration
and edit a fixture for a retry, but users have no equivalent production recovery path. A transient
key failure may resolve on a subsequent initialization; a permanently invalid/unbound input cannot
be repaired through the ordinary repository while initialization fails. Catching the error and
blocking every package would invent an unknown profile policy and friction, while still preventing
recovery writes. Removing the rejection would reintroduce permissive authorization or data loss.

Completion requires an agreed production migration-failure/recovery state that preserves safe
Accessibility decisions and emergency access, plus a real recovery/retry route. This crosses the
DATA-001 recovery and DEC-003 dormant-duration boundaries; it is not implemented here. The roadmap
must resolve that scope/sequencing decision before this task can complete; dependent tasks retain
their existing merge prerequisites. Do not skip, disable, or change the assertion to call this
fixture passed. Current evidence covers rollback and successful migrations, not this failure gate.

## Fixture format and provenance

Fixture suite **v1** is a test-input format. It is independent of the application version and of
`BackupCodec.VERSION` (the WSB1 envelope version). No DataStore schema-version key is added.
The canonical JVM inputs are under `app/src/test/resources/migrations/v1`; identical device
copies are under `app/src/androidTest/assets/migrations/v1`. `FixtureCatalogTest` checks parity.

Each JSON file declares `fixtureVersion`, `kind`, `source`, and `preferences`. The harness stores
collection/object values ending in `_json` as serialized JSON strings; other string values as
string preferences; `history_retention_days` as an integer. `rawOverrides` seeds exact malformed
strings without accidentally converting them into valid JSON strings.

All values are invented for testing. A historical-shape label means the declared fields and
serialization types were verified against source, not that an actual installation supplied data.

| Fixture | Provenance | Purpose and expected result |
| --- | --- | --- |
| `alpha1.json` | `v1.0.0-alpha.1`, `a4a05bb113ea4e2cf59dde273f96673698926785` | Raw tag records, specific profile links, active/inactive flags, activation time, escaped labels/descriptions, optional payload/timestamps, schedules, history, theme. Migrate identity fields and retain supported metadata/state. |
| `alpha2-current.json` | `v1.0.0-alpha.2`, `e18c614d2375bf452d48c1a11ea4af77393431e8`; relevant fields checked through alpha.4 | Fingerprint records, stable IDs, recovery, dismissed occurrence, retention. Migration is a byte-stable no-op and must not provision a key. |
| `mixed.json` | Explicit synthetic combination of alpha.1 and alpha.2 fields | Legacy and current tags/profiles plus separate recovery/state. Keep all valid records; convert only legacy identities. Not claimed as a historical writer's output. |
| `dormant.json` | Synthetic compatibility probes of types declared at alpha.1 | Separate probes for `Profile.triggers`, time/location/Wi-Fi triggers and duration expiry; also migrate legacy NFC trigger references. No feature activation or proof of historical editor reachability. |
| `duration-unbound.json` | Explicit synthetic probe of alpha.1 nullable duration binding | Null/omitted legacy binding must abort initialization with original bytes retained; explicit fixture repair can select a specific binding for retry. Does not establish historical UI reachability. |
| `malformed.json` | Explicit synthetic corruption | Characterize unchanged nonlegacy decode fallbacks separately from stored bytes; demonstrates DATA-001's remaining recovery problem. |

Historical source differences can be inspected without checking out an old version:

```bash
git show v1.0.0-alpha.1:app/src/main/java/websnag/elopenmike/com/core/model/NfcTagRecord.kt
git diff v1.0.0-alpha.1 v1.0.0-alpha.2 -- app/src/main/java/websnag/elopenmike/com/core/model
git show v1.0.0-alpha.2:app/src/main/java/websnag/elopenmike/com/core/data/LocalDataStore.kt
```

Alpha.1 declared `uidHex`, `linkedTagUid`, `requiredTagUid`, and NFC trigger `tagUid`. Alpha.2
replaced these with fingerprints and enrolled IDs, introduced explicit any-enrolled policy and
persisted recovery/occurrence/retention. Alpha.3 (`ddb4cb53aa3a4e9e779eadf9335ac28dd33d25da`) and
alpha.4 (`eb2d25d5869dad554e60ef1df1e05504898a98a6`) retain the relevant shapes. Dormant types
being serializable is not evidence that the historical UI persisted them; DEC-003 owns that audit.

## Production migration and failure behavior

DataStore's initialization migration runs before repository reads and writes, including default
profile initialization and schedule consumers. The explicit `migrateLegacyTagIdentifiers` method
uses the same conversion. Conversion validates related tag/profile collections before replacing
both atomically. Metadata stays in the JSON tree, preserving escaped strings and present optional
values/timestamps. Current-only inputs are not rewritten.

```mermaid
flowchart TD
    H["Historical synthetic preferences"] --> V["Validate collections and identity references"]
    V --> K["Derive installation-keyed HMAC fingerprints"]
    K --> P["Prepare and validate tags plus profiles"]
    P --> A["Atomic DataStore persistence"]
    A --> R["Close scope and reload store"]
    R --> C["Assert state, recovery, dismissal and authorization"]
    V -->|"Malformed or ambiguous legacy state"| F["Retain original persisted preferences"]
    K -->|"Key/fingerprint failure"| F
    P -->|"Invalid related collection"| F
    F --> E["Initialization fails without an unlock result"]
    E --> B["Known failing gate: runtime engine remains inactive"]
    B --> D["Production failure/recovery design required"]
    E -->|"Transient cause resolved before retry"| V
```

A missing, malformed, ambiguous, unknown, or conflicting non-null legacy reference aborts the
migration. It never falls back to a different tag. Case normalization matches the historical
case-insensitive UID lookup. Legacy implicit-any NFC policy does not enable current
`allowAnyEnrolledTag`; it remains closed unless a current policy explicitly opts in. A duration
profile's specific legacy reference must resolve before conversion. A null or omitted legacy
duration binding also aborts: converting it to a current null binding would permit manual or
any-enrolled-tag unlock. A current explicitly serialized `requiredTagId: null` retains its current
policy; migration does not reinterpret it. No duration timer or new trigger behavior is introduced. This rejection retains disk state but
fails the runtime acceptance gate above; it is not a completed fail-closed recovery solution.

Fingerprint failure or malformed related collections leave the original preferences available.
Failure raises a payload-free `LegacyTagMigrationException`; underlying parser/key messages are
not attached because they can contain raw identifiers. DataStore does not deliver partially
migrated state or allow defaults to overwrite the failed migration. The next initialization can
retry if the cause has resolved. The test harness's raw-file repair is test-only and cannot prove
production recovery; see the unmet runtime gate above. This does not add a recovery UI, repair Keystore loss, or solve arbitrary nonlegacy
corruption; keep the failed file private for explicit recovery, rather than clearing app data.

## What the tests establish

| Area | Tests and evidence |
| --- | --- |
| Historical and mixed data | `LegacyMigrationTest`, `UpgradeMigrationTest`: metadata, stable references, idempotence, mixed/current preservation, malformed/duplicate/unknown reference refusal. |
| Startup and rollback | `StartupMigrationTest`, `MigrationFailureTest`: first read/default writer wait for initialization; concurrent readers see no premigration value; null/throwing identity failures preserve on-disk state and allow retry. |
| Runtime failure acceptance | `MigrationEnforcementAcceptanceTest`: enabled failing gate described above. The other tests do not substitute for it. |
| Authorization and Keystore | `NfcIdentityFixtureTest`, `UpgradeMigrationTest`: unique IDs/fingerprints required for writes and matches; ambiguous current bytes remain stored but cannot authorize. Also production HMAC with isolated test alias, correct/other/unknown tag resolution, active profile retained, fresh key cannot authenticate old fingerprints. |
| Recovery and dismissal | `PersistedStateFixtureTest`: production save methods and reload, configured recovery friction retained; dismissed occurrence stays inactive with a positive schedule-window control. ENF-001's timing redesign is not covered. |
| History/preferences | Deterministic inclusive cutoff, just-outside expiry, 500 retained records, newest-first order, retention settings 1..3650, theme, repeated reload. |
| Backup | `BackupFixtureTest`, `BackupRestoreFixtureTest`, `ScheduleBackupConsistencyTest`: fresh production encryption, malformed/authentication/size/count/schedule failures, no partial restore, both active markers and a precheck/transaction race, imported profiles always inactive. |
| Dormant compatibility | `DormantCompatibilityFixtureTest`: individual serialized types and fields survive migration and encrypted roundtrip, without wiring them into product behavior. |

History fixtures at the count and time boundaries are generated in tests using synthetic IDs and
explicit timestamps. `saveFocusSession` keeps at most 500 records and includes records whose end
time equals the retention cutoff. Reading or restoring history does not itself apply that cap.
The backup codec allows a count up to 10,000, subject to its **786,432-byte plaintext** and
**1,048,576-byte envelope** limits; 10,000 ordinary records can exceed the byte limit. Tests show
501 accepted backup records, count rejection at 10,001, and independent byte rejection at 10,000.

Valid envelopes are generated with production `BackupCodec.encrypt`, its full 210,000-iteration
PBKDF2-HMAC-SHA256 cost, and fresh random salt/nonce. Mutated headers/ciphertext test parser and
authentication refusal. A test-only adversarial writer starts from a production-generated header
and KDF settings, uses a fresh random nonce, and supplies authenticated invalid plaintext so
restore's post-decryption validation is also exercised. No fixed nonce or weakened production KDF
is used; no real backup or passphrase is committed.

Restore clears imported active flags/timestamps and never imports an active profile ID. It replaces
history when included and clears it when omitted. Existing destination recovery and occurrence
keys remain unchanged; they are not fields in the backup snapshot. Tag custom payloads are not
exported. Backup validation rejects duplicate tag fingerprints, empty schedule day sets, missing
schedule profile IDs/names, and references to profiles absent from the snapshot, as well as invalid
time ranges. Previously accepted inconsistent snapshots may now fail validation; correct their
source records before exporting again. Rejected restores leave every destination preference
unchanged. Deleting an inactive profile also removes its dependent schedules in the same
DataStore transaction; either active marker or malformed related collections refuse deletion.
Schedule saves recheck profile existence in their transaction, so a stale editor cannot persist a
dangling reference. Only defaults for existing profiles are materialized on these paths. Preserved fingerprints remain bound to their original installation key.

## Existing malformed-state behavior (DATA-001 evidence)

For nonlegacy malformed values, reading a fallback is not a successful repair:

| Stored value | Flow or snapshot result | Stored bytes after read |
| --- | --- | --- |
| Malformed profiles/tags/history JSON | Empty collection | Unchanged |
| Malformed schedules JSON | Two disabled defaults from `schedulesFlow`; empty schedules in backup snapshot | Unchanged |
| Malformed recovery/occurrence JSON | Null | Unchanged |
| Unknown theme | SYSTEM | Unchanged |
| Persisted out-of-range retention integer | The same integer (validation applies to setter/backup codec) | Unchanged |
| Active ID with undecodable profiles | ID flow retains it, repository active profile is null | Unchanged |

Absent values use ordinary defaults without persisting them on read. A later normal save can
replace a malformed collection with valid new content, losing the original corrupt source. Tests
make that distinction explicit. DATA-001 owns typed corruption outcomes, quarantine/recovery and
UI; these characterization tests do not claim those problems are fixed.

## Running safely

Use the repository's JDK 17 toolchain and Android SDK 35. Device tests additionally need an isolated
Android emulator/device (API 26+) with platform-tools and a supported system image. Use a fresh
AVD dedicated to tests: connected tests install the app/test APK, and existing device tests exercise
installation keys. Never select a personal or production installation. Discover JDK/SDK paths
locally (`java -version`, Android Studio SDK settings, `sdkmanager --list_installed`); configure
`JAVA_HOME` and `ANDROID_HOME` locally, or ignored `local.properties`, without committing paths.

```bash
./gradlew testDebugUnitTest --tests '*LegacyMigrationTest' --tests '*StartupMigrationTest' \
  --tests '*NfcIdentityFixtureTest' --tests '*BackupFixtureTest' --tests '*DormantCompatibilityFixtureTest' --tests '*FixtureCatalogTest' \
  --rerun-tasks --no-build-cache --no-daemon

adb devices -l
# Replace with the serial of the dedicated test emulator shown above.
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=websnag.elopenmike.com.core.data \
  --rerun-tasks --no-build-cache --no-daemon

./gradlew testDebugUnitTest lintDebug assembleDebug --continue --rerun-tasks --no-build-cache --no-daemon
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --rerun-tasks --no-build-cache --no-daemon
```

The full Android command currently fails the runtime acceptance gate above. That failure is an
unmet requirement, not an expected-success test or a reason to narrow final validation selectors.
Confirm nonzero test counts and no unexpected skips; inspect the failed assertion as well as
successful task execution for the other suites. Inspect
`app/build/test-results/testDebugUnitTest`, `app/build/outputs/androidTest-results/connected`, and
`app/build/reports/lint-results-debug.html`. Cached or skipped tasks are not fresh execution.
The fixture tests run on an isolated API 36 arm64 emulator, including the reproduced failure;
that is not a complete
Android-version/device matrix. CI still runs its existing JVM/lint/build gates; CI-001 owns a
new device-CI lane.

Each new device test uses a unique directory below the target application's cache, never the
production preferences filename. It cancels and joins the old DataStore scope before reopening
the same file and deletes only its own temporary directory. Keystore tests use unique
`synthetic.websnag.migration.*` aliases and remove only those aliases. Defaults/schedule consumers
cannot race with fixture storage because they use a different DataStore file.

## Adding a case

1. Inspect the source tag/commit first; record exact provenance or label a new compatibility/
   corruption probe explicitly synthetic. Include every relevant state key, not only tags.
2. Add a named v1 JSON fixture or a deterministic boundary generator with a stated purpose.
   Keep fixture version separate from app/envelope versions. Mirror JSON into device assets and
   update `FixtureCatalogTest`'s catalog; it prevents divergent JVM/device inputs.
3. Exercise the actual migration/store/repository/policy. Test the failure before changing
   production behavior, then assert preservation/authorization and close/reopen on Android.
4. Specify success versus rejection and rollback. Test idempotence and retained separate state.
   Do not satisfy a case by weakening authorization, bypassing production randomness/KDF, or
   silently dropping records. If a case needs DATA-001/DEC-003/ENF-001 redesign, record its exact
   limitation instead of claiming completion.
5. Assert booleans for raw-state equality/absence; do not print raw fixture preferences in failed
   comparisons, diagnostics, screenshots, reports or exports. Raw synthetic UID strings belong
   only in migration inputs, never expected protected output. Keep all encrypted samples ephemeral.

Rollback means retaining the untouched source when conversion fails. A successful conversion is
one-way identity protection; do not reconstruct raw UIDs or revert to raw-UID storage. Any rollback
build must continue reading the current protected shape. Back up synthetic test evidence locally
when investigating failure; never use real device data to extend the fixture corpus.
