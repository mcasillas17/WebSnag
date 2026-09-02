# Roadmap Consolidation Design

## Goal

Make `docs/ROADMAP.md` the single source of truth for roadmap status, dependencies, and
execution order while correcting the README drift already tracked by `DOC-001`.

## Chosen approach

Keep the detailed task cards and dependency graph in `docs/ROADMAP.md`. Replace its
duplicated "Suggested task ordering" section with one concise execution-order section
next to the status table, and update stale baseline/status language. Reduce the README
roadmap section to a durable link without repeating the next task or task state.

Two alternatives were rejected:

- Updating only stale status text would preserve duplicate ordering guidance that can
  drift again.
- Moving the task cards into issues would remove useful in-repository context and make
  the roadmap dependent on GitHub availability.

## Documentation changes

### `docs/ROADMAP.md`

- Update the baseline through the current alpha release and completed diagnostics work.
- Keep one authoritative status table and one dependency-aware execution sequence.
- Remove the duplicated ordering section at the end of the file.
- Mark `DOC-001` complete in the post-merge document state.
- Ensure completed tasks are not described as ready or listed among future work.

### `README.md`

- Correct the Kotlin badge to match the version catalog.
- Remove the obsolete roadmap label from scheduled triggers.
- Remove the duplicate emergency-unlock feature bullet.
- Update the representative project tree for shipped backup, attestation, privacy,
  scheduling, identity-protection, and diagnostics boundaries.
- Keep release/install text explicit about current debug-signature upgrade limits.
- Replace duplicated roadmap status/recommendation prose with a link to the canonical
  roadmap.

### Historical implementation plan

Label the completed P2 implementation plan as historical and link its merged
implementation so unchecked checklist boxes cannot be mistaken for active roadmap work.

## Validation

- Search for the known stale README phrases and duplicated roadmap ordering.
- Verify roadmap task statuses and dependencies against the repository and release
  workflow.
- Check Markdown links and inspect the complete documentation diff.
- Run the repository's existing build validation only if a non-documentation artifact is
  changed; this PR is expected to remain documentation-only.

## Pull request boundary

The PR changes documentation only. It does not create roadmap issues, alter application
behavior, configure release secrets, or implement any remaining roadmap task.
