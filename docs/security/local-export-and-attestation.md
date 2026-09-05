# Local Export and Activity Attestation

Backups use a versioned `WSB1` envelope encrypted with AES-256-GCM and a key derived from a
user-supplied passphrase using PBKDF2-HMAC-SHA256. Each export receives a new random salt and
nonce. Import rejects unknown versions, malformed lengths, bad authentication, oversized payloads,
invalid record counts, duplicate IDs, and invalid schedules before any DataStore write. Restoring
is one DataStore transaction and is refused while a focus profile is active. Restore replaces
history when included and clears existing history when it is not. Raw NFC custom payloads are not
exported.

The WSB1 version describes the encrypted envelope, not a DataStore schema. Snapshot validation
also rejects duplicate tag fingerprints, empty schedule day sets, missing schedule profile
IDs/names, and references to profiles absent from the snapshot. Restore removes imported active
flags and activation timestamps; existing destination recovery and schedule-occurrence state is
preserved. NFC fingerprints remain bound to the original installation's Android Keystore HMAC
key, so an encrypted backup does not make them portable authentication credentials.

Legacy identity conversion now runs during DataStore initialization, before repository consumers.
It validates and commits tags/profile references together; failures retain original preferences
and report a payload-free error. Ambiguous current NFC identities cannot authorize a match.
**Runtime migration-failure recovery remains incomplete:** the enabled Android acceptance test
shows that a failed initialization can leave the enforcement engine inactive despite retained
active-profile bytes. A test-harness file repair is not a production recovery route. See the
[migration guide](../testing/migrations.md#unmet-runtime-acceptance-criterion) before treating this
work as upgrade or fail-closed recovery evidence.

Activity attestations sign canonically sorted session records with an Android Keystore P-256 key.
The export contains the public key and can be verified offline. This proves only that a particular
app installation signed the included records; it is not non-repudiation, identity proof,
complete-history proof, or a claim that the records describe real-world behavior. The private key
is non-exportable. Reinstalling or clearing app data loses it, so prior exports remain verifiable
but new exports cannot be linked cryptographically to prior ones.

Ordinary UID and static NDEF tags are **low assurance**: they can be copied or replayed. WebSnag
does not describe them as challenge-response or clone-resistant. Authenticated tag hardware is
not enabled in this build.

## Diagnostics export

Unlike the encrypted backup and the Keystore-signed activity export above, the diagnostics export
is plaintext local JSON: no encryption, no signature, and no authenticity or confidentiality claim
once the file leaves the app. The user selects the destination document through the Storage
Access Framework; nothing is written until they do. The payload is schema v1
(`DIAGNOSTICS_SCHEMA_VERSION`) and is hard-bounded: at most 16,384 bytes
(`DIAGNOSTICS_MAX_EXPORT_BYTES`), at most 5 remediation actions
(`DIAGNOSTICS_MAX_REMEDIATION_ACTIONS`), and at most 80 characters per externally sourced display
string (`DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH`, covering app version name, device manufacturer,
and device model); an oversized or unsafe payload is rejected rather than truncated. It never
includes raw or HMAC NFC identifiers, profile/tag names, package block/allow lists, Wi-Fi
SSIDs/BSSIDs, backup passphrases or derived keys, activity history, notification/Accessibility
event content, or filesystem paths containing usernames.

The report's last-local-error field only reflects the categories this build actually
instruments through `recordLocalError`: `BACKUP`, `STORAGE`, and `DIAGNOSTICS` (plus an `UNKNOWN`
catch-all), alongside the separately typed schedule-reconciliation outcome/timestamp. It is not
exhaustive activity or event logging, and it never carries a payload or message value regardless
of category.
