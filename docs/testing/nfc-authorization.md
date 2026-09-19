# NFC authorization and recovery

TEST-003 exercises software authorization through production repositories, Android Keystore,
Preferences DataStore, the enforcement engine and the Dashboard/blocker. It reuses the migration
harness and ENF-001 recovery/lifecycle tests rather than implementing another timer or policy.

## Authority and user-visible behavior

ReaderMode supplies decoded identifiers; an Intent extra is not a scan. `NfcActionResolver`
proposes an action without activating or ending a session. Both Activity scan callbacks call the
engine. `requestNfcEnd` obtains a fresh repository/HMAC match, checks the resolved profile/session
against current persisted policy, and commits with session and storage-generation comparisons.
Caller-supplied enrollment or cooldown-completion booleans cannot authorize an end.

Activation checks nonempty valid enrollment, inactive state and storage generation in the same
DataStore transaction. A new activation cannot replace an existing session, nor commit after
the last tag was deleted during its preliminary read. Dashboard and NFC feedback use the current
`ActivationResult`; a previous storage/recovery error cannot mask a new missing-tag refusal.

While a session is active, changes to the enrolled ID/fingerprint set and delete-all are refused.
This includes enrollment that would broaden an explicit any-enrolled policy. Labels, descriptions
and usage timestamps can still change; timestamp updates operate on the current collection in one
transaction and cannot resurrect a deleted record. Existing active-profile edit/delete and backup
restore guards remain in force. Refused mutations preserve stored state and surface an error.

A scan's reads and usage write share a three-second resolver deadline. An observed storage failure
invalidates the scan even if repair finishes before that deadline. Engine activation/end writes
also compare the captured storage generation inside their transaction. A queued old NFC end
cannot commit or emit success after failure/recovery; the user must scan again.

## Coverage matrix

| Area | Production-path evidence |
| --- | --- |
| Enrollment and persisted policy | `NfcAuthorizationDeviceTest` enrolls synthetic inputs using a uniquely owned Android HMAC key, reloads DataStore, verifies stable IDs/fingerprints and policy, and checks raw-input absence without printing values. |
| Specific/other/explicit-any policy | Real enrolled lookups feed resolver proposals and engine commits. A different enrolled tag is denied under specific policy; both enrolled tags work under explicit-any. Unknown, deleted and malformed inputs are denied. |
| Missing binding and ManualOnly | Missing binding never becomes implicit-any; ManualOnly ignores NFC and retains its manual path. Activation without enrolled tags is rejected, including deletion of the last tag between read and write. |
| Stale commands | Same-profile session replacement before end, replacement during enrollment lookup, a later session before activation, and storage failure/repair during resolution, activation and the final end write. The old end produces no end event; a fresh scan has a positive control. |
| Keystore absence/unusable key | Real key deletion proves fresh-key HMAC cannot match retained fingerprints. Ordinary re-enrollment creates a new ID without changing old records/references; explicitly rebinding the inactive profile restores subsequent authorization. A uniquely owned incompatible Android key exercises actual MAC initialization failure without silently replacing an unusable key. Configured emergency recovery still works. |
| Protected mutations | `ProtectedMutationDeviceTest` covers profile edit/delete, required-tag deletion/replacement, new enrollment during explicit-any, delete-all and encrypted restore refusal. Both active markers are checked independently; metadata/usage has a positive control and an interleaved deletion/activation case. |
| Real UI callbacks | `NfcAuthorizationActivityTest` invokes the same internal decoded-scan callbacks used after ReaderMode decoding in MainActivity and BlockOverlayActivity. Unknown input keeps blocking; the expected enrolled input ends the session through the engine. The Tag Hub displays protected identity and a visible deletion refusal. |
| External Intent boundary | Actual Android launch and same-instance `onNewIntent` deliveries carry TAG/TECH/NDEF, VIEW and MAIN actions, claimed enrollment/unlock fields and malformed extras. They produce no reader event, tag usage, recovery change or unlock. Manifest checks still require a non-exported blocker and no generic NFC dispatch registration. |
| Recovery policy and presentation | Extended `EmergencyRecoveryActivityTest` uses real enrollment and persisted sessions for enabled/disabled, required/optional phrase, cancellation, Activity recreation, legacy continuity and stale manual callbacks. `EmergencyRecoveryScreenTest` covers failed activation feedback in both no-tag/storage-error directions. |
| Timing and durable lifecycle | Existing `EmergencyRecoverySafetyTest`, `EmergencyRecoveryPersistenceTest`, `EmergencyRecoveryDeviceTest` and the three host-ordered `EmergencyRecoveryLifecycleTest` phases retain exact elapsed-time boundaries, cancellation, restoration, completion and stale-request checks. No replacement lifecycle harness or timing model is introduced. |
| Storage recovery and escape paths | Existing migration acceptance/recovery UI remains required. New unreadable-storage scan tests preserve the active session while the separate lockdown can be paused; phone, telecom, emergency, app and registered dialer exemptions remain allowed. |

Every new safety method runs in smoke and full and is required by the report gate. See the
[device-test guide](device-tests.md) for the complete **108 smoke / 125 full** matrix, mandatory
process/reboot order, bounds, cleanup and hosted evidence requirements. JVM policy tests remain
useful, but fake-repository tests alone do not establish enrollment or Android Keystore behavior.

## Lost keys and re-enrollment

The availability probe does not create a key. A later lookup can create a fresh key if the old
alias is absent, but its HMAC does not authenticate old fingerprints; neither policy nor the old
records are rewritten. A present but unusable key also denies lookup/enrollment and is not silently
replaced. The device test models an incompatible key, not every vendor's hardware invalidation.

End the protected session through its configured recovery route before changing enrollment.
Ordinary enrollment after key loss cannot match the old record and creates a new enrolled ID;
rebind the inactive profile explicitly before starting a new session. The repository also supports
explicit existing-ID re-enrollment; that is not an automatic repair performed by the UI.
An unusable existing key remains a key-availability problem after emergency unlock; this suite
does not add a key-reset UI, clear application data, or make backup fingerprints portable.

Emergency recovery availability, phrase policy and elapsed-time requirements are unchanged.
Disabled emergency recovery does not acquire a new bypass. Returning home, opening WebSnag and
system/emergency exemptions remain distinct from ending a protected session. Unreadable-storage
recovery is also distinct: its deliberate pause only removes extra lockdown, never an active
session's rules. See [migration and recovery guidance](migrations.md).

## Test integrity, privacy and hardware limits

Use only the explicitly selected disposable AVD described in the device guide. Core fixtures own
unique synthetic DataStore directories and Keystore aliases and join scopes before reopening or
deleting them. Activity tests use the harness's fresh disposable installation and remove only
their own profiles/tags. Never run this suite against a personal installation.

Synthetic raw identifiers exist only as inputs; new enrolled state stores keyed fingerprints and
stable IDs. Assertions about raw-state equality/absence are boolean-only to avoid value dumps.
The uploaded artifact remains bounded class/method/status metadata, not logs, preferences, keys,
backup data or screenshots. No permission, exported component, debug switch or scan-injection
receiver is added. The internal callback seams start after physical decoding and still use the
same production resolver, repository and engine.

Emulator/callback success proves software policy, **not physical NFC reading, antenna behavior,
clone resistance, authenticated-tag protocols, vendor-specific key invalidation, or a separate
attacker-UID run**. Ordinary UID/static NDEF tags remain low-assurance, copyable identifiers;
at-rest HMAC is not tag authentication. Physical-reader results are untested here and must be
recorded separately without physical-tag identifiers. TEST-001 Accessibility E2E, schedule
lifecycle tasks and signed package upgrades remain separate work.

## Compatibility and rollback

There is no new persisted schema or exported entrypoint. Existing protected fingerprints, stable
references and ENF-001 recovery records remain readable. Newly enforced mutation guards can
refuse operations that previously succeeded, and activation can no longer replace an active
session; these are intentional safety corrections, not data migrations.

Do not roll back by clearing data, reconstructing raw identifiers, trusting caller booleans or
restoring non-atomic authorization. A rollback must preserve session/generation comparisons and
active-state guards, as well as #49's elapsed-time/boot handling. Old wall-clock recovery behavior
is not a safe downgrade. Release signing and store distribution remain owner-deferred.
