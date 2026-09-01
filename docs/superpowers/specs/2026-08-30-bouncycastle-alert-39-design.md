# Bouncy Castle Alert 39 Remediation Design

> **Historical design:** PR #21 implemented this initial root-classpath remediation.
> `docs/security/dependency-triage.md` records the current AGP 9.3.2 multi-configuration
> policy and supersedes the versions and scope below.

## Goal

Close Dependabot alert #39 (`GHSA-574f-3g2m-x479`) without adding Bouncy Castle
to the Android application's runtime classpath or weakening the build and release
controls.

## Scope

- Trace `org.bouncycastle:bcprov-jdk18on` to the top-level Gradle or Android
  tooling component that introduces it.
- Prefer upgrading that top-level component to a compatible release that selects
  a patched Bouncy Castle version.
- If no supported top-level upgrade is available, add the narrowest build
  classpath constraint that keeps the Bouncy Castle family version-aligned.
- Regenerate the repository dependency snapshot after remediation.
- Keep the implementation and documentation specific to this dependency family.

A compatible family-level remediation may also close alerts #21 and #22. That is
an acceptable side effect because forcing mismatched Bouncy Castle module
versions would create avoidable compatibility risk.

## Non-goals

- Do not add Bouncy Castle as an application dependency.
- Do not dismiss alert #39 based only on expected code-path reachability.
- Do not remediate unrelated Netty, protobuf, JDOM, jose4j, or Guava alerts in
  this change.
- Do not broadly upgrade application libraries.

## Current Dependency Path

GitHub's dependency graph reports `bcprov-jdk18on` as a transitive Maven
dependency attributed to `settings.gradle.kts`. The submitted SBOM connects
version 1.79 through Android Gradle Plugin tooling, including `sdk-common`,
`builder`, `apkzlib`, `bcpkix-jdk18on`, and `bcutil-jdk18on`.

The repository's dependency-remediation roadmap records that Bouncy Castle was
not present in `:app:debugRuntimeClasspath`. The implementation must re-check
debug and release runtime classpaths before describing the exposure as
build-only.

## Remediation Selection

1. Inspect available Android Gradle Plugin releases and their resolved build
   classpaths.
2. Select the smallest supported top-level upgrade that removes the vulnerable
   Bouncy Castle version while remaining compatible with the repository's
   Gradle, Kotlin, Java, and Android SDK versions.
3. If no such upgrade is viable, constrain the Bouncy Castle modules only on
   build/plugin configurations and align `bcprov-jdk18on`, `bcpkix-jdk18on`, and
   `bcutil-jdk18on` to the same compatible patched version.
4. Reject any approach that causes Bouncy Castle to appear in an application
   compile or runtime configuration.

## Failure Handling

- Dependency resolution failure is a hard failure; do not fall back to the
  vulnerable version.
- Build, lint, test, or dependency-snapshot failures block completion.
- If only a pre-release top-level toolchain can resolve the alert, prefer a
  scoped stable-family constraint and document the compatibility evidence.
- If neither route is supportable, leave the alert open and document the exact
  blocker rather than dismissing it.

## Validation

- Run the Gradle build-environment report and inspect every Bouncy Castle
  module on the buildscript classpath.
- Run focused `dependencyInsight` and dependency reports for debug and release
  compile/runtime configurations.
- Run the repository's unit tests, lint, debug build, and release build.
- Inspect APK/AAB contents to ensure Bouncy Castle is not packaged.
- Regenerate and inspect the dependency snapshot.
- Confirm through GitHub after the snapshot reaches the default branch that
  alert #39 closes.
