# Bouncy Castle Alert 39 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close critical Dependabot alert #39 by overriding the vulnerable
Bouncy Castle build-tooling dependency without adding it to the Android app.

**Architecture:** Keep Android Gradle Plugin 9.0.1 because Google's current
stable 9.3.2 artifacts still declare Bouncy Castle 1.79. Add a resolution rule
only to the root buildscript `classpath`, aligning `bcprov`, `bcpkix`, and
`bcutil` on 1.84. Record provenance and prove the modules remain absent from app
runtime configurations and packaged artifacts.

**Tech Stack:** Gradle 9.7 Kotlin DSL, Android Gradle Plugin, Bouncy Castle
JDK 18 modules, Android build/lint/test tasks

---

### Task 1: Add the build-classpath remediation

**Files:**
- Modify: `build.gradle.kts:1`

- [ ] **Step 1: Run the regression assertion and confirm it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew buildEnvironment --console=plain > /tmp/websnag-alert-39-before.txt
if grep -E 'org\.bouncycastle:bcprov-jdk18on:1\.79' \
  /tmp/websnag-alert-39-before.txt | grep -qv -- '->'; then
  echo "FAIL: vulnerable bcprov-jdk18on 1.79 remains on the build classpath"
  exit 1
fi
```

Expected: exit 1 with
`FAIL: vulnerable bcprov-jdk18on 1.79 remains on the build classpath`.

- [ ] **Step 2: Add a root-buildscript-only family override**

Insert this block before the existing `plugins` block in `build.gradle.kts`:

```kotlin
buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.84",
            "org.bouncycastle:bcpkix-jdk18on:1.84",
            "org.bouncycastle:bcutil-jdk18on:1.84",
        )
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

Do not add these modules to `dependencies` in the root project or `:app`.

- [ ] **Step 3: Re-run the regression assertion and confirm it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew buildEnvironment --console=plain > /tmp/websnag-alert-39-after.txt
grep -F 'org.bouncycastle:bcprov-jdk18on:1.79 -> 1.84' \
  /tmp/websnag-alert-39-after.txt
grep -F 'org.bouncycastle:bcpkix-jdk18on:1.79 -> 1.84' \
  /tmp/websnag-alert-39-after.txt
if grep -E 'org\.bouncycastle:(bcprov|bcpkix|bcutil)-jdk18on:1\.79' \
  /tmp/websnag-alert-39-after.txt | grep -qv -- '->'; then
  echo "FAIL: an unoverridden Bouncy Castle 1.79 module remains"
  exit 1
fi
```

Expected: both `1.79 -> 1.84` lines print and the command exits 0.

- [ ] **Step 4: Confirm app runtime configurations remain unaffected**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
for configuration in debugCompileClasspath debugRuntimeClasspath \
  releaseCompileClasspath releaseRuntimeClasspath; do
  output="/tmp/websnag-alert-39-${configuration}.txt"
  ./gradlew :app:dependencies --configuration "$configuration" \
    --console=plain > "$output"
  if grep -Eiq 'org\.bouncycastle|bcprov|bcpkix|bcutil' "$output"; then
    echo "FAIL: Bouncy Castle appeared in :app:${configuration}"
    exit 1
  fi
done
echo "PASS: Bouncy Castle is absent from app compile/runtime configurations"
```

Expected: exit 0 with
`PASS: Bouncy Castle is absent from app compile/runtime configurations`.

- [ ] **Step 5: Commit the resolution rule**

```bash
git add build.gradle.kts
git commit -m "build: patch Bouncy Castle tooling dependency" \
  -m "Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>" \
  -m "Copilot-Session: 0d025857-3747-4dd8-9941-9a6c804c12ad"
```

### Task 2: Record alert-specific provenance

**Files:**
- Create: `docs/security/dependency-triage.md`

- [ ] **Step 1: Create the dependency-triage record**

Create `docs/security/dependency-triage.md` with:

```markdown
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
```

- [ ] **Step 2: Check the record for incomplete language**

```bash
if grep -Ein 'T[B]D|T[O]DO|F[I]XME' \
  docs/security/dependency-triage.md; then
  echo "FAIL: dependency triage contains incomplete content"
  exit 1
fi
echo "PASS: dependency triage is complete"
```

Expected: exit 0 with `PASS: dependency triage is complete`.

- [ ] **Step 3: Commit the triage record**

```bash
git add docs/security/dependency-triage.md
git commit -m "docs: record Bouncy Castle alert triage" \
  -m "Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>" \
  -m "Copilot-Session: 0d025857-3747-4dd8-9941-9a6c804c12ad"
```

### Task 3: Validate the isolated remediation

**Files:**
- Verify: `build.gradle.kts`
- Verify: `docs/security/dependency-triage.md`
- Verify: `app/build/outputs/apk/debug/app-debug.apk`
- Verify: `app/build/outputs/apk/release/app-release.apk`

- [ ] **Step 1: Run unit tests, lint, and the debug build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest lintDebug assembleDebug --continue --no-daemon
```

Expected: `BUILD SUCCESSFUL`; test and lint tasks have no failures.

- [ ] **Step 2: Build a release APK with an ephemeral test key**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
rm -f /tmp/websnag-alert-39-release.jks
"$JAVA_HOME/bin/keytool" -genkeypair \
  -alias alert39 \
  -keyalg RSA \
  -keysize 2048 \
  -validity 1 \
  -dname "CN=WebSnag Alert 39 Test" \
  -keystore /tmp/websnag-alert-39-release.jks \
  -storepass alert39-test \
  -keypass alert39-test \
  -noprompt
KEYSTORE_PATH=/tmp/websnag-alert-39-release.jks \
KEYSTORE_PASSWORD=alert39-test \
KEY_ALIAS=alert39 \
KEY_PASSWORD=alert39-test \
./gradlew assembleRelease --no-daemon
rm -f /tmp/websnag-alert-39-release.jks
```

Expected: `BUILD SUCCESSFUL` and
`app/build/outputs/apk/release/app-release.apk` exists.

- [ ] **Step 3: Inspect both APKs for packaged Bouncy Castle classes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
apkanalyzer="$HOME/Library/Android/sdk/cmdline-tools/latest/bin/apkanalyzer"
for apk in app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/release/app-release.apk; do
  test -f "$apk"
  output="/tmp/$(basename "$apk")-packages.txt"
  "$apkanalyzer" dex packages "$apk" > "$output"
  if grep -Eiq 'org\.bouncycastle|bcprov|bcpkix|bcutil' "$output"; then
    echo "FAIL: Bouncy Castle classes found in ${apk}"
    exit 1
  fi
done
echo "PASS: Bouncy Castle classes are absent from debug and release APKs"
```

Expected: exit 0 with
`PASS: Bouncy Castle classes are absent from debug and release APKs`.

- [ ] **Step 4: Re-run the focused build dependency report**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew buildEnvironment --console=plain > /tmp/websnag-alert-39-final.txt
grep -F 'org.bouncycastle:bcprov-jdk18on:1.79 -> 1.84' \
  /tmp/websnag-alert-39-final.txt
if grep -E 'org\.bouncycastle:(bcprov|bcpkix|bcutil)-jdk18on:1\.79' \
  /tmp/websnag-alert-39-final.txt | grep -qv -- '->'; then
  exit 1
fi
```

Expected: the override is shown and the command exits 0.

- [ ] **Step 5: Check the final change set**

```bash
git diff --check main...HEAD
git status --short
git --no-pager diff --stat main...HEAD
```

Expected: no whitespace errors; only the design, plan, build policy, and
dependency-triage files are changed.

- [ ] **Step 6: Confirm default-branch closure after merge**

After the dependency-graph workflow submits a new default-branch snapshot, run:

```bash
gh api repos/mcasillas17/WebSnag/dependabot/alerts/39 \
  --jq '{number,state,fixed_at,dismissed_at}'
```

Expected after merge: `state` is `fixed`, `fixed_at` is non-null, and
`dismissed_at` is null.
