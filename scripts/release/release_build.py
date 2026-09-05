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

SIGNING_SECRETS = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS", "KEYSTORE_PATH")


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
    encoded = env.pop("KEYSTORE_BASE64", "")
    if not encoded or len(encoded) > 1_398_104:
        raise ReleaseError("KEYSTORE_BASE64 must encode a nonempty keystore of at most 1 MiB.")
    try:
        material = bytearray(base64.b64decode(encoded, validate=True))
    except (ValueError, binascii.Error):
        raise ReleaseError("KEYSTORE_BASE64 is malformed.") from None
    if not material or len(material) > 1_048_576:
        raise ReleaseError("KEYSTORE_BASE64 must encode a nonempty keystore of at most 1 MiB.")
    workspace = Path(env["RUNNER_TEMP"]).resolve() / "websnag-release"
    workspace.mkdir(mode=0o700)  # Refuse an existing directory rather than reuse private state.
    try:
        key = workspace / "signing.keystore"
        try:
            with open(key, "xb", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
                stream.write(material)
        finally:
            material[:] = b"\0" * len(material)
            encoded = None
        yield workspace
    finally:
        shutil.rmtree(workspace)


def run(command, env, phase, capture=True, timeout=1800):
    with subprocess.Popen(command, env=env, text=True, start_new_session=True,
                          stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL) as process:
        try:
            output, _ = process.communicate(timeout=timeout)
        except (ReleaseError, KeyboardInterrupt, subprocess.TimeoutExpired):
            # Stop the whole child group before the enclosing workspace removes its key.
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            raise ReleaseError(f"{phase} interrupted or timed out.") from None
    if process.returncode:
        # Raw tool output can contain signing aliases, paths or credential values.
        raise ReleaseError(f"{phase} failed. Raw tool output is suppressed; see docs/releasing.md.")
    return output.strip() if capture else ""


def gradle(arguments, tag, env, phase, capture=True):
    return run(["./gradlew", "-q", *arguments, f"-PwebsnagReleaseTag={tag}",
                "--no-daemon", "--no-configuration-cache", "--no-build-cache", "--console=plain"],
               env, phase, capture)


def public_environment(env):
    return {key: value for key, value in env.items() if key not in SIGNING_SECRETS}


def clean_checkout(env, root):
    if run(["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all"], env, "Clean checkout"):
        raise ReleaseError("Release checkout contains modified or untracked files.")
    if (root / "local.properties").exists():
        raise ReleaseError("Release checkout must not contain local.properties; use ANDROID_HOME.")


def trust(env):
    # This runs in both jobs, before any repository build code or key materialization.
    public = public_environment(env)
    run(["git", "fetch", "--no-tags", "origin", "refs/heads/main"], public, "Main trust refresh")
    check_context(public, run(["git", "rev-parse", "HEAD"], public, "Checkout identity"),
                  run(["git", "rev-parse", "FETCH_HEAD"], public, "Main identity"))
    clean_checkout(public, Path.cwd())


def version(tag, env):
    text = gradle([":app:printWebSnagVersion"], tag, env, "Release tag validation")
    values = dict(line.split("=", 1) for line in text.splitlines() if line.startswith(("versionName=", "versionCode=")))
    if set(values) != {"versionName", "versionCode"} or not values["versionCode"].isdigit():
        raise ReleaseError("Gradle returned invalid version metadata.")
    return values


def verify_apk(env, expected, digest):
    apk = "app/build/outputs/apk/release/app-release.apk"
    signer = str(Path(env["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner")
    output = run([signer, "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", apk],
                 env, "APK signature verification")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", output, re.MULTILINE)
    if certificates != [digest]:
        raise ReleaseError("APK certificate does not match the recorded signing identity.")
    schemes = dict(re.findall(r"^Verified using (v[\d.]+) scheme [^:\n]*: (true|false)$", output, re.MULTILINE))
    if any(schemes.get(scheme) != "true" for scheme in ("v2", "v3")):
        raise ReleaseError("APK must verify with both v2 and v3 signing schemes.")
    for field, value in (("version-name", expected["versionName"]), ("version-code", expected["versionCode"]),
                         ("application-id", "websnag.elopenmike.com"), ("debuggable", "false")):
        if run(["apkanalyzer", "manifest", field, apk], env, "APK manifest verification") != value:
            raise ReleaseError("APK package/version/debuggable identity mismatch.")
    permissions = run(["apkanalyzer", "manifest", "permissions", apk], env, "APK permission verification")
    if "android.permission.INTERNET" in permissions.splitlines():
        raise ReleaseError("APK must not request INTERNET permission.")


def build(env, tag, digest):
    private = {key: env.pop(key, "") for key in SIGNING_SECRETS}
    for key in SIGNING_SECRETS:
        os.environ.pop(key, None)
    try:
        for field in ("KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS"):
            if not private[field].strip():
                raise ReleaseError(f"Release signing requires nonblank {field}.")
        with signing_workspace({"RUNNER_TEMP": env["RUNNER_TEMP"],
                                "KEYSTORE_BASE64": private.pop("KEYSTORE_BASE64")}) as workspace:
            child = dict(env, **private)
            key_file = workspace / "signing.keystore"
            child.update(KEYSTORE_PATH=str(key_file), GRADLE_USER_HOME=str(workspace / "gradle"),
                         WEBSNAG_SIGNING_CERT_SHA256=digest)
            try:
                gradle([":app:printWebSnagVersion", "-PwebsnagReleaseSigning=true"], tag, child,
                       "Signing input validation (keystore, passwords, alias, certificate)", capture=False)
                gradle(["assembleRelease", "bundleRelease", "lintRelease", "-PwebsnagReleaseSigning=true",
                        "--rerun-tasks"],
                       tag, child, "Signed APK/AAB build", capture=False)
            finally:
                child = public_environment(child)
                private.clear()
                key_file.unlink(missing_ok=True)
            expected = version(tag, child)
            verify_apk(child, expected, digest)
            gradle([":app:verifyBundleIdentity"], tag, child, "AAB identity verification", capture=False)
            print(f"Verified build-only identity: commit={env['GITHUB_SHA']} tag={tag} "
                  f"versionName={expected['versionName']} versionCode={expected['versionCode']} "
                  f"certificateSHA256={digest}", flush=True)
            return expected
    finally:
        private.clear()


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
