# Local Export and Activity Attestation

Backups use a versioned `WSB1` envelope encrypted with AES-256-GCM and a key derived from a
user-supplied passphrase using PBKDF2-HMAC-SHA256. Each export receives a new random salt and
nonce. Import rejects unknown versions, malformed lengths, bad authentication, oversized payloads,
invalid record counts, duplicate IDs, and invalid schedules before any DataStore write. Restoring
is one DataStore transaction and is refused while a focus profile is active. Restore replaces
history when included and clears existing history when it is not. Raw NFC custom payloads are not
exported.

Activity attestations sign canonically sorted session records with an Android Keystore P-256 key.
The export contains the public key and can be verified offline. This proves only that a particular
app installation signed the included records; it is not non-repudiation, identity proof,
complete-history proof, or a claim that the records describe real-world behavior. The private key
is non-exportable. Reinstalling or clearing app data loses it, so prior exports remain verifiable
but new exports cannot be linked cryptographically to prior ones.

Ordinary UID and static NDEF tags are **low assurance**: they can be copied or replayed. WebSnag
does not describe them as challenge-response or clone-resistant. Authenticated tag hardware is
not enabled in this build.
