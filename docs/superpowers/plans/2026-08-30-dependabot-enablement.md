# Dependabot Enablement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Confirm and enforce Dependabot settings, ecosystem coverage, dependency submission, and severity reporting for WebSnag.

**Architecture:** Keep the existing repository configuration because it already covers Gradle and GitHub Actions and submits the resolved Gradle graph. Apply the two GitHub repository settings idempotently, then verify settings, configuration, workflow health, graph population, and open-alert counts through read-back checks.

**Tech Stack:** GitHub REST API via GitHub CLI, GitHub Actions, Dependabot configuration, Gradle dependency submission, Ruby YAML parser

---

### Task 1: Enforce Repository Dependabot Settings

**Files:**
- No repository files change.

- [ ] **Step 1: Enable Dependabot alerts**

Run:

```bash
gh api --method PUT repos/mcasillas17/WebSnag/vulnerability-alerts
```

Expected: command exits successfully with no response body.

- [ ] **Step 2: Enable Dependabot security updates**

Run:

```bash
gh api --method PUT repos/mcasillas17/WebSnag/automated-security-fixes
```

Expected: command exits successfully with no response body.

- [ ] **Step 3: Read back both settings**

Run:

```bash
gh api -i repos/mcasillas17/WebSnag/vulnerability-alerts 2>&1 |
  sed -n '1p'
gh api repos/mcasillas17/WebSnag/automated-security-fixes \
  --jq '{enabled, paused}'
```

Expected:

```text
HTTP/2.0 204 No Content
{"enabled":true,"paused":false}
```

### Task 2: Validate Package-Ecosystem Coverage

**Files:**
- Verify: `.github/dependabot.yml`
- Verify: `build.gradle.kts`
- Verify: `settings.gradle.kts`
- Verify: `app/build.gradle.kts`
- Verify: `gradle/libs.versions.toml`
- Verify: `.github/workflows/*.yml`

- [ ] **Step 1: List recognized dependency surfaces**

Run:

```bash
find . -type f \
  \( -name 'build.gradle' -o -name 'build.gradle.kts' \
     -o -name 'settings.gradle' -o -name 'settings.gradle.kts' \
     -o -name 'libs.versions.toml' -o -path './.github/workflows/*.yml' \
     -o -path './.github/workflows/*.yaml' \) \
  -not -path './.git/*' |
  sort
```

Expected: Gradle build files, the Gradle version catalog, and GitHub Actions workflow
files are listed; no additional package-manager manifest is present.

- [ ] **Step 2: Parse and assert Dependabot coverage**

Run:

```bash
ruby -ryaml -e '
config = YAML.safe_load_file(".github/dependabot.yml")
updates = config.fetch("updates")
actual = updates.map { |entry|
  [entry.fetch("package-ecosystem"), entry.fetch("directory"), entry.dig("schedule", "interval")]
}.sort
expected = [["github-actions", "/", "weekly"], ["gradle", "/", "weekly"]]
abort("unexpected Dependabot coverage: #{actual.inspect}") unless actual == expected
puts actual.map { |row| row.join(":") }
'
```

Expected:

```text
github-actions:/:weekly
gradle:/:weekly
```

### Task 3: Verify Dependency Submission

**Files:**
- Verify: `.github/workflows/dependency-graph.yml`
- Verify: `.github/workflows/dependency-graph-submit.yml`

- [ ] **Step 1: Wait for the latest main-branch dependency-graph run**

Run:

```bash
run_id="$(
  gh run list --repo mcasillas17/WebSnag \
    --workflow dependency-graph.yml \
    --event push \
    --branch main \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId'
)"
test -n "$run_id"
gh run watch "$run_id" --repo mcasillas17/WebSnag --exit-status
```

Expected: the workflow finishes with a successful conclusion.

- [ ] **Step 2: Confirm the submitted dependency graph is populated**

Run:

```bash
gh api repos/mcasillas17/WebSnag/dependency-graph/sbom \
  --jq '{
    packages: (.sbom.packages | length),
    relationships: (.sbom.relationships | length)
  } | select(.packages > 1 and .relationships > 0)'
```

Expected: one JSON object with a package count greater than one and a relationship
count greater than zero.

### Task 4: Count Open Alerts by Severity

**Files:**
- No repository files change.

- [ ] **Step 1: Fetch every page of open alerts and print all severities**

Run:

```bash
gh api --paginate \
  'repos/mcasillas17/WebSnag/dependabot/alerts?state=open&per_page=100' |
jq -s '
  add
  | map(.security_advisory.severity)
  | {
      critical: map(select(. == "critical")) | length,
      high: map(select(. == "high")) | length,
      medium: map(select(. == "medium")) | length,
      low: map(select(. == "low")) | length
    }
'
```

Expected: one JSON object containing integer counts for `critical`, `high`,
`medium`, and `low`.

### Task 5: Publish Repository Documentation

**Files:**
- Create: `docs/superpowers/specs/2026-08-30-dependabot-enablement-design.md`
- Create: `docs/superpowers/plans/2026-08-30-dependabot-enablement.md`

- [ ] **Step 1: Check the documentation changes**

Run:

```bash
git diff --check main...HEAD
git status --short
```

Expected: no whitespace errors and only the planned Dependabot documentation is
reported.

- [ ] **Step 2: Commit the implementation plan**

Run:

```bash
git add docs/superpowers/plans/2026-08-30-dependabot-enablement.md
git commit -m "docs: plan Dependabot enablement" \
  -m "Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>"
```

Expected: one commit containing the implementation plan.

- [ ] **Step 3: Push the branch**

Run:

```bash
git push --set-upstream origin mcasillas17-enable-dependabot
```

Expected: the branch is pushed successfully.

- [ ] **Step 4: Open the pull request without merging it**

Run:

```bash
gh pr create \
  --repo mcasillas17/WebSnag \
  --base main \
  --head mcasillas17-enable-dependabot \
  --title "docs: document Dependabot enablement" \
  --body "## Summary

- document the verified Dependabot configuration and repository settings
- record the repeatable checks for ecosystem coverage, dependency submission, and alert severity counts

## Validation

- Dependabot alerts and security updates enabled
- Gradle and GitHub Actions update coverage confirmed
- dependency graph workflow and SBOM confirmed"
```

Expected: GitHub returns the URL of a new open pull request. Do not merge it.
