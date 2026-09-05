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
| Kotlin Gradle plugin | 2.4.10 | 2.4.20-RC2 |

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

## Alert #50: Kotlin build-cache metadata deserialization

| Field | Value |
| --- | --- |
| Advisory | [GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp) / CVE-2026-53914 |
| Severity | Medium |
| Package | `org.jetbrains.kotlin:kotlin-gradle-plugin` |
| Detected version | 2.4.10 |
| First patched version | 2.4.20-Beta1 |
| Patched version used | 2.4.20-RC2 |
| Dependency relationship | Build/plugin tooling, including AGP's built-in Kotlin |
| Exposure | Code execution on the build host through unsafe build-cache metadata deserialization |

### Remediation and provenance

The shared Kotlin version in `gradle/libs.versions.toml` selects **2.4.20-RC2** for the
Kotlin Gradle plugin, Compose compiler plugin, and serialization plugin. The root
buildscript explicitly declares the catalog's Kotlin Gradle plugin dependency, following
[AGP's documented mechanism for upgrading its built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin).
The resolved root classpath upgrades AGP's transitive `2.2.10` request to `2.4.20-RC2`.
The affected Gradle plugin is build tooling, not an application dependency.
The application compiler and its Compose/serialization compiler plugins also resolve
`2.4.20-RC2`. The separate `kotlinAbiValidationCompatClasspath` still resolves
`kotlin-compiler-embeddable:2.4.0`; that is not the affected `kotlin-gradle-plugin` module.

This replaces the previous decision to wait for a stable release. At remediation time,
[2.4.20-RC2](https://github.com/JetBrains/kotlin/releases/tag/v2.4.20-RC2) was the latest
published patched release; stable `2.4.10` remained vulnerable. The release candidate
changes the compiler toolchain and therefore requires build and device validation.

`verifyBuildDependencySecurity` now rejects vulnerable Kotlin Gradle plugin versions.
Its focused `buildSrc` policy recognizes the fixed Beta1 boundary, later betas, release
candidates, and stable releases without treating qualifier digits as version components.
Unrecognized/dev version formats fail the check and require explicit policy review.
Unit tests cover the vulnerable range, patched prereleases/stable versions, and malformed
inputs. The check was also run against the old resolved `2.4.10` dependency and failed
before the upgrade, then passed with `2.4.20-RC2`.

### Verification and alert closure

Run the build-logic tests separately from the app suite:

```bash
./gradlew -p buildSrc test --no-daemon --no-configuration-cache
./gradlew verifyBuildDependencySecurity buildEnvironment --no-daemon --no-configuration-cache
./gradlew testDebugUnitTest lintDebug assembleDebug --continue --no-daemon
./gradlew connectedDebugAndroidTest --no-daemon
```

Also validate release APK/AAB assembly and release lint with the
[disposable signing procedure](../releasing.md#local-disposable-validation), including
explicit opt-in, valid `websnagReleaseTag` and private user/project caches. Inspect
debug/release compile and runtime classpaths to
confirm the affected build plugin is absent. The PR dependency-graph workflow generates
a fresh snapshot for review; after merge, the default-branch snapshot must resolve
`kotlin-gradle-plugin` to `2.4.20-RC2` or a newer patched release before alert #50 can be
confirmed closed. Do not dismiss the alert to substitute for that snapshot.

Release AAB identity verification reuses the selected AGP's bundled `DumpCommand` API.
AGP upgrades must exercise that API and the APK/AAB identity checks again; do not add an
independently versioned bundletool merely to bypass a failure. Keep build-tools 35.0.0
in the release scripts aligned with workflow SDK provisioning. The release guide records
the signing/cache constraints; none of these checks replaces dependency-security floors.

### Stable release follow-up

- **Owner:** `@mcasillas17`
- **Review trigger:** publication of stable Kotlin 2.4.20 or a newer stable patched release.
- **Required action at trigger:** replace the shared release-candidate version with the
  stable version, rerun the build and device-test matrix, and inspect the regenerated
  dependency snapshot. Keep the security regression check enabled.

### Reconsideration trigger

Remove individual overrides only after the selected Android Gradle Plugin resolves the
affected family at or above the recorded minimum and dependency reports, builds, generated
snapshots, and packaged-artifact checks continue to pass.
