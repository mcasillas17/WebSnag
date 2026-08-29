# Safe P2 Differentiation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver local-only backup, activity attestation, privacy controls, and honest NFC
assurance without weakening WebSnag's safety boundaries.

**Architecture:** Keep cryptographic parsing/serialization in pure Kotlin components that can be
unit-tested. Keep Android Keystore, DataStore atomic persistence, and Storage Access Framework
integration behind narrow Android-facing adapters. Refuse restore while a profile is active.

**Tech Stack:** Kotlin, Kotlinx Serialization, Android DataStore, Android Keystore, Compose,
Storage Access Framework, JUnit.

---

### Task 1: Define bounded portable backup protocol

**Files:**
- Create: `app/src/main/java/websnag/elopenmike/com/core/backup/BackupCodec.kt`
- Create: `app/src/main/java/websnag/elopenmike/com/core/backup/BackupModels.kt`
- Test: `app/src/test/java/websnag/elopenmike/com/BackupCodecTest.kt`

- [ ] **Step 1: Write failing protocol tests** for deterministic payload normalization, encrypted
  round-trip, wrong-passphrase failure, GCM tampering, unsupported versions, excessive envelope
  sizes, duplicate IDs, invalid schedule times, and rejection before persistence.
- [ ] **Step 2: Run**
  `./gradlew testDebugUnitTest --tests '*BackupCodecTest' --no-daemon`
  **Expected:** compilation/test failures because backup types do not exist.
- [ ] **Step 3: Implement** a fixed-magic version-one AES-GCM envelope with PBKDF2-HMAC-SHA256,
  random salt/nonces, strict JSON decoding, validation limits, and typed failures.
- [ ] **Step 4: Re-run** the Task 1 test command.
- [ ] **Step 5: Commit** the models, codec, and tests.

### Task 2: Add atomic DataStore backup/restore and deletion controls

**Files:**
- Modify: `app/src/main/java/websnag/elopenmike/com/core/data/LocalDataStore.kt`
- Create: `app/src/main/java/websnag/elopenmike/com/core/backup/BackupRepository.kt`
- Test: `app/src/test/java/websnag/elopenmike/com/BackupRepositoryTest.kt`

- [ ] **Step 1: Write failing tests** that a snapshot excludes raw NFC custom payloads, preserves
  optional-history choice, fails active-lock restore, and writes all values through one atomic
  replacement seam.
- [ ] **Step 2: Run**
  `./gradlew testDebugUnitTest --tests '*BackupRepositoryTest' --no-daemon`
  **Expected:** failures because snapshot and restore APIs do not exist.
- [ ] **Step 3: Implement** snapshot construction, active-session conflict rejection, one
  DataStore `edit` restore transaction, history retention/deletion, and full local-data deletion.
- [ ] **Step 4: Re-run** the Task 2 test command.
- [ ] **Step 5: Commit** the repository, DataStore integration, and tests.

### Task 3: Add device-bound, locally verifiable activity attestation

**Files:**
- Create: `app/src/main/java/websnag/elopenmike/com/core/activity/ActivityAttestation.kt`
- Create: `app/src/main/java/websnag/elopenmike/com/core/activity/AndroidKeystoreActivitySigner.kt`
- Test: `app/src/test/java/websnag/elopenmike/com/ActivityAttestationTest.kt`
- Test: `app/src/androidTest/java/websnag/elopenmike/com/AndroidKeystoreActivitySignerTest.kt`

- [ ] **Step 1: Write failing tests** for canonical record ordering, offline verification,
  tampering/signature rejection, and explicit missing-key behavior.
- [ ] **Step 2: Run**
  `./gradlew testDebugUnitTest --tests '*ActivityAttestationTest' --no-daemon`
  **Expected:** failures because attestation APIs do not exist.
- [ ] **Step 3: Implement** canonical JSON, detached ECDSA P-256 signing, public-key inclusion,
  verifier, typed key-loss errors, and Android Keystore signer.
- [ ] **Step 4: Re-run** focused unit tests; run the Android test if an emulator is available.
- [ ] **Step 5: Commit** attestation code and tests.

### Task 4: Establish NFC assurance boundary and privacy facts

**Files:**
- Create: `app/src/main/java/websnag/elopenmike/com/core/nfc/TagCredentialVerifier.kt`
- Create: `app/src/main/java/websnag/elopenmike/com/core/privacy/PrivacyStatus.kt`
- Modify: `app/src/main/java/websnag/elopenmike/com/core/model/NfcTagRecord.kt`
- Test: `app/src/test/java/websnag/elopenmike/com/NfcCredentialAndPrivacyTest.kt`

- [ ] **Step 1: Write failing tests** for low-assurance ordinary tags, explicit unsupported
  authenticated hardware, deterministic protocol fixtures, and `INTERNET` permission reporting.
- [ ] **Step 2: Run**
  `./gradlew testDebugUnitTest --tests '*NfcCredentialAndPrivacyTest' --no-daemon`
  **Expected:** failures because assurance and privacy types do not exist.
- [ ] **Step 3: Implement** the typed verifier abstraction, unavailable authenticator, assurance
  labels, and privacy-status factory.
- [ ] **Step 4: Re-run** focused tests.
- [ ] **Step 5: Commit** the security-boundary code and tests.

### Task 5: Wire accessible local UI and SAF boundaries

**Files:**
- Create: `app/src/main/java/websnag/elopenmike/com/ui/privacy/PrivacyScreen.kt`
- Modify: `app/src/main/java/websnag/elopenmike/com/MainActivity.kt`
- Modify: `app/src/main/java/websnag/elopenmike/com/ui/setup/PermissionsScreen.kt`
- Test: `app/src/androidTest/java/websnag/elopenmike/com/PrivacyScreenTest.kt`

- [ ] **Step 1: Write failing UI/instrumentation tests** for visible local-only/privacy claims and
  export/import entry points.
- [ ] **Step 2: Run** the focused Android test when a device exists.
- [ ] **Step 3: Implement** `ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT` flow without
  persistable URI storage, explicit passphrase confirmation, error presentation, activity export,
  data deletion confirmation, and truthful assurance text.
- [ ] **Step 4: Run** focused Android tests, or record the exact unavailable-device command.
- [ ] **Step 5: Commit** UI and tests.

### Task 6: Document boundaries and validate branch

**Files:**
- Create: `docs/architecture/domain-blocking-decision.md`
- Modify: `README.md`
- Create: `docs/security/local-export-and-attestation.md`

- [ ] **Step 1: Document** backup format/version behavior, attestation trust model/key loss,
  retention/delete controls, low-assurance NFC semantics, and domain-blocking deferral.
- [ ] **Step 2: Run**
  `./gradlew testDebugUnitTest lintDebug assembleDebug --continue --no-daemon`.
- [ ] **Step 3: Inspect** `git diff origin/main...HEAD`, manifest component exposure, fixture
  provenance, and a secret scan before review.
- [ ] **Step 4: Commit** documentation and final validation fixes.
