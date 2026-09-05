"""Build-only protected signing boundary. No publication or cache/artifact upload."""

import base64
import binascii
import contextlib
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys


class ReleaseError(Exception):
    pass


def check_context(env, head, main):
    expected = {
        "GITHUB_EVENT_NAME": "workflow_dispatch",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_REPOSITORY": "mcasillas17/WebSnag",
        "GITHUB_REF_PROTECTED": "true",
        "GITHUB_WORKFLOW_REF": "mcasillas17/WebSnag/.github/workflows/release-build.yml@refs/heads/main",
    }
    sha = env.get("GITHUB_SHA", "")
    if (any(env.get(key) != value for key, value in expected.items())
            or not re.fullmatch(r"[0-9a-f]{40}", sha) or head != sha or main != sha):
        raise ReleaseError("Rejected release input: require the exact current protected main commit and main workflow.")


def recorded_digest(root):
    text = (root / "config/prerelease-signing.properties").read_text()
    values = [line.removeprefix("certificateSha256=") for line in text.splitlines()
              if line.startswith("certificateSha256=")]
    if len(values) != 1 or not re.fullmatch(r"[0-9a-f]{64}", values[0]):
        raise ReleaseError("Approved signing certificate is not configured; follow docs/releasing.md.")
    return values[0]


@contextlib.contextmanager
def signing_workspace(env):
    encoded = env.get("KEYSTORE_BASE64", "")
    if not encoded or len(encoded) > 1_398_104:
        raise ReleaseError("KEYSTORE_BASE64 must encode a nonempty keystore of at most 1 MiB.")
    try:
        material = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        raise ReleaseError("KEYSTORE_BASE64 is malformed.") from None
    if not material or len(material) > 1_048_576:
        raise ReleaseError("KEYSTORE_BASE64 must encode a nonempty keystore of at most 1 MiB.")
    workspace = Path(env["RUNNER_TEMP"]).resolve() / "websnag-release"
    workspace.mkdir(mode=0o700)  # Refuse an existing directory rather than reuse private state.
    try:
        key = workspace / "signing.keystore"
        with key.open("xb") as stream:
            key.chmod(0o600)
            stream.write(material)
        yield workspace
    finally:
        shutil.rmtree(workspace)


def run(command, env, phase, capture=True):
    result = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        # Raw tool output can contain signing aliases, paths or credential values.
        raise ReleaseError(f"{phase} failed. Raw tool output is suppressed; see docs/releasing.md.")
    return result.stdout.strip() if capture else ""


def gradle(arguments, tag, env, phase, capture=True):
    return run(["./gradlew", "-q", *arguments, f"-PwebsnagReleaseTag={tag}",
                "--no-daemon", "--no-configuration-cache", "--no-build-cache", "--console=plain"],
               env, phase, capture)


def trust(env):
    # This runs in both jobs, before any repository build code or key materialization.
    run(["git", "fetch", "--no-tags", "origin", "refs/heads/main"], env, "Main trust refresh")
    check_context(env, run(["git", "rev-parse", "HEAD"], env, "Checkout identity"),
                  run(["git", "rev-parse", "FETCH_HEAD"], env, "Main identity"))
    run(["git", "diff", "--quiet", "HEAD"], env, "Clean checkout")


def version(tag, env):
    text = gradle([":app:printWebSnagVersion"], tag, env, "Release tag validation")
    values = dict(line.split("=", 1) for line in text.splitlines() if line.startswith(("versionName=", "versionCode=")))
    if set(values) != {"versionName", "versionCode"} or not values["versionCode"].isdigit():
        raise ReleaseError("Gradle returned invalid version metadata.")
    return values


def verify_apk(env, expected, digest):
    apk = "app/build/outputs/apk/release/app-release.apk"
    signer = str(Path(env["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner")
    output = run([signer, "verify", "--verbose", "--print-certs", apk], env, "APK signature verification")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", output, re.MULTILINE)
    if certificates != [digest]:
        raise ReleaseError("APK certificate does not match the recorded signing identity.")
    for field, value in (("version-name", expected["versionName"]), ("version-code", expected["versionCode"]),
                         ("application-id", "websnag.elopenmike.com"), ("debuggable", "false")):
        if run(["apkanalyzer", "manifest", field, apk], env, "APK manifest verification") != value:
            raise ReleaseError("APK package/version/debuggable identity mismatch.")


def build(env, tag, digest):
    with signing_workspace(env) as workspace:
        child = dict(env)
        child.pop("KEYSTORE_BASE64", None)
        child["KEYSTORE_PATH"] = str(workspace / "signing.keystore")
        child["GRADLE_USER_HOME"] = str(workspace / "gradle")
        child["WEBSNAG_SIGNING_CERT_SHA256"] = digest
        gradle(["assembleRelease", "bundleRelease", "lintRelease", "-PwebsnagReleaseSigning=true"],
               tag, child, "Signed APK/AAB build", capture=False)
        for secret in ("KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS", "KEYSTORE_PATH"):
            child.pop(secret, None)
        expected = version(tag, child)
        verify_apk(child, expected, digest)
        gradle([":app:verifyBundleIdentity"], tag, child, "AAB identity verification", capture=False)
        print(f"Verified build-only identity: commit={env['GITHUB_SHA']} tag={tag} "
              f"versionName={expected['versionName']} versionCode={expected['versionCode']} "
              f"certificateSHA256={digest}", flush=True)


def main():
    os.umask(0o077)
    # Let finally blocks remove the key on catchable runner cancellation, too.
    def interrupted(signum, frame):
        raise ReleaseError("Signing interrupted.")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    env = dict(os.environ)
    if len(sys.argv) != 2 or sys.argv[1] not in ("preflight", "build"):
        raise ReleaseError("Expected preflight or build.")
    trust(env)
    digest = recorded_digest(Path.cwd())
    tag = env.get("RELEASE_TAG", "")
    if sys.argv[1] == "preflight":
        version(tag, env)
        print("Trusted release input and recorded certificate accepted.")
    else:
        build(env, tag, digest)


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, KeyError):
        # OS failures can include private paths. No exception traceback crosses this boundary.
        print("Release build rejected or failed; no assets published. See docs/releasing.md.", file=sys.stderr)
        sys.exit(1)
