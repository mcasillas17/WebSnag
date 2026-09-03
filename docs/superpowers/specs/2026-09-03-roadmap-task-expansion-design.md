# Roadmap Task Expansion Design

**Date:** 2026-09-03
**Status:** Approved autonomously after the user delegated unavailable decisions
**Target:** `docs/ROADMAP.md`

## Goal

Turn the remaining WebSnag roadmap into a single, implementation-ready backlog whose
canonical task IDs each fit one focused pull request. Preserve completed work as
provenance, add only evidence-backed gaps, and use decision cards instead of silently
choosing unresolved product policy.

## Research process

Four independent reviews covered the repository and roadmap:

- Grok 4.6
- Gemini 3.7 Flash
- Claude Opus 4.8
- the lead session

After the independent pass, every reviewer received the same evidence matrix and
challenged the proposed gaps, task boundaries, dependencies, and speculative claims.
All reviewers selected the same roadmap organization described below.

The review baseline is `origin/main` at `9699124`, after the Kotlin build-cache
remediation in PR #32. There were no open issues or pull requests when the roundtable
began.

## Considered approaches

### A. Keep the current task cards and add nested slices

This preserves familiar IDs but leaves ambiguous ownership: an issue or pull request
could claim a broad parent while another claims one of its slices. It also conflicts
with the roadmap's one-task-per-pull-request rule.

### B. Replace broad active cards with canonical leaf tasks

This keeps `docs/ROADMAP.md` as the sole authority while giving every deliverable a
unique, ownable, dependency-checkable ID. Completed tasks remain in a compact provenance
section, and former broad IDs become milestone aliases rather than claimable work.

### C. Move implementation cards into separate documents

This scales to a larger backlog but weakens the recently consolidated source of truth and
makes status, dependencies, and implementation details easier to drift apart.

## Decision

Use **Approach B**.

`docs/ROADMAP.md` will contain:

1. purpose, current baseline, product/safety invariants, and priority model;
2. completed foundation and parent-alias tables;
3. canonical active task status and dependency tables;
4. execution lanes and a dependency graph;
5. PR-sized task cards grouped by milestone;
6. explicit non-goals and the shared definition of done.

## Canonical task roster

### Completed foundation

Keep these IDs complete and non-claimable:

- `DEP-001` — build/tooling dependency remediation
- `REL-001` — tag-derived Android version metadata
- `DOC-001` — roadmap and README correction
- `DIAG-001` — privacy-preserving local diagnostics

### Release and upgrade safety

| ID | Status | Depends on |
| --- | --- | --- |
| `REL-002A` Release build and durable signing | Ready | `DEP-001`, `REL-001` |
| `REL-002B` Verified artifact publication | Blocked | `REL-002A` |
| `REL-002C` R8 and resource shrinking | Blocked | `REL-002A` |
| `MIG-001A` Synthetic migration fixtures | Ready | None |
| `MIG-001B` Signed package-upgrade matrix | Blocked | `REL-002B`, `REL-002C`, `MIG-001A` |

`REL-002` and `MIG-001` become aliases for these leaves.

### Core reliability and Android validation

| ID | Status | Depends on |
| --- | --- | --- |
| `CI-001` Bounded device-test harness | Ready | None |
| `ENF-001` Emergency recovery correctness | Ready | None |
| `SEC-001` Schedule receiver action validation | Ready | None |
| `DATA-001` Persisted-state corruption handling | Ready | None |
| `TEST-001` Accessibility enforcement E2E | Blocked | `CI-001` |
| `TEST-002A` Schedule clock and time-zone seam | Ready | None |
| `TEST-002B` Schedule boundary and overlap tests | Blocked | `TEST-002A` |
| `TEST-002C` Schedule system-event device tests | Blocked | `CI-001`, `SEC-001`, `TEST-002A` |
| `TEST-003` NFC and recovery device tests | Blocked | `CI-001`, `ENF-001` |

`TEST-002` becomes an alias for its three leaves.

### Product quality and beta readiness

| ID | Status | Depends on |
| --- | --- | --- |
| `UX-001A` English resources, plurals, and formatting | Ready | None |
| `UX-001B` Spanish translation | Blocked | `UX-001A`, fluent human review |
| `UX-002A` Interaction accessibility and semantics | Blocked | `UX-001A` |
| `UX-002B` Large content, RTL, and reduced motion | Blocked | `UX-001A`, `UX-002A` |
| `PERF-001A` Performance and battery baseline | Ready | None |
| `PERF-001B` Evidence-based regression budgets | Blocked | `PERF-001A` |

`UX-001`, `UX-002`, and `PERF-001` become aliases for these leaves.

### Product decisions

| ID | Status | Depends on |
| --- | --- | --- |
| `DEC-001` Wi-Fi SSID and location-permission posture | Ready | None |
| `DEC-002` Notification permission and feature posture | Ready | None |
| `DEC-003` Dormant trigger and duration model | Ready | `MIG-001A` |

These cards produce a recorded decision. If a decision requires behavior beyond the
card's bounded cleanup, it creates a new implementation task rather than expanding the
decision pull request.

### Distribution and research

| ID | Status | Depends on |
| --- | --- | --- |
| `DIST-001A` Distribution policy source of truth | Blocked | `DEC-001`, `DEC-002`, `REL-002B` |
| `DIST-001B` Reproducible build and F-Droid feasibility | Blocked | `REL-002B`, `REL-002C` |
| `DIST-001C` Listing and internal-track readiness | Blocked | `REL-002B`, `REL-002C`, `MIG-001B`, `TEST-001`, `TEST-002B`, `TEST-002C`, `TEST-003`, `UX-001B`, `UX-002A`, `UX-002B`, `PERF-001B`, `DIST-001A`, `DIST-001B` |
| `SAFE-001` Stronger-enforcement and coercion threat model | Ready | None |
| `NFC-001` Authenticated-tag research | Blocked | `SAFE-001` |

`DIST-001` becomes an alias for its three leaves. Actual publication remains a separate
user-approved action.

## Task-card contract

Every canonical leaf card must include:

1. priority, status, dependencies, and parallel-work guidance;
2. a pull-request boundary with in-scope and out-of-scope paths or components;
3. a problem statement backed by repository paths or symbols;
4. concrete implementation steps;
5. likely files to create or modify;
6. required tests and observable acceptance criteria;
7. security, privacy, and product-invariant guardrails;
8. migration, rollback, and failure-recovery notes;
9. explicit exit criteria for decision and research cards.

The roadmap must not prescribe secret values, invent performance thresholds, or assert
external store policy without verification from a current primary source.

## Evidence-backed additions

The following gaps justify new cards:

- `CI-001`: `.github/workflows/ci.yml` does not run the existing Android instrumented
  tests.
- `ENF-001`: emergency completion uses wall-clock time, the UI hardcodes the five-minute
  path, and `requireIntentionPhrase=false` conflicts with an unlock policy that always
  requires `intentionConfirmed`.
- `SEC-001`: `SystemScheduleReceiver` delegates to a receiver that does not validate the
  incoming action.
- `DATA-001`: multiple persisted JSON decode paths replace malformed state with empty or
  default collections, which can later be overwritten without surfacing recovery.
- `DEC-001`: SSID-specific schedules and location permissions are coupled; dropping the
  permission would change shipped behavior.
- `DEC-002`: `POST_NOTIFICATIONS` is declared and diagnosed without a notification
  implementation.
- `DEC-003`: `Profile.triggers`, `Trigger.TimeSchedule`, `Trigger.Location`,
  `Trigger.WifiSsid`, and `DurationExpiry` are not wired into the shipped editing and
  scheduling flow.

## Rejected findings

- The default dialer is not wholly hardcoded; `WebSnagApp` registers
  `TelecomManager.defaultDialerPackage`. Device tests may reveal refresh gaps, but the
  roadmap will not assert one in advance.
- `SystemScheduleReceiver` will not be marked for removal solely because it is exported.
  The bounded task validates accepted actions and tests unexpected explicit intents.
- Performance tasks will measure a baseline before defining regression thresholds.
- Migration work will not add a speculative schema engine. Fixtures must demonstrate the
  migration or corruption behavior that needs production code.
- Location permissions will not be removed in documentation without an explicit product
  decision about SSID schedules.
- Completed foundation tasks will not be reopened.

## Validation

The documentation change is complete when:

- every active ID is a single pull-request unit;
- every dependency named in the status table exists and matches the graph;
- all former broad IDs are clearly aliases and cannot be claimed as tasks;
- completed work is not presented as future implementation;
- decision cards avoid unverified external-policy claims;
- searches find no stale active-card ordering or old broad-task status rows;
- `git diff --check` passes.
