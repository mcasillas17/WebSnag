# Roadmap Task Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace broad active roadmap cards with canonical PR-sized leaf tasks, exact dependencies, and implementation details validated against `origin/main` at `9699124`.

**Architecture:** Keep `docs/ROADMAP.md` as the sole roadmap authority. Preserve the current baseline, invariants, versioning explanation, completed foundation, non-goals, and definition of done; replace only active status, sequencing, dependency, and task-card content with the leaf roster in the approved design.

**Tech Stack:** GitHub-flavored Markdown, Mermaid, Kotlin/Android repository evidence, ripgrep, Git.

---

### Task 1: Replace roadmap status and dependency structure

**Files:**
- Modify: `docs/ROADMAP.md:152-230`
- Reference: `docs/superpowers/specs/2026-09-03-roadmap-task-expansion-design.md:63-138`

- [x] **Step 1: Record the stale broad-task structure**

Run:

```bash
grep -En '^\| (REL-002|MIG-001|TEST-002|UX-001|UX-002|PERF-001|DIST-001) \|' docs/ROADMAP.md
```

Expected: seven active parent rows are present.

- [x] **Step 2: Add completed-foundation and alias tables**

Keep `DEP-001`, `REL-001`, `DOC-001`, and `DIAG-001` as completed provenance. Add this
alias mapping:

```markdown
| Alias | Canonical leaves |
| --- | --- |
| `REL-002` | `REL-002A`, `REL-002B`, `REL-002C` |
| `MIG-001` | `MIG-001A`, `MIG-001B` |
| `TEST-002` | `TEST-002A`, `TEST-002B`, `TEST-002C` |
| `UX-001` | `UX-001A`, `UX-001B` |
| `UX-002` | `UX-002A`, `UX-002B` |
| `PERF-001` | `PERF-001A`, `PERF-001B` |
| `DIST-001` | `DIST-001A`, `DIST-001B`, `DIST-001C` |
```

State explicitly that aliases cannot own issues or pull requests.

- [x] **Step 3: Replace the active status table**

Add exactly these canonical leaves and statuses:

```markdown
| Task | Status | Start condition |
| --- | --- | --- |
| REL-002A | Ready | DEP-001 and REL-001 complete |
| REL-002B | Blocked | REL-002A merged |
| REL-002C | Blocked | REL-002A merged |
| MIG-001A | Ready | May start now |
| MIG-001B | Blocked | REL-002B, REL-002C, and MIG-001A merged |
| CI-001 | Ready | May start now |
| ENF-001 | Ready | May start now |
| SEC-001 | Ready | May start now |
| DATA-001 | Blocked | MIG-001A merged |
| TEST-001 | Blocked | CI-001 merged |
| TEST-002A | Ready | May start now |
| TEST-002B | Blocked | TEST-002A merged |
| TEST-002C | Blocked | CI-001, SEC-001, and TEST-002A merged |
| TEST-003 | Blocked | CI-001 and ENF-001 merged |
| UX-001A | Ready | May start now |
| UX-001B | Blocked | UX-001A merged and fluent human review available |
| UX-002A | Blocked | UX-001A merged |
| UX-002B | Blocked | UX-001A and UX-002A merged |
| PERF-001A | Ready | May start now |
| PERF-001B | Blocked | PERF-001A baseline accepted |
| DEC-001 | Ready | May start now |
| DEC-002 | Ready | May start now |
| DEC-003 | Blocked | MIG-001A merged |
| DIST-001A | Blocked | DEC-001, DEC-002, and REL-002B merged |
| DIST-001B | Blocked | REL-002B and REL-002C merged |
| DIST-001C | Blocked | All dependencies in its card merged |
| SAFE-001 | Ready | May start now |
| NFC-001 | Blocked | SAFE-001 merged |
```

- [x] **Step 4: Replace execution lanes and Mermaid graph**

Describe:

1. immediate release work: `REL-002A` and `MIG-001A`;
2. immediate reliability work: `CI-001`, `ENF-001`, `SEC-001`, `TEST-002A`;
3. immediate quality/research work: `UX-001A`, `PERF-001A`, `DEC-001`, `DEC-002`,
   `SAFE-001`;
4. dependency-unlocked leaves;
5. `DIST-001C` as final integration.

The graph must contain every blocked edge from the status table and no alias node.

- [x] **Step 5: Verify aliases replaced active parent rows**

Run:

```bash
if grep -En '^\| (REL-002|MIG-001|TEST-002|UX-001|UX-002|PERF-001|DIST-001) \| (Ready|Blocked)' docs/ROADMAP.md; then
  exit 1
fi
grep -En '^\| (REL-002A|MIG-001A|CI-001|UX-001A|DIST-001C) \|' docs/ROADMAP.md
```

Expected: the first search returns no rows; the second returns five rows.

### Task 2: Replace broad implementation cards with leaf cards

**Files:**
- Modify: `docs/ROADMAP.md:234-1048`
- Reference: `docs/superpowers/specs/2026-09-03-roadmap-task-expansion-design.md`

- [x] **Step 1: Preserve completed foundation cards**

Keep the evidence and completion summaries for `DEP-001`, `REL-001`, `DOC-001`, and
`DIAG-001`, but mark their detailed cards historical so they cannot be claimed.

- [x] **Step 2: Write release and migration leaf cards**

Create cards for:

```text
REL-002A Release build and durable signing
REL-002B Verified artifact publication
REL-002C R8 and resource shrinking
MIG-001A Synthetic migration fixtures
MIG-001B Signed package-upgrade matrix
```

Use these boundaries:

| ID | Primary files | Required behavior |
| --- | --- | --- |
| REL-002A | `app/build.gradle.kts`, `.github/workflows/release.yml`, `docs/releasing.md` | protected signing identity, release APK/AAB, PR workflows remain secret-free |
| REL-002B | `.github/workflows/release.yml`, `scripts/verify-release-artifacts.sh`, `docs/releasing.md` | signature/certificate, version, debuggable, checksum, manifest, and AAB verification before publication |
| REL-002C | `app/build.gradle.kts`, `app/proguard-rules.pro`, release smoke tests | minification/resource shrinking with narrow evidence-backed rules |
| MIG-001A | `app/src/test/`, `app/src/androidTest/assets/migrations/`, `LocalDataStore.kt` only after a failing fixture | synthetic legacy, malformed, active-lock, schedule, history, and backup fixtures |
| MIG-001B | `scripts/test-apk-upgrade.sh`, device tests, CI only if bounded | two signed versions upgraded with `adb install -r`, state and safety invariants preserved |

- [x] **Step 3: Write reliability and validation leaf cards**

Create cards for:

```text
CI-001 Bounded device-test harness
ENF-001 Emergency recovery correctness
SEC-001 Schedule receiver action validation
DATA-001 Persisted-state corruption handling
TEST-001 Accessibility enforcement E2E
TEST-002A Schedule clock and time-zone seam
TEST-002B Schedule boundary and overlap tests
TEST-002C Schedule system-event device tests
TEST-003 NFC and recovery device tests
```

Required implementation evidence:

- `CI-001` runs a non-zero instrumented test count and separates a PR smoke lane from any
  slower full matrix.
- `ENF-001` aligns `requireIntentionPhrase`, removes the UI's hardcoded cooldown path,
  and uses a monotonic duration source without weakening persisted recovery.
- `SEC-001` allowlists accepted receiver actions and ignores unexpected explicit intents;
  it does not prescribe changing `android:exported`.
- `DATA-001` starts from a failing MIG-001A corruption fixture, introduces typed
  decode/corruption outcomes, prevents automatic overwrite of malformed state, and gives
  the user a bounded recovery path; it does not add a speculative schema engine.
- `TEST-001` uses real window-state events and validates launcher, WebSnag, system, and
  current-dialer exemptions.
- `TEST-002A` injects wall-clock and time-zone inputs into schedule calculations without
  changing schedule behavior.
- `TEST-002B` covers same-day, overnight, DST, overlap, dismissal, and delayed delivery.
- `TEST-002C` covers alarms, reboot, time/time-zone changes, package replacement, and
  process death on an emulator.
- `TEST-003` covers specific/any/unknown/deleted tags, Keystore loss, recovery recreation,
  and typed authorization outcomes without clone-resistance claims.

- [x] **Step 4: Write quality, decision, distribution, and research cards**

Create cards for:

```text
UX-001A English resources, plurals, and formatting
UX-001B Spanish translation
UX-002A Interaction accessibility and semantics
UX-002B Large content, RTL, and reduced motion
PERF-001A Performance and battery baseline
PERF-001B Evidence-based regression budgets
DEC-001 Wi-Fi SSID and location-permission posture
DEC-002 Notification permission and feature posture
DEC-003 Dormant trigger and duration model
DIST-001A Distribution policy source of truth
DIST-001B Reproducible build and F-Droid feasibility
DIST-001C Listing and internal-track readiness
SAFE-001 Stronger-enforcement and coercion threat model
NFC-001 Authenticated-tag research
```

Decision cards must list options and end in one recorded decision. Distribution cards
must not claim current external store policy without a primary-source check in that task.
`PERF-001A` must measure before `PERF-001B` sets thresholds. Store publication and any
production privilege remain outside these cards.

- [x] **Step 5: Verify every active leaf has one detailed card**

Run:

```bash
for id in \
  REL-002A REL-002B REL-002C MIG-001A MIG-001B \
  CI-001 ENF-001 SEC-001 DATA-001 TEST-001 TEST-002A TEST-002B TEST-002C TEST-003 \
  UX-001A UX-001B UX-002A UX-002B PERF-001A PERF-001B \
  DEC-001 DEC-002 DEC-003 DIST-001A DIST-001B DIST-001C SAFE-001 NFC-001
do
  test "$(grep -Ec "^### ${id} " docs/ROADMAP.md)" = "1" || exit 1
done
```

Expected: exit 0.

### Task 3: Preserve roadmap guardrails and remove stale guidance

**Files:**
- Modify: `docs/ROADMAP.md`

- [x] **Step 1: Keep non-goals and shared protocol**

Retain local-first operation, no hidden inspection, mandatory recovery, centralized
authorization, honest NFC assurance, no zero-bypass claim, double validation of untrusted
data, secret exclusion, evidence before claims, and separately approved publication.

- [x] **Step 2: Update ownership language**

State that only canonical leaf IDs can be claimed. Completed IDs and parent aliases are
not active work. An open issue or pull request with the leaf ID owns the task.

- [x] **Step 3: Update definition of done**

Require:

- acceptance criteria and red-green evidence;
- targeted and repository-wide validation appropriate to the changed surface;
- non-zero device-test count for Android framework behavior;
- final correctness and security/privacy review;
- documentation that distinguishes current behavior from planned behavior;
- no secrets or personal data;
- CI, CodeQL, and dependency review where applicable;
- one canonical leaf ID per pull request.

- [x] **Step 4: Remove stale broad-task instructions**

Run:

```bash
grep -En 'DIAG-001 remains ready|Suggested task ordering|recommended next release task|one task ID whose dependencies' docs/ROADMAP.md
```

Expected: no stale status/ordering phrases; update the protocol wording to "canonical
leaf ID."

### Task 4: Validate and commit the expanded roadmap

**Files:**
- Review: `docs/ROADMAP.md`
- Review: `docs/superpowers/specs/2026-09-03-roadmap-task-expansion-design.md`
- Review: `docs/superpowers/plans/2026-09-03-roadmap-task-expansion.md`

- [x] **Step 1: Validate headings and status rows**

Run the card-count loop from Task 2 and:

```bash
test "$(grep -Ec '^\| (REL-002A|REL-002B|REL-002C|MIG-001A|MIG-001B|CI-001|ENF-001|SEC-001|DATA-001|TEST-001|TEST-002A|TEST-002B|TEST-002C|TEST-003|UX-001A|UX-001B|UX-002A|UX-002B|PERF-001A|PERF-001B|DEC-001|DEC-002|DEC-003|DIST-001A|DIST-001B|DIST-001C|SAFE-001|NFC-001) \|' docs/ROADMAP.md)" = "28"
```

Expected: exit 0.

- [x] **Step 2: Validate dependencies and prose**

Run:

```bash
grep -En 'T[B]D|T[O]DO|implement la[t]er|fill in detai[l]s|appropriate error handlin[g]|similar to Tas[k]' \
  docs/ROADMAP.md docs/superpowers/specs/2026-09-03-roadmap-task-expansion-design.md
git diff --check
```

Expected: the placeholder search has no matches and `git diff --check` exits 0.

- [x] **Step 3: Review the final diff**

Run:

```bash
git --no-pager diff --stat 9699124..HEAD
git --no-pager diff -- docs/ROADMAP.md
```

Expected: only the design, plan, and canonical roadmap documentation changed; no
application, build, or workflow behavior changed.

- [x] **Step 4: Commit**

```bash
git add docs/ROADMAP.md docs/superpowers/plans/2026-09-03-roadmap-task-expansion.md
git commit -m "docs: expand roadmap into implementation tasks"
```
