# Safe P2 Differentiation Design

## Scope

WebSnag P2 remains local-only. It adds portable, passphrase-encrypted backups; device-bound,
tamper-evident activity attestations; a privacy-status/control surface; and explicit NFC
assurance boundaries. It does not add cloud services, Device Admin, traffic inspection, a VPN,
or browser/content inspection.

## Encrypted backup and restore

`BackupCodec` owns a versioned binary envelope: a fixed magic value, one supported schema
version, an explicit PBKDF2-HMAC-SHA256 work factor, a random 16-byte salt, a random 12-byte
AES-GCM nonce, and authenticated ciphertext. The encrypted deterministic JSON payload contains
profiles, schedules, NFC metadata, theme preference, retention preference, and optionally focus
history. Raw NFC custom payloads are excluded.

The decoder accepts only bounded envelopes and supported parameters, rejects unknown versions,
bad authentication tags, malformed JSON, unknown fields, duplicate identifiers, invalid model
values, and excessive strings/counts before any write. Restore replaces the stored backupable
data in one DataStore transaction. It refuses to run while a profile is actively locking, so an
import can never silently weaken an active lock. The active profile is never imported.

## Activity attestation

`ActivityAttestation` serializes records deterministically (stable field order and records sorted
by ID), then signs the exact UTF-8 bytes with a P-256 Android Keystore key. The export includes
the signing public key, algorithm, payload, and signature; anyone can verify it offline with the
included public key. It proves only that this app installation's private key signed those records.
It is not identity verification, non-repudiation, complete-history proof, or proof that records
reflect real-world behavior. Reinstalling or clearing app data loses the non-exportable key, so
old attestations remain verifiable but no longer link cryptographically to new exports.

## Privacy and controls

The Settings flow reports the package's actually declared `INTERNET` permission state, local data
categories, no-telemetry posture, Accessibility scope (`canRetrieveWindowContent=false`), and
delete/retention/export controls. Users can delete history or all locally stored WebSnag data.

## NFC and domains

Existing UID and ordinary static-NDEF behavior remains supported and is labeled low assurance:
UIDs/static NDEF are identifiers, not clone-resistant credentials. P2 introduces a
`TagCredentialVerifier` boundary and an unavailable authenticated-hardware implementation with
deterministic fixtures. No static shared secret or NDEF field is represented as challenge-response.

Domain blocking is deferred by ADR: a browser-independent implementation would require
privacy-invasive network or accessibility capabilities that are out of scope.
