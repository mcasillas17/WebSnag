import base64
import os
from pathlib import Path
import tempfile
import unittest
import sys
import subprocess
import signal
import time
from unittest.mock import patch

from release_build import ReleaseError, build, check_context, clean_checkout, recorded_digest, run, signing_workspace, trust, verify_apk


class ReleaseBuildTest(unittest.TestCase):
    def context(self):
        return {
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
            "GITHUB_REPOSITORY": "mcasillas17/WebSnag", "GITHUB_REF_PROTECTED": "true",
            "GITHUB_SHA": "a" * 40,
            "GITHUB_WORKFLOW_REF": "mcasillas17/WebSnag/.github/workflows/release-build.yml@refs/heads/main",
        }

    def test_accepts_exact_protected_main_dispatch(self):
        check_context(self.context(), "a" * 40, "a" * 40)

    def test_rejects_forks_pr_tags_unprotected_stale_or_changed_commits(self):
        for field, value in (
            ("GITHUB_EVENT_NAME", "pull_request"),
            ("GITHUB_EVENT_NAME", "pull_request_target"),
            ("GITHUB_REF", "refs/tags/v1.0.0"),
            ("GITHUB_REF", "refs/heads/untrusted"),
            ("GITHUB_REPOSITORY", "fork/WebSnag"),
            ("GITHUB_REF_PROTECTED", "false"),
            ("GITHUB_SHA", "PRIVATE_SENTINEL"),
            ("GITHUB_WORKFLOW_REF", "mcasillas17/WebSnag/.github/workflows/release-build.yml@refs/heads/fork"),
        ):
            env = self.context()
            env[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ReleaseError):
                check_context(env, "a" * 40, "a" * 40)
        for head, main in (("b" * 40, "a" * 40), ("a" * 40, "b" * 40)):
            with self.assertRaises(ReleaseError):
                check_context(self.context(), head, main)

    def test_requires_a_recorded_public_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            config = root / "config/prerelease-signing.properties"
            for value in ("", "not-hex", "a" * 63):
                config.write_text("certificateSha256=" + value + "\n")
                with self.assertRaises(ReleaseError):
                    recorded_digest(root)
            config.write_text("certificateSha256=" + "a" * 64 + "\n")
            self.assertEqual("a" * 64, recorded_digest(root))

    def test_materializes_private_temporary_key_and_cleans_success_and_failure(self):
        for fail in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                env = {"RUNNER_TEMP": directory, "KEYSTORE_BASE64": base64.b64encode(b"test-only").decode()}
                try:
                    with signing_workspace(env) as workspace:
                        key = workspace / "signing.keystore"
                        self.assertEqual(b"test-only", key.read_bytes())
                        self.assertEqual(0o600, key.stat().st_mode & 0o777)
                        self.assertEqual(0o700, workspace.stat().st_mode & 0o777)
                        if fail:
                            raise RuntimeError("simulated build failure")
                except RuntimeError:
                    self.assertTrue(fail)
                self.assertFalse((Path(directory) / "websnag-release").exists())

    def test_rejects_empty_malformed_or_oversized_base64_and_cleans(self):
        for value in ("", " ", "PRIVATE_SENTINEL", base64.b64encode(b"x" * 1_048_577).decode()):
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ReleaseError) as failure:
                    with signing_workspace({"RUNNER_TEMP": directory, "KEYSTORE_BASE64": value}):
                        self.fail("invalid material accepted")
                self.assertNotIn("PRIVATE_SENTINEL", str(failure.exception))
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_tool_failure_output_never_crosses_the_boundary(self):
        with self.assertRaises(ReleaseError) as failure:
            run([sys.executable, "-c", "print('PRIVATE_SENTINEL'); raise RuntimeError('PRIVATE_SENTINEL')"],
                dict(os.environ), "Test build")
        self.assertNotIn("PRIVATE_SENTINEL", str(failure.exception))
        self.assertIn("Test build failed", str(failure.exception))

    def test_does_not_reuse_existing_private_workspace(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory) / "websnag-release"
            workspace.mkdir()
            marker = workspace / "do-not-touch"
            marker.write_text("existing")
            with self.assertRaises(FileExistsError):
                with signing_workspace({"RUNNER_TEMP": directory, "KEYSTORE_BASE64": "eA=="}):
                    self.fail("existing workspace reused")
            self.assertEqual("existing", marker.read_text())

    def test_trust_children_do_not_inherit_signing_material(self):
        env = dict(self.context(), KEYSTORE_BASE64="PRIVATE_SENTINEL", KEYSTORE_PASSWORD="PRIVATE_SENTINEL",
                   KEY_PASSWORD="PRIVATE_SENTINEL", KEY_ALIAS="PRIVATE_SENTINEL", KEYSTORE_PATH="PRIVATE_SENTINEL")
        def git(command, child, phase, capture=True):
            self.assertFalse(any(key.startswith("KEY") for key in child))
            return "a" * 40 if command[:2] == ["git", "rev-parse"] else ""
        with patch("release_build.run", side_effect=git):
            trust(env)

    def test_wrapper_removes_key_before_public_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(self.context(), RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
                       KEYSTORE_PASSWORD="test-password", KEY_PASSWORD="test-password", KEY_ALIAS="test-alias")
            def metadata(tag, child):
                self.assertFalse((Path(directory) / "websnag-release/signing.keystore").exists())
                self.assertFalse(any(key.startswith("KEY") for key in child))
                return {"versionName": "1.0.0", "versionCode": "100009000"}
            with patch("release_build.gradle"), patch("release_build.version", side_effect=metadata), \
                    patch("release_build.verify_apk"):
                build(env, "v1.0.0", "a" * 64)

    def test_wrapper_identifies_missing_or_blank_secret_fields(self):
        for field in ("KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS"):
            for value in (None, "", " "):
                with tempfile.TemporaryDirectory() as directory:
                    env = dict(self.context(), RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
                               KEYSTORE_PASSWORD="test-password", KEY_PASSWORD="test-password", KEY_ALIAS="test-alias")
                    if value is None:
                        env.pop(field)
                    else:
                        env[field] = value
                    with patch("release_build.gradle"), patch("release_build.verify_apk"), \
                            patch("release_build.version", return_value={"versionName": "1.0.0", "versionCode": "100009000"}):
                        with self.assertRaisesRegex(ReleaseError, field):
                            build(env, "v1.0.0", "a" * 64)
                    self.assertEqual([], list(Path(directory).iterdir()))

    def test_wrapper_stops_and_cleans_on_signing_input_or_compilation_failure(self):
        for failing_phase in ("Signing input validation", "Signed APK/AAB build"):
            with tempfile.TemporaryDirectory() as directory:
                env = dict(self.context(), RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
                           KEYSTORE_PASSWORD="test-password", KEY_PASSWORD="test-password", KEY_ALIAS="test-alias")
                def failure(arguments, tag, child, phase, capture=True):
                    if phase.startswith(failing_phase):
                        raise ReleaseError(phase + " failed.")
                with patch("release_build.gradle", side_effect=failure), patch("release_build.verify_apk") as verifier:
                    with self.assertRaisesRegex(ReleaseError, failing_phase):
                        build(env, "v1.0.0", "a" * 64)
                    verifier.assert_not_called()
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_apk_gate_requires_v2_v3_and_no_internet(self):
        digest = "a" * 64
        expected = {"versionName": "1.0.0", "versionCode": "100009000"}
        for v3, internet in ((False, False), (True, True), (True, False)):
            def output(command, env, phase, capture=True):
                if "apksigner" in command[0]:
                    return ("Verified using v2 scheme (APK Signature Scheme v2): true\n"
                            f"Verified using v3 scheme (APK Signature Scheme v3): {str(v3).lower()}\n"
                            f"Signer #1 certificate SHA-256 digest: {digest}")
                return {"version-name": "1.0.0", "version-code": "100009000",
                        "application-id": "websnag.elopenmike.com", "debuggable": "false",
                        "permissions": "android.permission.INTERNET" if internet else "android.permission.NFC"}[command[2]]
            with patch("release_build.run", side_effect=output):
                if not v3 or internet:
                    with self.assertRaises(ReleaseError):
                        verify_apk({"ANDROID_HOME": "/test-sdk"}, expected, digest)
                else:
                    verify_apk({"ANDROID_HOME": "/test-sdk"}, expected, digest)

    def test_apk_permission_parser_rejects_empty_or_decorated_output(self):
        for permissions in ("", "uses-permission: android.permission.INTERNET",
                            "android.permission.INTERNET maxSdkVersion=30"):
            def output(command, env, phase, capture=True):
                if "apksigner" in command[0]:
                    return ("Verified using v2 scheme (APK Signature Scheme v2): true\n"
                            "Verified using v3 scheme (APK Signature Scheme v3): true\n"
                            f"Signer #1 certificate SHA-256 digest: {'a' * 64}")
                return {"version-name": "1.0.0", "version-code": "100009000",
                        "application-id": "websnag.elopenmike.com", "debuggable": "false",
                        "permissions": permissions}[command[2]]
            with patch("release_build.run", side_effect=output), self.assertRaises(ReleaseError):
                verify_apk({"ANDROID_HOME": "/test-sdk"},
                           {"versionName": "1.0.0", "versionCode": "100009000"}, "a" * 64)

    def test_clean_checkout_accepts_only_ignored_generated_build_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = dict(os.environ)
            run(["git", "init", "-q", str(root)], env, "Test repository")
            (root / ".git/info/exclude").write_text((Path(__file__).parents[2] / ".gitignore").read_text())
            for relative in (".gradle/cache", ".kotlin/cache", "app/build/output", "buildSrc/build/output"):
                generated = root / relative
                generated.parent.mkdir(parents=True, exist_ok=True)
                generated.write_text("generated")
            clean_checkout(env, root)

    def test_clean_checkout_rejects_untracked_and_ignored_local_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = dict(os.environ)
            run(["git", "init", "-q", str(root)], env, "Test repository")
            clean_checkout(env, root)
            (root / "extra.gradle").write_text("test")
            with self.assertRaisesRegex(ReleaseError, "untracked"):
                clean_checkout(env, root)
            (root / "extra.gradle").unlink()
            (root / ".git/info/exclude").write_text("local.properties\n")
            (root / "local.properties").write_text("sdk.dir=test")
            with self.assertRaisesRegex(ReleaseError, "local.properties"):
                clean_checkout(env, root)

    def test_sigterm_stops_child_group_before_temporary_key_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
                       PYTHONPATH=str(Path(__file__).parent.resolve()))
            worker_code = """
import os, signal, sys
from release_build import ReleaseError, run, signing_workspace
def stop(signum, frame):
    raise ReleaseError("test cancellation")
signal.signal(signal.SIGTERM, stop)
child = '''import os, pathlib, subprocess, sys, time
root = pathlib.Path(os.environ["RUNNER_TEMP"])
(root / "child.pid").write_text(str(os.getpid()))
grandchild = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
(root / "grandchild.pid").write_text(str(grandchild.pid))
time.sleep(60)
'''
try:
    with signing_workspace(dict(os.environ)):
        run([sys.executable, "-c", child], dict(os.environ), "Cancellation test", capture=False)
except ReleaseError:
    sys.exit(1)
"""
            worker = subprocess.Popen([sys.executable, "-B", "-c", worker_code], env=env,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                ready = Path(directory) / "grandchild.pid"
                deadline = time.monotonic() + 10
                while not ready.exists() and worker.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists(), "child group did not start")
                worker.send_signal(signal.SIGTERM)
                self.assertEqual(1, worker.wait(timeout=10))
                self.assertFalse((Path(directory) / "websnag-release").exists())
                for file in ("child.pid", "grandchild.pid"):
                    pid = (Path(directory) / file).read_text()
                    state = subprocess.run(["ps", "-p", pid, "-o", "stat="],
                                           capture_output=True, text=True).stdout.strip()
                    self.assertTrue(not state or state.startswith("Z"), "child group is still running")
            finally:
                if worker.poll() is None:
                    worker.kill()
                    worker.wait()


if __name__ == "__main__":
    unittest.main()
