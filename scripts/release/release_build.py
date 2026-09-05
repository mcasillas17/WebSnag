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
import tempfile
import time
import xml.etree.ElementTree as ET

SIGNING_SECRETS = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS", "KEYSTORE_PATH")
APK_PATH = Path("app/build/outputs/apk/release/app-release.apk")
BUNDLE_PATH = Path("app/build/outputs/bundle/release/app-release.aab")


class ReleaseError(Exception):
    pass


class ReleaseInterrupted(ReleaseError):
    pass


def interrupt_signing(signum, frame):
    raise ReleaseInterrupted("Signing interrupted.")


def install_interrupt_handlers():
    signal.signal(signal.SIGTERM, interrupt_signing)
    signal.signal(signal.SIGINT, interrupt_signing)


def required_environment(env, name):
    value = env.get(name)
    if not isinstance(value, str) or not value.strip():
        raise ReleaseError(f"Release tooling requires nonblank {name}.")
    return value


def validate_runtime_environment(env):
    for name in ("RUNNER_TEMP", "ANDROID_HOME"):
        required_environment(env, name)


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
    try:
        text = (root / "config/prerelease-signing.properties").read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        raise ReleaseError("Signing certificate configuration could not be read as UTF-8.") from None
    values = [line.removeprefix("certificateSha256=") for line in text.splitlines()
              if line.startswith("certificateSha256=")]
    if not values or values == [""]:
        raise ReleaseError("Approved signing certificate is not configured; follow docs/releasing.md.")
    if len(values) != 1:
        raise ReleaseError("Signing certificate configuration must contain exactly one certificateSha256 property.")
    if not re.fullmatch(r"[0-9a-f]{64}", values[0]):
        raise ReleaseError("Signing certificate digest must be 64 lowercase hex characters without spaces or separators.")
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
    try:
        if not material or len(material) > 1_048_576:
            raise ReleaseError("KEYSTORE_BASE64 must encode a nonempty keystore of at most 1 MiB.")
        workspace = Path(required_environment(env, "RUNNER_TEMP")).resolve() / "websnag-release"
        workspace.mkdir(mode=0o700)  # Refuse an existing directory rather than reuse private state.
        try:
            key = workspace / "signing.keystore"
            with open(key, "xb", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
                stream.write(material)
            material[:] = b"\0" * len(material)
            encoded = None
            yield workspace
        finally:
            shutil.rmtree(workspace)
    finally:
        material[:] = b"\0" * len(material)
        encoded = None


def run(command, env, phase, capture=True, timeout=1800):
    if capture and any(env.get(key) for key in SIGNING_SECRETS):
        raise ReleaseError("Captured tool output requires a secret-free environment.")
    if not hasattr(os, "waitid") or not hasattr(os, "WNOWAIT"):
        raise ReleaseError("Release tooling requires POSIX waitid/WNOWAIT support.")
    # Only public output is spooled; credentialed commands always go directly to DEVNULL.
    with (tempfile.TemporaryFile(dir=env.get("RUNNER_TEMP")) if capture else contextlib.nullcontext()) as output:
        try:
            process = subprocess.Popen(command, env=env, start_new_session=True,
                                       stdout=output if capture else subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL)
        except (OSError, ValueError):
            raise ReleaseError(f"{phase} could not start.") from None
        with process:
            try:
                deadline = time.monotonic() + timeout
                while os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is None:
                    if time.monotonic() >= deadline:
                        raise ReleaseError(f"{phase} timed out.")
                    time.sleep(0.05)
            except (ReleaseInterrupted, KeyboardInterrupt):
                raise ReleaseError(f"{phase} interrupted.") from None
            finally:
                # WNOWAIT keeps the leader's PID reserved until its group is terminated.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except PermissionError:
                    # Darwin reports EPERM for an owned group containing only unreaped zombies.
                    if sys.platform != "darwin":
                        raise
                    try:
                        members = subprocess.run(["ps", "-g", str(process.pid), "-o", "stat="],
                                                 env=dict(public_environment(env), COMMAND_MODE="unix2003"), capture_output=True,
                                                 text=True, timeout=5, check=False)
                    except subprocess.TimeoutExpired:
                        raise ReleaseError(f"{phase} cleanup status timed out.") from None
                    states = members.stdout.splitlines()
                    if members.returncode != 0 or not states or any(
                            not re.fullmatch(r"Z[+<>AELNSsVWX]*", state.strip()) for state in states):
                        raise ReleaseError(f"{phase} cleanup state could not be confirmed.") from None
                process.wait()
        if process.returncode:
            raise ReleaseError(f"{phase} failed. Raw tool output is suppressed; see docs/releasing.md.")
        if capture:
            output.seek(0)
            try:
                return output.read().decode("utf-8").strip()
            except UnicodeDecodeError:
                raise ReleaseError(f"{phase} returned invalid UTF-8.") from None
        return ""


def gradle(arguments, tag, env, phase, capture=True):
    project_cache = (["--project-cache-dir", str(Path(env["GRADLE_USER_HOME"]) / "project-cache")]
                     if env.get("GRADLE_USER_HOME") else [])
    return run(["./gradlew", "-q", *arguments, *project_cache, f"-PwebsnagReleaseTag={tag}",
                "--no-daemon", "--no-configuration-cache", "--no-build-cache", "--console=plain"],
               env, phase, capture)


def public_environment(env):
    return {key: value for key, value in env.items() if key not in SIGNING_SECRETS}


def clean_checkout(env, root):
    if run(["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all"], env, "Clean checkout"):
        raise ReleaseError("Release checkout contains modified or untracked files.")
    if run(["git", "-C", str(root), "ls-files", "--", ":(icase)*.jks", ":(icase)*.keystore",
            ":(icase)*.p12", ":(icase)*.pfx"], env, "Tracked keystore check"):
        raise ReleaseError("Release checkout tracks signing keystore files.")
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


def verify_apk_permissions(manifest_xml):
    try:
        if "<!DOCTYPE" in manifest_xml:
            raise ET.ParseError("DOCTYPE is forbidden")
        manifest = ET.fromstring(manifest_xml)
        if manifest.tag != "manifest":
            raise ET.ParseError("Expected manifest")
    except ET.ParseError:
        raise ReleaseError("APK permission manifest could not be parsed.") from None
    for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
        for permission in manifest.iter(tag):
            if permission.get("{http://schemas.android.com/apk/res/android}name") == "android.permission.INTERNET":
                raise ReleaseError("APK must not request INTERNET permission.")


def verify_apk(env, expected, digest):
    apk = str(APK_PATH)
    analyzer = shutil.which("apkanalyzer", path=env.get("PATH", os.defpath))
    sdk = Path(required_environment(env, "ANDROID_HOME"))
    if analyzer is None or not Path(analyzer).resolve().is_relative_to(sdk.resolve()):
        raise ReleaseError("apkanalyzer must resolve inside ANDROID_HOME.")
    signer = str(Path(env["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner")
    output = run([signer, "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", apk],
                 env, "APK signature verification")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", output, re.MULTILINE)
    if certificates != [digest]:
        raise ReleaseError("APK certificate does not match the recorded signing identity.")
    schemes = dict(re.findall(r"^Verified using (v[\d.]+) scheme [^:\n]*: (true|false)$", output, re.MULTILINE))
    if schemes.get("v1") != "false" or any(schemes.get(scheme) != "true" for scheme in ("v2", "v3")):
        raise ReleaseError("APK must verify with v2 and v3 signing and no v1 signature.")
    for field, value in (("version-name", expected["versionName"]), ("version-code", expected["versionCode"]),
                         ("application-id", "websnag.elopenmike.com"), ("debuggable", "false")):
        if run([analyzer, "manifest", field, apk], env, "APK manifest verification") != value:
            raise ReleaseError("APK package/version/debuggable identity mismatch.")
    verify_apk_permissions(run([analyzer, "manifest", "print", apk], env, "APK permission verification"))


def build(env, tag, digest):
    private = {key: env.pop(key, "") for key in SIGNING_SECRETS}
    for key in SIGNING_SECRETS:
        os.environ.pop(key, None)
    try:
        validate_runtime_environment(env)
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
                for artifact in (APK_PATH, BUNDLE_PATH):
                    artifact.unlink(missing_ok=True)
                gradle(["assembleRelease", "bundleRelease", "lintRelease", "-PwebsnagReleaseSigning=true",
                        "--rerun-tasks"],
                       tag, child, "Signed APK/AAB build", capture=False)
                if any(artifact.is_symlink() or not artifact.is_file() or artifact.stat().st_size == 0
                       for artifact in (APK_PATH, BUNDLE_PATH)):
                    raise ReleaseError("Signed build did not produce a fresh APK and AAB.")
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
    install_interrupt_handlers()
    env = dict(os.environ)
    for key in SIGNING_SECRETS:
        os.environ.pop(key, None)
    if len(sys.argv) != 2 or sys.argv[1] not in ("preflight", "build"):
        raise ReleaseError("Expected preflight or build.")
    validate_runtime_environment(env)
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
