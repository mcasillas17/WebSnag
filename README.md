<p align="center">
  <img src="docs/brand/png/wordmark-unbounded-dark.png" width="380" alt="WebSnag Logo" />
</p>

<h1 align="center">WebSnag</h1>

<p align="center">
  <em>Environmental & Context-Aware Tangible Self-Control System for Android</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT" /></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg" alt="Platform" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.3.20-purple.svg" alt="Kotlin" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-brightgreen.svg" alt="Compose" /></a>
</p>

> **"WebSnag makes the user's intentions stronger than their impulses."**

WebSnag is an open-source, local-first Android application for intentional digital distraction blocking, tangible NFC physical locking, and context-aware self-control.

Inspired by physical-first focus devices like Brick, WebSnag turns your smartphone into an intentional tool. Users decide how they want their future behavior to be while thinking clearly, and WebSnag applies local profiles, physical NFC tags, and an emergency-recovery path as intentional friction.

---

## Architecture Overview

WebSnag is designed around a clean, reactive pipeline:

```
Physical NFC Triggers → Profiles & Rules → Enforcement Engine → Accessibility Interception
```

```mermaid
flowchart TD
    subgraph Triggers ["1. Triggers & Gestures"]
        T1["NfcTagTrigger (Tap to Lock / Unlock)"]
        T2["HoldToLockGesture (Tactile 1.5s Press)"]
        T3["TimeScheduleTrigger (Roadmap)"]
    end

    subgraph Core ["2. Profiles & Filtering Modes"]
        P1["Deep Work (Allowlist / Dumbphone Mode)"]
        P2["Bedtime Rest (Distraction Blocklist Mode)"]
        R["Rule Evaluator & Lock Guard"]
        T1 --> R
        T2 --> R
        P1 --> R
        P2 --> R
    end

    subgraph Conditions ["3. Unlock Conditions"]
        U1["RequireNfcTag (Specific or Any Tag)"]
        U2["EmergencyCooldown (5-Min Delay + Intention)"]
    end

    subgraph Enforcement ["4. Android Enforcement Engine"]
        EE["EnforcementEngine (O(1) In-Memory Cache)"]
        AS["WebSnagAccessibilityService"]
        OA["BlockOverlayActivity (Compose Blocker UI)"]
        DS["LocalDataStore (Persistent History)"]
        
        R --> EE
        Conditions --> EE
        EE --> AS
        EE --> DS
        AS -->|"Intercept blocked launch"| OA
    end
```

### Architectural Principles

1. **Local-First & Private**: Operates 100% offline with zero cloud accounts, telemetry, or tracking servers.
2. **Intentional Friction**: Designed for standard consumer Android (non-MDM). It adds deliberate physical friction but does not claim zero-bypass enforcement.
3. **Reactive & Battery-Efficient**: Event-driven Android Accessibility events (`TYPE_WINDOW_STATE_CHANGED`) rather than battery-draining background polling loops.
4. **NFC Trust Boundary**: Enrolled tag identifiers are stored only as Android-Keystore-keyed HMAC fingerprints. A profile requires its specific enrolled tag by default; any-enrolled behavior is an explicit policy. NFC UIDs are not clone-resistant credentials.

---

## Core Features

* 🏷️ **NFC Tag Hub & Scanner**: Enroll physical NFC tags with radar pulse scanning, custom naming, and usage tracking.
* 🔒 **Tactile "Hold to Lock" Remote Action**: 1.5-second press-and-hold button with progressive haptic feedback to lock profiles on the go.
* 📅 **Brick-Style Automated Schedules & Routines**: Set recurring focus windows (e.g., Workday Mon-Fri 9:00 AM - 5:00 PM, Nightly Bedtime 10:30 PM - 7:00 AM) that automatically activate profiles and enforce boundaries.
* 🛡️ **NFC Lockout Guard**: Rejects unknown and deleted tags; NFC-required profiles must have a specific enrolled tag before they can be saved.
* 📵 **Allowlist (Dumbphone Mode)**: Choose between standard **Blocklist Mode** (*"Block selected apps"*) or strict **Allowlist Mode** (*"Block EVERYTHING except essential tools like Phone, Maps & Notes"*).
* 📊 **Brick-Style Activity & Calendar**:
  * **Split Today / Average Metric Header** with active streak counter (`🔥 1d streak`).
  * **Interactive Calendar Day Tiles** (`AUG 24`, `AUG 23`...) with session dots.
  * **7-Day Hour:Minute Distribution Chart**.
  * **Day Session Drilldown Feed** inspecting exact start/end times and prevented distraction attempts.
* 🌓 **Dynamic Theme Engine**: Full support for Dark Theme, Light Theme, and System Default.
* 🧘 **Calm Blocker Screen**: Fullscreen Jetpack Compose overlay with breathing animation, active focus duration timer, and instant NFC unlock listener.
* ⏳ **Emergency Unlock Friction**: Deliberate cooldown timer (5-minute delay + typed intention phrase) to prevent impulsive bypasses without risking permanent lockouts.
* 🔐 **Portable Private Backups**: Passphrase-encrypted local export/import with atomic restore and active-lock conflict protection.
* 🧾 **Locally Verifiable Activity Exports**: Device-key-signed focus history exports, with explicit installation-bound trust limits.
* ⏳ **Emergency Unlock Friction**: A configured local cooldown and typed intention phrase provide recovery without creating an unrecoverable lock. Emergency calling and the device dialer are always exempt from blocking.
* 📅 **Durable schedules**: Schedule occurrences, dismissals, and end reasons persist locally. Android alarms reconcile windows after reboot, timezone or clock changes; timing is explicitly best-effort if exact alarms are unavailable.

---

## App Screenshots

| Dashboard (Hold to Lock) | Profile Quick-Switcher | Schedules (Automated Routines) |
| :---: | :---: | :---: |
| <img src="docs/screenshots/01_dashboard_wordmark_idle.png" width="260" /> | <img src="docs/screenshots/02_profile_dropdown.png" width="260" /> | <img src="docs/screenshots/02_schedules_overview.png" width="260" /> |

| Schedule Editor | Activity (Split Header & Chart) | Activity (Day Drilldown) |
| :---: | :---: | :---: |
| <img src="docs/screenshots/02_schedule_editor.png" width="260" /> | <img src="docs/screenshots/03_activity_overview.png" width="260" /> | <img src="docs/screenshots/04_activity_day_selected.png" width="260" /> |

| NFC Hub | Physical Tag Enrollment | Settings & System Setup |
| :---: | :---: | :---: |
| <img src="docs/screenshots/05_nfc_hub.png" width="260" /> | <img src="docs/screenshots/05_enroll_tag_screen.png" width="260" /> | <img src="docs/screenshots/07_settings_system_setup.png" width="260" /> |

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── res/
│   ├── drawable/ (websnag_logo_circle.png, websnag_wordmark_dark.png, ic_launcher_*)
│   ├── mipmap-*/ (adaptive & legacy launcher icons)
│   ├── values/ (colors.xml, strings.xml, themes.xml, websnag_colors.xml)
│   └── xml/ (accessibility_service_config.xml)
└── java/websnag/elopenmike/com/
    ├── WebSnagApp.kt                  # Application container & dependency wiring
    ├── MainActivity.kt                # Jetpack Compose Navigation & NFC host
    ├── core/
    │   ├── model/
    │   │   ├── Trigger.kt             # Sealed trigger hierarchy
    │   │   ├── UnlockCondition.kt     # Unlock requirements & friction policies
    │   │   ├── Profile.kt             # Blocking profile domain model
    │   │   ├── ScheduleRecord.kt      # Recurring focus routine model
    │   │   ├── NfcTagRecord.kt        # Enrolled NFC tag representation
    │   │   ├── FocusSessionRecord.kt  # Focus history and session metrics
    │   │   ├── AppThemeMode.kt        # Dark / Light / System theme enum
    │   │   ├── AppInfo.kt             # Installed app metadata & categories
    │   │   └── EnforcementState.kt    # System-wide blocking state
    │   ├── data/
    │   │   ├── LocalDataStore.kt      # DataStore + Kotlinx Serialization persistence
    │   │   ├── ProfileRepository.kt   # Profile CRUD & presets
    │   │   ├── NfcTagRepository.kt    # Tag enrollment repository
    │   │   └── InstalledAppsRepository.kt # PackageManager app scanner
    │   ├── schedule/
    │   │   └── ScheduleManager.kt     # Background routine evaluation & auto-lock
    │   ├── nfc/
    │   │   ├── NfcManager.kt          # Modern ReaderMode coordinator
    │   │   ├── NfcActionResolver.kt   # Tag tap action dispatcher
    │   │   └── NfcPayloadHelper.kt    # UID conversion & NDEF helpers
    │   └── enforcement/
    │       └── EnforcementEngine.kt   # Central reactive blocking coordinator
    ├── service/
    │   └── WebSnagAccessibilityService.kt # Low-latency window state interceptor
    └── ui/
        ├── theme/ (Color.kt, Theme.kt, Type.kt)
        ├── navigation/ (Screen.kt)
        ├── dashboard/ (DashboardScreen.kt, DashboardViewModel.kt)
        ├── schedule/ (ScheduleScreen.kt, ScheduleEditorScreen.kt, ScheduleViewModel.kt)
        ├── activity/ (ActivityScreen.kt, ActivityViewModel.kt)
        ├── profiles/ (ProfilesScreen.kt, ProfileEditorScreen.kt, ProfilesViewModel.kt)
        ├── tags/ (TagsScreen.kt, EnrollTagScreen.kt, TagsViewModel.kt)
        ├── overlay/ (BlockOverlayActivity.kt, BlockOverlayScreen.kt)
        └── setup/ (PermissionsScreen.kt)
```

---

## Building & Sideloading

### Prerequisites
* Android Studio Ladybug / Iguana or later
* JDK 17+
* Android SDK 35 (Android 15)

### Build Debug APK
```bash
./gradlew assembleDebug
```
Output APK is located at: `app/build/outputs/apk/debug/app-debug.apk`

### Install to Connected Device via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Continuous Integration and Repository Security

Pull requests targeting `main` and pushes to `main` are validated by GitHub Actions. A push to `main` is the post-merge validation path; dependency review itself is pull-request-only because it compares the proposed dependency graph with the base branch.

| Automation | When it runs | Why it exists |
| --- | --- | --- |
| [CI](.github/workflows/ci.yml) | Pull requests, pushes to `main`, and manual dispatches | Runs unit tests and Android lint, then builds the debug APK so code, resources, and packaging are validated together. Failure reports are retained for diagnosis. |
| [Debug Release](.github/workflows/release.yml) | Pushed tags matching `v*` | Repeats the primary validation and publishes the tagged debug APK as a GitHub prerelease with generated release notes. |
| [CodeQL](.github/workflows/codeql.yml) | Pull requests, pushes to `main`, weekly, and manual dispatches | Scans Java/Kotlin and GitHub Actions for security issues. The Android build is captured with JDK 17 and SDK 35 so analysis covers the compiled app. |
| [Dependency Graph](.github/workflows/dependency-graph.yml) | Pull requests and pushes to `main` | Generates Gradle dependency snapshots. Main-branch snapshots are submitted directly; pull-request snapshots are uploaded without granting untrusted PR code a write token. |
| [Submit Pull Request Dependency Graph](.github/workflows/dependency-graph-submit.yml) | After a successful pull-request dependency-graph run | Downloads one expected artifact in a trusted workflow, validates its structure, workflow identity, PR ref, commit SHA, and metadata, then submits only the validated snapshot fields. This supports fork PRs without executing their code in a privileged job. |
| [Dependency Review](.github/workflows/dependency-review.yml) | Pull requests | Waits for the submitted snapshot, rejects newly introduced dependencies with known vulnerabilities rated moderate or higher, and fails closed if snapshot warnings remain after the retry window. |
| [Dependabot](.github/dependabot.yml) | Weekly | Opens bounded update PRs for Gradle and GitHub Actions dependencies after a seven-day release cooldown, so upgrades have stabilization time and go through the same review and validation gates. Security updates are not delayed by the cooldown. |

Third-party actions are pinned to full commit SHAs to prevent mutable tags from changing executed CI code unexpectedly. Dependabot keeps those pinned references current. The workflows grant read-only permissions by default and add write permissions only to CodeQL result upload, dependency-snapshot submission, or tagged GitHub release publication jobs.

Debug releases are intentional rather than merge-driven. After the release workflow is present on `main`, create and push an annotated tag such as `v1.0.0-alpha.1`; the tag push validates the tagged commit and publishes `websnag-v1.0.0-alpha.1-debug.apk` as a GitHub prerelease. These APKs use runner-generated debug signing keys, so uninstall the previous CI-distributed build before installing a newer one; uninstalling removes that build's local app data. Production signing, Android App Bundles, and Google Play publishing are outside this workflow.

[`CODEOWNERS`](.github/CODEOWNERS) assigns the workflow definitions, Gradle build configuration, version catalog, wrapper, and launchers to the repository owner. To enforce these safeguards, configure the `main` branch rules after the workflows have run once:

1. Require a pull request before merging and require code-owner approval.
2. Require the CI validation, both CodeQL analyses, dependency-graph generation, and dependency-review checks to pass.
3. Require branches to be up to date before merging and block force pushes and deletions.

The pull request that first installs these workflows runs Dependency Review in bootstrap mode because GitHub only triggers a `workflow_run` workflow after that workflow exists on the default branch. Bootstrap mode is limited to the known pre-Actions base commit; a missing trusted workflow on any later base is an error. After this change is merged, every later pull request runs the full dependency review and fails if its Gradle snapshot is missing or incomplete.

Run the same primary validation locally with:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --continue --no-daemon
```

### Release signing

Release tasks require `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. No password or alias fallback is built into the project; debug builds remain independently signed by the Android debug configuration.

---

## Roadmap

[`docs/ROADMAP.md`](docs/ROADMAP.md) is the source of truth for post-alpha work. It
documents Android version automation, signed upgradeable releases, migration and
end-to-end test matrices, accessibility/localization, local diagnostics, distribution
readiness, authenticated-NFC research, security/privacy invariants, dependencies, and
PR-sized task cards for future contributors and agents.

The recommended next task is **REL-001: Automate Android version metadata**.

---

## License

WebSnag is licensed under the [MIT License](LICENSE).
