"""Disposable identity integration checks; never provisions or uses the durable CI key."""

import argparse
from pathlib import Path
import os
import subprocess
import tempfile
import uuid

from release_build import ReleaseError, gradle, run, verify_apk, version


def rejected(arguments, env, expected, cache_flags=("--no-build-cache", "--no-configuration-cache")):
    result = subprocess.run(["./gradlew", *arguments, "--no-daemon", *cache_flags, "--console=plain"],
                            env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout
    if result.returncode == 0 or expected not in output:
        raise ReleaseError("Expected failure gate was not observed: " + expected)
    for value in (env.get("KEYSTORE_PASSWORD"), env.get("KEY_PASSWORD"), env.get("KEYSTORE_PATH"),
                  "PRIVATE_SENTINEL"):
        if value and value.strip() and value in output:
            raise ReleaseError("A private input was found in failure output.")
    print("Rejected as expected: " + expected, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--failure-cases", action="store_true", help="Also exercise Gradle configuration failures")
    args = parser.parse_args()
    env = dict(os.environ)
    for name in ("KEYSTORE_BASE64", "KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS",
                 "WEBSNAG_SIGNING_CERT_SHA256"):
        env.pop(name, None)
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="websnag-disposable-") as directory:
        key = Path(directory) / "disposable.p12"
        env.update(KEYSTORE_PATH=str(key), KEYSTORE_PASSWORD=str(uuid.uuid4()),
                   KEY_PASSWORD="", KEY_ALIAS="disposable")
        env["KEY_PASSWORD"] = env["KEYSTORE_PASSWORD"]
        run(["keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12",
             "-storepass:env", "KEYSTORE_PASSWORD", "-keypass:env", "KEY_PASSWORD",
             "-alias", "disposable", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
             "-dname", "CN=Disposable WebSnag validation", "-noprompt"], env, "Disposable key generation", False)
        certificate = subprocess.run(
            ["keytool", "-exportcert", "-keystore", str(key), "-storepass:env", "KEYSTORE_PASSWORD",
             "-alias", "disposable"], env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True).stdout
        import hashlib
        digest = hashlib.sha256(certificate).hexdigest()
        env["WEBSNAG_SIGNING_CERT_SHA256"] = digest
        if args.failure_cases:
            for tasks in (["assemble"], [":app:assR"], [":app:bundle"]):
                rejected(tasks, env, "Release tasks require")
            enabled = [":app:printWebSnagVersion", "-PwebsnagReleaseSigning=true"]
            rejected(enabled, env, "Release builds require")
            rejected(enabled + ["-PwebsnagReleaseTag=v1.0.0-PRIVATE_SENTINEL"], env, "Invalid WebSnag release tag")
            tagged = enabled + ["-PwebsnagReleaseTag=v1.0.0-alpha.5"]
            for cache_flags in (("--configuration-cache", "--no-build-cache"),
                                ("--no-configuration-cache", "--build-cache")):
                rejected(tagged, env, "Release signing requires --no-configuration-cache", cache_flags)
            for field in ("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD",
                          "WEBSNAG_SIGNING_CERT_SHA256"):
                for value in (None, " "):
                    invalid = dict(env)
                    if value is None:
                        invalid.pop(field)
                    else:
                        invalid[field] = value
                    rejected(tagged, invalid, "Release signing requires nonblank " + field)
            for field in ("KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS"):
                invalid = dict(env)
                invalid[field] = "PRIVATE_SENTINEL"
                rejected(tagged, invalid, "Release signing")
            malformed = Path(directory) / "malformed.p12"
            malformed.write_text("not a keystore")
            for path in (malformed, Path(directory) / "missing.p12"):
                rejected(tagged, dict(env, KEYSTORE_PATH=str(path)), "Release signing could not read")
            rejected(tagged, dict(env, WEBSNAG_SIGNING_CERT_SHA256="0" * 64),
                     "Release signing certificate does not match")
        sha = run(["git", "rev-parse", "HEAD"], env, "Candidate commit")
        for tag in ("v1.0.0-alpha.5", "v1.0.0-alpha.6"):
            gradle(["assembleRelease", "bundleRelease", "lintRelease", "-PwebsnagReleaseSigning=true",
                    "--rerun-tasks", "--continue"], tag, env, "Disposable APK/AAB build", False)
            public = {key: value for key, value in env.items()
                      if key not in ("KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEYSTORE_PATH", "KEY_ALIAS")}
            expected = version(tag, public)
            verify_apk(public, expected, digest)
            gradle([":app:verifyBundleIdentity"], tag, public, "AAB identity verification", False)
            print(f"DISPOSABLE ONLY commit={sha} tag={tag} "
                  f"versionName={expected['versionName']} versionCode={expected['versionCode']} "
                  f"APK+AAB certificateSHA256={digest}; package and non-debuggability verified", flush=True)
    if key.exists():
        raise ReleaseError("Disposable key cleanup failed.")
    print("Disposable key removed. No durable signing environment was exercised.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, OSError, subprocess.SubprocessError) as error:
        # Only ReleaseError messages are intentionally sanitized.
        raise SystemExit(str(error) if isinstance(error, ReleaseError) else "Local validation failed.") from None
