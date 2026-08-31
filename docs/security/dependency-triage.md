# Dependency Alert Triage

## Alert #39: Bouncy Castle GOST CTR keystream reuse

| Field | Value |
| --- | --- |
| Advisory | `GHSA-574f-3g2m-x479` / `CVE-2025-14813` |
| Severity | Critical |
| Package | `org.bouncycastle:bcprov-jdk18on` |
| Detected version | 1.79 |
| Patched version used | 1.84 |
| Dependency relationship | Transitive build/plugin tooling |
| Introducing component | Android Gradle Plugin 9.0.1 |
| App runtime exposure | None in debug or release compile/runtime reports |

### Provenance

Android Gradle Plugin build tooling introduces Bouncy Castle through
`com.android.tools:sdk-common`, `com.android.tools.build:builder`, and
`com.android.tools.build:apkzlib`. The plugin's resolved classpath selects
`bcprov-jdk18on`, `bcpkix-jdk18on`, and `bcutil-jdk18on` 1.79.

Google's stable Android Gradle Plugin 9.3.2 POMs still declare Bouncy Castle
1.79, so a top-level plugin upgrade does not remediate this advisory. The root
buildscript classpath therefore aligns all three modules on 1.84. The override
does not affect application dependency configurations.

### Reconsideration trigger

Remove the override after the selected Android Gradle Plugin version resolves
all three Bouncy Castle modules to 1.84 or newer and the dependency reports,
builds, and packaged-artifact checks continue to pass.
