# Release builds and signing

## Readiness and scope

REL-002A supplies a **build-only signing foundation**, not a public signed release.
The approved certificate entry in
[`config/prerelease-signing.properties`](../config/prerelease-signing.properties)
is deliberately empty. Owner-approved identity/custody, a protected release environment,
enforced review rules, and two successful runs with that approved identity remain
acceptance blockers. Disposable-key success does not establish any of those controls.

[`release-build.yml`](../.github/workflows/release-build.yml) is a separate manual
workflow. It builds the exact dispatched **current main commit**, using a tag-shaped
version input. It does not check out a supplied tag, accept a historical commit, create
tags, upload artifacts, or publish releases. This avoids running arbitrary tag-selected
workflow code with the durable key.

[`release.yml`](../.github/workflows/release.yml) is unchanged: pushed `v*` tags still
produce a debug-signed GitHub prerelease. **Uninstall previous CI-distributed debug
builds before installing newer ones; uninstalling removes their local app data.**
Do not remove this warning on the strength of certificate continuity alone.

| Output | Location | Current distribution |
| --- | --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | Existing tagged debug prerelease workflow |
| Release APK | `app/build/outputs/apk/release/app-release.apk` | Local output or temporary hosted-job output only |
| Release AAB | `app/build/outputs/bundle/release/app-release.aab` | Local output or temporary hosted-job output only |

REL-002B owns complete artifact verification/publication, checksums and a release
manifest. REL-002C owns R8/resource shrinking and keep-rule tuning. MIG-001B owns the
in-place upgrade matrix and removal of the uninstall warning. None is completed here.
The application ID remains `websnag.elopenmike.com`; no application permissions, data
flows, telemetry, accounts, or enforcement privileges change.

## Release-build flow

```mermaid
flowchart TD
    PR["PR or fork / push to main"] --> CI["No durable credentials: tests, lint, debug APK"]
    TAG["Existing v* tag push"] --> DEBUG["Unchanged debug APK prerelease"]
    MAIN["Manual main dispatch + version input"] --> PREFLIGHT{"Current main, clean tree, digest, tag, tests, dependency floors"}
    PREFLIGHT -->|reject| STOP["Stop: no signed publication"]
    PREFLIGHT -->|pass| APPROVE["prerelease-signing: required reviewer and main-only policy"]
    APPROVE --> RECHECK{"Recheck exact current main before materialization"}
    RECHECK -->|reject| STOP
    RECHECK -->|pass| TEMP["0700 temporary workspace, 0600 key, private Gradle caches"]
    TEMP --> INPUTS{"Tag, credentials, certificate and cache gates"}
    INPUTS -->|pass| BUILD["Fresh assembleRelease + bundleRelease + lintRelease"]
    INPUTS -->|fail| CLEAN["Remove key and private caches"]
    BUILD -->|failure or interruption| CLEAN
    BUILD -->|success| UNLINK["Remove key before public verification"]
    UNLINK --> VERIFY{"APK/AAB signatures, identity, non-debuggable, no INTERNET"}
    VERIFY -->|pass| PROOF["Public commit / tag / versions / certificate log"]
    VERIFY -->|fail| CLEAN
    PROOF --> CLEAN
    CLEAN --> END["Job ends; no artifact upload"]
```

## Maintainer setup: approval before provisioning

**Owner: `@mcasillas17`. Do not generate, replace, rotate, upload, or delete a durable
identity without explicit owner approval.** First inventory existing approved identities
and environment/secret **names and protection metadata**, not secret values. Reuse an
approved identity rather than creating another because configuration is missing.

### 1. Establish repository and environment controls

Before uploading signing material:

1. Protect `main`: require pull requests, code-owner review, passing CI, all three CodeQL
   analyses (Java/Kotlin, Actions, Python), dependency-graph and dependency-review checks.
   Require up-to-date branches; block force pushes and deletions.
2. Ensure a different authorized person can approve changes and deployments. A sole
   code owner cannot approve their own PR; add an approved trusted owner/team if needed
   rather than bypassing review.
3. In repository **Settings > Environments**, explicitly create `prerelease-signing`.
   Require trusted reviewers, prevent self-review, and disallow administrator bypass.
4. Select **Selected branches and tags**, with one **branch** rule named `main` and
   no tag rules. Do not use unrestricted access, `v*`, pull-request refs, or rely on
   "protected branches only" when protection rules are absent.
5. Keep the four signing values below at **environment scope only**. Do not duplicate
   them as repository/organization values or forward them into PR workflows.

YAML and CODEOWNERS do not configure these server-side protections. The
`github.ref_protected` check is an additional gate, not proof that reviews or required
checks were configured. Do not rely on GitHub implicitly creating an unprotected
environment when a workflow references a new name.

The environment reviewer must confirm passing checks for the **exact dispatched main
SHA** before approving it. The workflow re-runs release-control tests and dependency
floors, but does not query the status of every main CI check.

### 2. Approve and safeguard one durable identity

Use a private JKS or PKCS12 keystore containing the approved private key and X.509
certificate, kept outside version-controlled directories. Prefer a modern RSA key
(at least 3072 bits for a newly provisioned identity) and at least **25 years initial
certificate validity**, following the Android signing guidance. Do not use an Android
debug key. The build checks validity now, not a minimum remaining lifetime; initial
validity and expiry planning are custody controls, not an automated floor.

For a new identity, use an owner-approved offline provisioning procedure or Android
Studio's key-creation flow. Record the approval, custodian, certificate, algorithm,
creation/expiry dates, and recovery procedure before enabling the workflow. Existing
approved keys must not be regenerated to match an example.

Choose a **non-sensitive alias**, such as `websnag-prerelease`. `KEY_ALIAS` is stored as
an environment secret for configuration/log hygiene, not confidentiality. AAB/JAR
signature filenames expose an alias-derived form (uppercased/truncated/sanitized).
The current v2/v3 APK path does not use those JAR alias filenames. Neither aliases nor
certificate subject fields should contain passwords, personal identifiers or recovery
information. The certificate itself and its digest are public.

Keep the original in an access-controlled encrypted vault/offline store. Maintain at
least two independently stored encrypted backups, with recovery credentials separate
from the backup media. Base64 is not encryption; GitHub Actions must not be the sole
backup. Test restoration on an authorized isolated machine and confirm the restored
certificate digest and private-key usability before depending on a backup. Record the
restore date and authorized custodians without recording passwords in this repository.
Review expiry before each release approval and after any custody/backup change.

### 3. Record the public certificate digest

On the authorized custody machine, export the **certificate only** from the approved
keystore. With `KEYSTORE_PATH`, `KEY_ALIAS`, and `KEYSTORE_PASSWORD` securely supplied
to that shell, and `PUBLIC_CERTIFICATE_PATH` naming an external `.der` file:

```bash
keytool -exportcert \
  -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" \
  -storepass:env KEYSTORE_PASSWORD -file "$PUBLIC_CERTIFICATE_PATH"

python3 -c 'import hashlib,pathlib,sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' \
  "$PUBLIC_CERTIFICATE_PATH"
```

Do not add `-rfc`: this command hashes the exported **DER certificate bytes**, not PEM
text, the keystore file, or the public key alone. The Python output is exactly
64 lowercase hexadecimal characters.

In a reviewed commit, replace the empty value after `certificateSha256=` in
`config/prerelease-signing.properties` with that output. Keep one unindented property,
without quotes, separators or trailing spaces. LF and CRLF line endings are supported.
Do not paste the uppercase, colon-separated display from `keytool -list -v`, and never
record a disposable test fingerprint as the approved identity.

### 4. Provision only the protected environment

| Environment secret | Meaning |
| --- | --- |
| `KEYSTORE_BASE64` | Single-line base64 of the approved keystore, without whitespace |
| `KEYSTORE_PASSWORD` | Exact nonblank store password |
| `KEY_ALIAS` | Exact nonblank, public-safe private-key alias |
| `KEY_PASSWORD` | Exact nonblank private-key password |

For PKCS12, use the same password for store and key unless the approved tooling supports
and has verified a different arrangement. Both variables are still required; there is
no fallback. Password/alias values are never trimmed into different credentials.
The store must fit GitHub's secret-value limit after encoding; the build also rejects
empty stores and stores larger than 1 MiB.

Only after approval, protections and backup are complete, this produces newline-free
base64 directly into the environment secret without printing the value:

```bash
python3 -c 'import base64,pathlib,sys; sys.stdout.write(base64.b64encode(pathlib.Path(sys.argv[1]).read_bytes()).decode("ascii"))' \
  "$KEYSTORE_PATH" |
  gh secret set KEYSTORE_BASE64 --repo mcasillas17/WebSnag --env prerelease-signing
```

Set the other three values through the environment settings or from the approved vault
via standard input to `gh secret set NAME --repo mcasillas17/WebSnag --env prerelease-signing`.
Never use literal passwords in command arguments, command tracing, `printenv`, screenshots,
Gradle properties, repository files or public reports. Listing secret names with
`gh secret list --repo mcasillas17/WebSnag --env prerelease-signing` is sufficient to
check registration; do not retrieve values for discovery.

`KEYSTORE_PATH` and `WEBSNAG_SIGNING_CERT_SHA256` are **not** additional GitHub secrets.
The wrapper generates the temporary path and reads the public digest from the reviewed
configuration file. It never derives a new expected identity from an uploaded keystore.

## Local disposable validation

Use JDK 17, SDK platform 35, build-tools 35.0.0, and installed Android command-line tools.
Set `JAVA_HOME` and `ANDROID_HOME` for your installation. Put `keytool`, `git`,
`apkanalyzer`, and `ps` on PATH; `apkanalyzer` must resolve inside `ANDROID_HOME`.
The Python tooling requires Python 3.9+ with POSIX `waitid`/`WNOWAIT` support (Linux or
a supported macOS Python). For example, if command-line tools are installed as `latest`:

```bash
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew -p buildSrc test --rerun-tasks --no-daemon --no-configuration-cache
python3 -B -m unittest discover -s scripts/release -p 'test_*.py' -v
./gradlew verifyBuildDependencySecurity --no-daemon --no-configuration-cache
./gradlew testDebugUnitTest lintDebug assembleDebug --continue --no-daemon
python3 -B scripts/release/validate_local.py --failure-cases
```

The validator ignores inherited signing inputs, creates a two-day disposable identity
outside the checkout, and uses one identity for `v1.0.0-alpha.5` and `v1.0.0-alpha.6`.
It exercises the production build wrapper, with fresh private Gradle user/project caches,
then deletes the key and caches. It checks failure paths, including default configuration
cache reuse, abbreviated/aggregate tasks, and task-dependency exclusion. It records the
tested commit, both version identities, certificate digest and per-build elapsed time.
It neither creates Git tags nor publishes anything.

Run from a clean, committed worktree when retaining commit-bound evidence. Do not
distribute these disposable outputs: another validator invocation creates a different
key, and the original key is removed. These runs prove implementation mechanics, not
durable custody, environment protection, in-place upgrades or store readiness.

### Direct Gradle commands for test identities

Prefer the validator above, which owns cleanup. For manual disposable-key work, securely
provide `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and the test
certificate's `WEBSNAG_SIGNING_CERT_SHA256`. Keep the keystore and a private temporary
`GRADLE_USER_HOME` outside the checkout and arrange cleanup on both success and failure.
The build command is:

```bash
: "${GRADLE_USER_HOME:?Use a private temporary directory outside the checkout}"
./gradlew assembleRelease bundleRelease lintRelease \
  -PwebsnagReleaseSigning=true -PwebsnagReleaseTag=v1.0.0-alpha.5 \
  --project-cache-dir="$GRADLE_USER_HOME/project-cache" \
  --rerun-tasks --no-daemon --no-configuration-cache --no-build-cache
```

The opt-in, tag, credentials and external project cache are required. Debug logging and
build scans are forbidden while signing. Debug builds need none of these values; omit
the opt-in (or set it to `false`). All release-named task nodes, including release lint,
are gated; aggregates such as `assemble`, `build`, and `bundle` can reach those nodes.
Use explicit debug targets for secret-free work. Abbreviations and excluding
`requireReleaseSigning` do not disable the task-action guards.

After removing the signing inputs from the environment, the public digest and tag
can verify an **existing** AAB:

```bash
unset KEYSTORE_BASE64 KEYSTORE_PATH KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD
./gradlew :app:verifyBundleIdentity -PwebsnagReleaseTag=v1.0.0-alpha.5 \
  --no-daemon --no-configuration-cache --no-build-cache
```

That standalone task is not a provenance/freshness check or a publisher. The wrapper
deletes the two prior release output files, requires newly produced nonempty regular
files, and verifies both artifacts before reporting success.

## Authorized protected executions

After the reviewed code and approved digest are on `main`, the environment controls are
configured, and checks pass on the exact main SHA, an authorized maintainer may dispatch:

```bash
gh workflow run release-build.yml --repo mcasillas17/WebSnag \
  --ref main -f release_tag=v1.0.0-alpha.5
```

This requests a build, not publication. Approve the `prerelease-signing` deployment only
after inspecting its exact commit, version input and checks. Repeat with the next
accepted input, for example `v1.0.0-alpha.6`, to establish signing continuity.

The input uses the existing `WebSnagVersion` mapping:
`vMAJOR.MINOR.PATCH` for stable, or `vMAJOR.MINOR.PATCH-(alpha|beta|rc).N`.
It is a version label, not a Git ref lookup. Untagged development defaults
(`0.0.0-dev`, code 1) cannot be used for signed builds. Publication uniqueness and
comparison with previously distributed versions are not implemented here.

Every execution checks repository/workflow identity, protected `main`, clean tracked and
untracked state, absence of tracked `.jks`/`.keystore`/`.p12`/`.pfx` files (case-insensitive), and
`HEAD == GITHUB_SHA == current origin/main`. A new push to main during approval or
execution can deliberately reject the run; re-dispatch the new main commit rather than
weakening the check. `local.properties` is rejected in the protected checkout; SDK
configuration comes from `ANDROID_HOME`.
Filename guards do not replace custody rules, code review or secret scanning; private
material must not be committed under any name.

The owner must retain a public acceptance record with both run URLs, tested SHAs, version
inputs/names/codes, the approved certificate digest, and successful APK/AAB verification.
The log line is emitted only after APK v2/v3 verification, expected certificates,
package/version equality, non-debuggability and no `INTERNET` permission are checked.
AAB verification checks signed payloads and metadata as well as its manifest.

There is **no Actions artifact upload** in this workflow. Until REL-002B, protected-run
continuity evidence is in the logs, subject to repository retention settings. The signed
files and normal build intermediates are discarded with the hosted VM. Merging the
foundation does not complete REL-002A by itself: record the remaining controls and real
approved-identity evidence before updating the roadmap and enabling its dependents.

## Cleanup, diagnostics and maintenance

The private workspace is `$RUNNER_TEMP/websnag-release`, mode 0700; the materialized
keystore is mode 0600. Both Gradle user cache and project execution history stay under
that workspace. No Gradle cache restore/save or report/artifact upload is used by the
signing job. The key is unlinked immediately after the signing command, before public
verification. `finally` cleanup removes private caches, and an `always()` workflow step
also removes the workspace after failure or cancellation.

Tool process groups are terminated before their reserved leader PID is reaped. Catchable
interruptions and deadlines have distinct errors. No software cleanup can guarantee
execution after power loss or an uncatchable kill; hosted-runner disposal is part of the
control. **Use standard GitHub-hosted ephemeral runners only.** Moving this workflow to
persistent/self-hosted runners requires a reviewed workspace-retention and lifecycle design.

Buffer clearing is best effort: Python decoding creates an intermediate immutable byte
string, passwords/aliases must exist as strings for Gradle, and removing environment
variables does not erase every memory copy or the OS initial-environment snapshot.
Unlinking files is not guaranteed physical overwrite. Do not claim secure memory/disk
erasure; rely on restricted access, short lifetimes, no sharing/upload, and ephemeral hosts.
The application's on-device Android Keystore is separate and is not changed here.

| Failure | Required response |
| --- | --- |
| Approved certificate not configured | Complete owner approval and the canonical DER digest step; do not insert a test digest |
| Missing/blank named runtime or credential input | Correct that environment name/scope; there is no default key/password/alias |
| `KEYSTORE_BASE64` malformed | Encode without wrapping, spaces or newline characters; base64 is not encryption |
| Signing-input validation failure | Check store type/integrity, passwords, alias, validity dates and public digest through the authorized custody procedure |
| Release task/cache gate | Use the explicit opt-in, valid version input, disabled caches and external temporary project cache |
| Rejected main/checkout | Re-dispatch current main; resolve tracked/untracked changes or tracked keystores; never bypass trust checks |
| Signed build failure | Reproduce compiler/lint issues without durable credentials, using debug targets or the disposable validator |
| Artifact identity failure | Do not distribute; inspect public metadata and tool versions, not private signing logs |
| Interrupted / timed out / cleanup unconfirmed | Stop and inspect lifecycle/runner state; do not reuse leftover private state |

Raw credentialed tool output is discarded, not redacted and uploaded. Errors identify
fields or stages without echoing submitted values. Do not enable tracing, debug logs,
scans, or upload whole workspaces to troubleshoot a signing failure.

The command cap is 30 minutes; the protected job cap is 40 minutes. The owner records
hosted duration at the **first authorized protected run** and revisits caps through review
if measured evidence requires it. Local validator timings are not hosted-runner estimates.
Cold downloads are deliberate: the sign job's secret-free preflight and private signing
home do not share a credential-bearing cache.

`verifyBundleIdentity` reuses the selected AGP's bundled `DumpCommand` API. On an AGP
upgrade, revalidate this coupling, dependency floors, both artifacts and the failure
matrix. Build-tools 35.0.0 in the scripts must stay aligned with the workflow package
selection; CI pins command-line tools revision 14742923. Do not add a second bundletool
version or relax security floors merely to mask an incompatibility.

Workflow tests deliberately check a fixed reviewed structure and reserve the `secrets`
token for four exact identity bindings, even in comments/strings. New signing inputs,
job structure, or credential-reference styles require coordinated tests and code-owner
review. This is a regression guard, not an Actions expression parser or a replacement
for server-side environment protection.

## Loss, compromise, rotation and Play boundaries

**Loss:** stop releases. Restore only an authorized encrypted backup and verify its
certificate identity and private-key usability. A certificate/digest or an existing
APK/AAB cannot reconstruct the private key. If no valid backup exists, escalate to the
owner for recovery/distribution policy; never generate a replacement as a success fallback.

**Compromise or accidental commit:** stop approvals and signing, restrict compromised
access, and involve the owner in incident response. Preserve appropriate evidence without
copying private material into reports. Treat a committed private key as exposed even if
removed from the current tree. Do not delete originals, rewrite shared history, rotate
keys, or resume distribution without explicit approval and a reviewed recovery plan.
Deleting a published asset is not recall of downloaded copies.

**Rotation:** this pipeline supports one unrotated prerelease identity. Multisigner or
unexpected signing-tool output fails the current checks; lineage-aware verification and
device-upgrade compatibility are not implemented. v3 is enabled alongside v2, but this
does not create a proof-of-rotation, recover a lost key, or prove updates on Android
26/27 or newer devices. Changing the pinned digest is not a rotation procedure.
Any identity/certificate change needs an approved verifier/distribution plan and the
relevant device upgrade evidence before use.

**Play App Signing:** the current APK signing certificate identifies locally installed
APKs; the current AAB is signed with the same prerelease identity solely for this
build foundation. It is not a Play-managed app-signing configuration.
With Play App Signing, Google signs distributed APKs with the **app-signing key**;
the **upload key** authenticates uploaded bundles and can be separate. Resetting an
upload key does not replace a lost sideload app-signing key. Before Play enrollment,
the owner must decide how existing sideload identity is preserved and approve any
custody transfer. Do not substitute an upload key into the current APK signing inputs.
Separate upload/app identities require explicit configuration and verifier changes;
no store readiness or successful package-upgrade claim is made here.

## Primary references

- [Android app signing and Play App Signing](https://developer.android.com/studio/publish/app-signing)
- [APK Signature Scheme v3 and proof of rotation](https://source.android.com/docs/security/features/apksigning/v3)
- [GitHub environment protection and secret scope](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [Creating GitHub environment secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GitHub-hosted runner lifecycle](https://docs.github.com/en/actions/concepts/runners/github-hosted-runners)
