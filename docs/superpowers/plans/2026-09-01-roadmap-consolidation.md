# Roadmap Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `docs/ROADMAP.md` the sole authority for roadmap status, dependencies,
and execution order while completing the coupled `DOC-001` documentation cleanup.

**Architecture:** Preserve detailed task cards and the dependency graph in the canonical
roadmap. Remove repeated task-state guidance from the README and the roadmap footer, and
label completed implementation plans as historical records rather than active backlogs.

**Tech Stack:** Markdown, Git, ripgrep

---

### Task 1: Consolidate roadmap status and ordering

**Files:**
- Modify: `docs/ROADMAP.md:8-218`
- Modify: `docs/ROADMAP.md:1098-1124`

- [ ] **Step 1: Record the stale roadmap assertions**

Run:

```bash
rg -n 'As of `v1\.0\.0-alpha\.2`|DIAG-001.*May start now|Suggested task ordering|DIAG-001 remains ready' docs/ROADMAP.md
```

Expected: matches identify the stale baseline, completed task marked ready, duplicated
ordering section, and stale diagnostics recommendation.

- [ ] **Step 2: Update the baseline and status table**

Change the baseline to describe the current alpha line, include completed Android version
automation and local diagnostics, and make the task table use these post-merge states:

```text
DEP-001 Complete
REL-001 Complete
REL-002 Ready
MIG-001 Blocked
DOC-001 Complete
TEST-001 Ready
TEST-002 Ready
TEST-003 Ready
UX-001 Ready
UX-002 Blocked
DIAG-001 Complete
PERF-001 Ready
DIST-001 Blocked
SAFE-001 Ready
NFC-001 Blocked
```

- [ ] **Step 3: Add one authoritative execution sequence**

Immediately after the status table, describe:

1. `REL-002` as the release critical path.
2. `TEST-001`, `TEST-002`, `TEST-003`, `UX-001`, `PERF-001`, and `SAFE-001` as parallel
   work.
3. `MIG-001`, `UX-002`, and `NFC-001` as dependency-unlocked follow-up work.
4. `DIST-001` as final integration.

Keep the existing Mermaid dependency graph as the machine-scannable dependency view.

- [ ] **Step 4: Remove duplicated footer ordering**

Delete the `## Suggested task ordering` section and its stale recommendation. The status
table, execution sequence, and dependency graph become the only roadmap ordering source.

- [ ] **Step 5: Verify stale roadmap assertions are gone**

Run:

```bash
if rg -n 'DIAG-001.*May start now|Suggested task ordering|DIAG-001 remains ready' docs/ROADMAP.md; then
  exit 1
fi
rg -n 'REL-002.*critical path|DOC-001.*Complete|DIAG-001.*Complete' docs/ROADMAP.md
```

Expected: the first search prints nothing; the second prints the canonical current state.

- [ ] **Step 6: Commit the canonical roadmap**

```bash
git add docs/ROADMAP.md
git commit -m "docs: consolidate roadmap status and ordering"
```

### Task 2: Remove README drift and label historical work

**Files:**
- Modify: `README.md:11-17`
- Modify: `README.md:34-99`
- Modify: `README.md:119-174`
- Modify: `README.md:214-258`
- Modify: `docs/superpowers/plans/2026-08-28-safe-p2-differentiation.md:1-5`

- [ ] **Step 1: Record the known README drift**

Run:

```bash
rg -n 'Kotlin-2\.3\.20|TimeScheduleTrigger \(Roadmap\)|Emergency Unlock Friction|recommended next release task' README.md
```

Expected: the Kotlin mismatch, obsolete schedule label, duplicate emergency feature, and
duplicated roadmap recommendation are present.

- [ ] **Step 2: Correct current README behavior**

Update the Kotlin badge to `2.4.10`, remove `(Roadmap)` from `TimeScheduleTrigger`, and
retain only one emergency-unlock feature bullet. Keep the more complete bullet that
mentions both recovery friction and emergency/dialer exemptions.

- [ ] **Step 3: Refresh the representative project tree**

Add the shipped boundaries omitted by the current tree:

```text
core/activity/
core/backup/
core/data/TagIdentityProtector.kt
core/diagnostics/
core/privacy/
core/schedule/ alarm, receiver, reconciliation, and transition components
ui/privacy/
```

Keep the tree representative rather than listing every source file.

- [ ] **Step 4: Preserve accurate release guidance**

Keep the current debug-release warning: tagged artifacts are runner-debug-signed,
upgrades require uninstalling the prior CI build, and uninstalling removes local data.
Keep production signing/AAB/store publishing explicitly outside the current workflow.

- [ ] **Step 5: Reduce the README roadmap section to a canonical pointer**

Retain the link to `docs/ROADMAP.md` and its scope summary. Remove any duplicated current
task recommendation or task status.

- [ ] **Step 6: Mark the completed P2 plan historical**

Add this note below the plan title:

```markdown
> **Historical plan:** The implementation shipped in
> [PR #19](https://github.com/mcasillas17/WebSnag/pull/19). The unchecked boxes below
> preserve the original execution plan; they are not active roadmap tasks.
```

- [ ] **Step 7: Verify README and history cleanup**

Run:

```bash
test "$(rg -c 'Emergency Unlock Friction' README.md)" -eq 1
if rg -n 'Kotlin-2\.3\.20|TimeScheduleTrigger \(Roadmap\)|recommended next release task' README.md; then
  exit 1
fi
rg -n 'Kotlin-2\.4\.10|Historical plan|docs/ROADMAP\.md' README.md docs/superpowers/plans/2026-08-28-safe-p2-differentiation.md
```

Expected: one emergency feature, no stale phrases, and matches for the corrected badge,
historical label, and canonical roadmap link.

- [ ] **Step 8: Commit the coupled documentation cleanup**

```bash
git add README.md docs/superpowers/plans/2026-08-28-safe-p2-differentiation.md
git commit -m "docs: complete roadmap documentation cleanup"
```

### Task 3: Validate and prepare the pull request

**Files:**
- Review: `docs/ROADMAP.md`
- Review: `README.md`
- Review: `docs/superpowers/plans/2026-08-28-safe-p2-differentiation.md`
- Review: `docs/superpowers/specs/2026-09-01-roadmap-consolidation-design.md`
- Review: `docs/superpowers/plans/2026-09-01-roadmap-consolidation.md`

- [ ] **Step 1: Check Markdown links to repository files**

Run a small local script that extracts relative Markdown links from the changed Markdown
files, ignores URLs and anchors, resolves each path relative to its document, and exits
non-zero for a missing target.

Expected: exit 0 with every relative target present.

- [ ] **Step 2: Check formatting and the complete diff**

Run:

```bash
git diff --check main...HEAD
git status --short
git --no-pager diff --stat main...HEAD
git --no-pager diff main...HEAD -- README.md docs/ROADMAP.md docs/superpowers/
```

Expected: no whitespace errors, only intended Markdown changes, and no application or
workflow files in the diff.

- [ ] **Step 3: Confirm task states and ordering**

Read the status table, execution sequence, dependency graph, and each changed README
claim together. Confirm that completed tasks are not listed as future work and blocked
tasks retain their prerequisites.

- [ ] **Step 4: Push and open the pull request**

```bash
git push -u origin HEAD
gh pr create \
  --base main \
  --title "docs: consolidate the WebSnag roadmap" \
  --body "$(cat <<'EOF'
## Summary

- make `docs/ROADMAP.md` the sole source of task status, dependencies, and execution order
- complete `DOC-001` README drift cleanup and label the merged P2 plan as historical
- remove stale and duplicated task recommendations

## Validation

- checked relative Markdown links in every changed document
- ran `git diff --check`
- confirmed the branch changes documentation only
EOF
)"
```

The PR body must summarize the canonical-source decision, the `DOC-001` cleanup, and the
documentation-only validation.
