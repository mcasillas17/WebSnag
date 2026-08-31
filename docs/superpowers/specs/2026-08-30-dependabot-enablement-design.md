# Dependabot Enablement Design

## Goal

Ensure WebSnag has Dependabot alerts, Dependabot security updates, update coverage for
every package ecosystem in the repository, and a complete GitHub dependency graph.

## Current State

- GitHub reports Dependabot alerts as enabled.
- GitHub reports Dependabot security updates as enabled and not paused.
- `.github/dependabot.yml` covers the repository's Gradle and GitHub Actions
  ecosystems with weekly updates.
- The dependency graph SBOM contains 436 packages and 2112 relationships.
- The existing Gradle dependency-submission workflows have completed successfully
  for both pull-request and main-branch snapshots.

## Design

Keep the existing Dependabot and dependency-submission files unchanged because they
already satisfy the requested ecosystem coverage and dependency-graph requirements.
Re-enable the two repository settings through idempotent GitHub API calls, then read
them back to confirm their state.

Query all open Dependabot alerts and group them by GitHub advisory severity. Report
zero explicitly for any severity with no open alerts. Confirm that the latest
main-branch dependency-graph workflow completes successfully and that the resulting
SBOM remains populated.

Do not create a cosmetic repository change solely to force a pull request. Open a
pull request only if implementation discovers a substantive repository-file change
is required.

## Failure Handling

Stop and report any GitHub API authorization or settings error rather than treating
it as success. If the dependency graph is missing, empty, or its workflow fails, fix
the existing submission configuration and validate the replacement before opening a
pull request.

## Validation

- Read back the vulnerability-alerts endpoint.
- Read back the automated-security-fixes endpoint.
- Count all open alerts by critical, high, medium, and low severity.
- Confirm the latest main-branch dependency-graph workflow result.
- Confirm the dependency graph SBOM contains packages and relationships.
- Validate repository configuration syntax if a repository file changes.
