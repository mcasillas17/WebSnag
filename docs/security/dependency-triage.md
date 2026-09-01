# Dependency Alert Triage

## Current remediation

The dependency submission graph includes build and test tooling as well as application
dependencies. Android Gradle Plugin 9.3.2 resolves vulnerable transitive versions through
both the root plugin classpath and internal `:app` tooling configurations. These
dependencies are not present in the application's debug or release compile/runtime
classpaths.

| Package family | Vulnerable resolved versions | Patched versions selected |
| --- | --- | --- |
| Bouncy Castle | `1.79` | `bcprov-jdk18on` 1.85.2; `bcpkix-jdk18on` and `bcutil-jdk18on` 1.85 |
| Netty | 4.1.93.Final and 4.1.110.Final | 4.1.137.Final |
| jose4j | 0.9.5 | 0.9.6 |
| JDOM | 2.0.6 | 2.0.6.1 |
| Guava | 28.1-android and 32.0.1-jre | 33.4.0-jre |
| Apache Commons Lang | 3.16.0 | 3.18.0 |
| Apache HttpClient | 4.5.6 | 4.5.14 |

The root buildscript pins plugin-classpath dependencies. Subproject resolution rules
upgrade the same families only when an existing configuration requests them; the rules
do not add application dependencies. `verifyBuildDependencySecurity` resolves the root
buildscript and every resolvable project configuration, then fails below the reviewed
minimum versions. CI and dependency-graph workflows run this check after wrapper
validation and before dependency submission.

## Alert #39: Bouncy Castle GOST CTR keystream reuse

| Field | Value |
| --- | --- |
| Advisory | `GHSA-574f-3g2m-x479` / `CVE-2025-14813` |
| Severity | Critical |
| Package | `org.bouncycastle:bcprov-jdk18on` |
| Detected version | 1.79 |
| Patched version used | 1.85.2 |
| Dependency relationship | Transitive build/plugin tooling |
| Introducing component | Android Gradle Plugin 9.3.2 |
| App runtime exposure | None in debug or release compile/runtime reports |

### Provenance

Android Gradle Plugin build tooling introduces Bouncy Castle through
`com.android.tools:sdk-common`, `com.android.tools.build:builder`, and
`com.android.tools.build:apkzlib`. The root plugin classpath honors the existing override,
but `androidLintTool` and
`unified-test-platform-android-test-plugin-result-listener-gradle` independently resolve
`bcprov-jdk18on`, `bcpkix-jdk18on`, and `bcutil-jdk18on` 1.79 unless the subproject
configurations apply the same policy.

The selected versions preserve the latest available compatible family: Bouncy Castle
publishes `bcprov-jdk18on` 1.85.2 while the corresponding `bcpkix-jdk18on` and
`bcutil-jdk18on` artifacts remain at 1.85. The verification floor for
`bcprov-jdk18on` is 1.84 because the advisory has separate affected ranges and the
mainline fix for the newest affected range begins at 1.84.

## Remaining bounded follow-up

Dependabot alert #50 reports `org.jetbrains.kotlin:kotlin-gradle-plugin` 2.4.10 with
2.4.20-Beta1 as its first patched version. WebSnag will not move its compiler and Gradle
plugin toolchain to a beta release solely to close a medium-severity build-time alert.

- **Owner:** `@mcasillas17`
- **Review trigger:** the first stable Kotlin release at or above 2.4.20, or a change in
  the advisory's severity, affected range, or exploitability.
- **Required action at trigger:** upgrade the Kotlin plugin family together, run the full
  build and device-test matrix, regenerate the dependency snapshot, and re-check the
  alert.

### Reconsideration trigger

Remove individual overrides only after the selected Android Gradle Plugin resolves the
affected family at or above the recorded minimum and dependency reports, builds, generated
snapshots, and packaged-artifact checks continue to pass.
