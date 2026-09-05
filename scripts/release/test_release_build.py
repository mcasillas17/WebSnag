import base64
import os
from pathlib import Path
import tempfile
import unittest
import sys
import subprocess
import signal
import time
import re
import uuid
from unittest.mock import patch

from release_build import SIGNING_SECRETS, ReleaseError, build, check_context, clean_checkout, gradle, main, public_environment, recorded_digest, run, signing_workspace, trust, verify_apk, verify_apk_permissions


class ReleaseBuildTest(unittest.TestCase):
    def setUp(self):
        self.environment = public_environment(os.environ)
        outputs = tempfile.TemporaryDirectory()
        self.addCleanup(outputs.cleanup)
        self.artifacts = (Path(outputs.name) / "test.apk", Path(outputs.name) / "test.aab")
        paths = patch.multiple("release_build", APK_PATH=self.artifacts[0], BUNDLE_PATH=self.artifacts[1])
        paths.start()
        self.addCleanup(paths.stop)
        analyzer = patch("release_build.shutil.which", return_value="/test-sdk/cmdline-tools/latest/bin/apkanalyzer")
        analyzer.start()
        self.addCleanup(analyzer.stop)

    def simulate_gradle(self, arguments, tag, child, phase, capture=True):
        if phase == "Signed APK/AAB build":
            for artifact in self.artifacts:
                artifact.write_bytes(b"synthetic test artifact")

    def assert_process_stopped(self, pid):
        deadline = time.monotonic() + 30
        while True:
            state = subprocess.run(["ps", "-p", str(pid), "-o", "stat="],
                                   capture_output=True, text=True).stdout.strip()
            if not state or state.startswith("Z"):
                return
            if time.monotonic() >= deadline:
                self.fail("child process is still running")
            time.sleep(0.02)

    def stop_owned_test_process(self, pid, token):
        command = subprocess.run(["ps", "-p", str(pid), "-o", "command="],
                                 capture_output=True, text=True).stdout
        if token in command:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

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

    def test_certificate_configuration_rejects_invalid_utf8_without_a_traceback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            (root / "config/prerelease-signing.properties").write_bytes(b"\xffPRIVATE_SENTINEL")
            with self.assertRaisesRegex(ReleaseError, "certificate configuration") as failure:
                recorded_digest(root)
            self.assertNotIn("PRIVATE_SENTINEL", str(failure.exception))

    def test_gradle_project_history_stays_inside_the_temporary_home(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(self.environment, GRADLE_USER_HOME=str(Path(directory) / "gradle"))
            with patch("release_build.run", return_value="") as command:
                gradle(["assembleRelease", "-PwebsnagReleaseSigning=true"], "v1.0.0", env, "Test build", False)
            arguments = command.call_args.args[0]
            self.assertIn("--project-cache-dir", arguments)
            self.assertEqual(str(Path(env["GRADLE_USER_HOME"]) / "project-cache"),
                             arguments[arguments.index("--project-cache-dir") + 1])

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

    def test_decoded_bytes_are_wiped_when_materialization_cannot_start(self):
        for reason in ("oversize", "missing-temp", "existing-workspace"):
            with tempfile.TemporaryDirectory() as directory:
                material = bytearray(b"x" * (1_048_577 if reason == "oversize" else 8))
                env = {"RUNNER_TEMP": directory, "KEYSTORE_BASE64": "eA=="}
                if reason == "missing-temp":
                    env.pop("RUNNER_TEMP")
                if reason == "existing-workspace":
                    (Path(directory) / "websnag-release").mkdir()
                with patch("release_build.bytearray", return_value=material, create=True):
                    with self.assertRaises((ReleaseError, KeyError, FileExistsError)):
                        with signing_workspace(env):
                            self.fail("invalid materialization succeeded")
                self.assertFalse(any(material), "decoded bytes were retained")

    def test_tool_failure_output_never_crosses_the_boundary(self):
        with self.assertRaises(ReleaseError) as failure:
            run([sys.executable, "-c", "print('PRIVATE_SENTINEL'); raise RuntimeError('PRIVATE_SENTINEL')"],
                self.environment, "Test build")
        self.assertNotIn("PRIVATE_SENTINEL", str(failure.exception))
        self.assertIn("Test build failed", str(failure.exception))

    def test_tool_start_and_encoding_failures_are_sanitized_and_named(self):
        with self.assertRaisesRegex(ReleaseError, "Test start"):
            run(["/PRIVATE_SENTINEL/nonexistent"], self.environment, "Test start")
        with self.assertRaisesRegex(ReleaseError, "Test encoding"):
            run([sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'\\xff')"],
                self.environment, "Test encoding")

    def test_suite_helpers_ignore_host_signing_environment(self):
        with patch.dict(os.environ, {"KEY_PASSWORD": "PRIVATE_SENTINEL"}):
            self.test_tool_failure_output_never_crosses_the_boundary()

    def test_process_group_leader_is_not_reaped_before_group_cleanup(self):
        kill = os.killpg
        def unreaped(pid, signum):
            os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            kill(pid, signum)
        with patch("release_build.os.killpg", side_effect=unreaped):
            run([sys.executable, "-c", "pass"], self.environment, "Unreaped leader", capture=False)

    def test_captured_output_requires_a_secret_free_environment(self):
        with self.assertRaisesRegex(ReleaseError, "secret-free"):
            run([sys.executable, "-c", "pass"], dict(self.environment, KEY_PASSWORD="PRIVATE_SENTINEL"), "Capture")

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
        with patch("release_build.run", side_effect=git), patch("release_build.clean_checkout"):
            trust(env)

    def test_main_clears_ambient_signing_inputs_before_trust(self):
        def check(env):
            self.assertFalse(any(key in os.environ for key in SIGNING_SECRETS))
        with patch.dict(os.environ, dict(self.context(), KEY_PASSWORD="PRIVATE_SENTINEL"), clear=True), \
                patch("release_build.sys.argv", ["release_build.py", "preflight"]), \
                patch("release_build.signal.signal"), patch("release_build.os.umask"), \
                patch("release_build.trust", side_effect=check), \
                patch("release_build.recorded_digest", return_value="a" * 64), patch("release_build.version"):
            main()

    def test_trust_environment_test_does_not_depend_on_developer_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "local.properties").write_text("sdk.dir=test")
            with patch("release_build.Path.cwd", return_value=Path(directory)):
                self.test_trust_children_do_not_inherit_signing_material()

    def test_wrapper_removes_key_before_public_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(self.context(), RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
                       KEYSTORE_PASSWORD="test-password", KEY_PASSWORD="test-password", KEY_ALIAS="test-alias")
            def metadata(tag, child):
                self.assertFalse((Path(directory) / "websnag-release/signing.keystore").exists())
                self.assertFalse(any(key.startswith("KEY") for key in child))
                return {"versionName": "1.0.0", "versionCode": "100009000"}
            with patch("release_build.gradle", side_effect=self.simulate_gradle), \
                    patch("release_build.version", side_effect=metadata), \
                    patch("release_build.verify_apk"):
                build(env, "v1.0.0", "a" * 64)

    def test_wrapper_rejects_stale_artifacts_when_build_produces_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(self.context(), RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
                       KEYSTORE_PASSWORD="test-password", KEY_PASSWORD="test-password", KEY_ALIAS="test-alias")
            for artifact in self.artifacts:
                artifact.write_bytes(b"stale artifact")
            with patch("release_build.gradle"), patch("release_build.verify_apk"), \
                    patch("release_build.version", return_value={"versionName": "1.0.0", "versionCode": "100009000"}):
                with self.assertRaisesRegex(ReleaseError, "fresh APK and AAB"):
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
                        "print": '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                                 '<uses-permission android:name="android.permission.'
                                 + ("INTERNET" if internet else "NFC") + '"/></manifest>'}[command[2]]
            with patch("release_build.run", side_effect=output):
                if not v3 or internet:
                    with self.assertRaises(ReleaseError):
                        verify_apk({"ANDROID_HOME": "/test-sdk"}, expected, digest)
                else:
                    verify_apk({"ANDROID_HOME": "/test-sdk"}, expected, digest)

    def test_apkanalyzer_must_resolve_inside_the_configured_sdk(self):
        for location in (None, "/usr/local/bin/apkanalyzer"):
            with patch("release_build.shutil.which", return_value=location):
                with self.assertRaisesRegex(ReleaseError, "ANDROID_HOME"):
                    self.test_apk_gate_requires_v2_v3_and_no_internet()

    def test_apk_permission_parser_rejects_malformed_xml_and_internet_aliases(self):
        for xml in ("", "uses-permission: android.permission.INTERNET", "<resources/>",
                    '<!DOCTYPE manifest SYSTEM "file:///PRIVATE_SENTINEL"><manifest/>'):
            with self.assertRaises(ReleaseError):
                verify_apk_permissions(xml)
        for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            with self.assertRaises(ReleaseError):
                verify_apk_permissions('<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                                       f'<{tag} android:name="android.permission.INTERNET"/></manifest>')

    def test_apk_permission_parser_accepts_zero_or_non_nfc_permissions(self):
        for xml in ("<manifest/>", '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                    '<uses-permission android:name="android.permission.VIBRATE"/></manifest>'):
            verify_apk_permissions(xml)

    def assert_workflow_secret_contract(self, text):
        bindings = re.findall(r"^\s+([A-Z_][A-Z0-9_]*):\s*\$\{\{\s*secrets\.[A-Z_][A-Z0-9_]*\s*\}\}\s*$",
                              text, re.MULTILINE)
        self.assertTrue(bindings, "expected explicit environment secret bindings")
        self.assertEqual(len(re.findall(r"\bsecrets\s*[.\[]", text)), len(bindings),
                         "unrecognized secret-binding syntax")
        self.assertIsNone(re.search(r"^\s*secrets\s*:\s*inherit\s*$", text, re.MULTILINE))
        self.assertTrue(set(bindings) <= set(SIGNING_SECRETS), "unregistered signing input")

    def test_every_workflow_signing_secret_is_registered(self):
        workflow = (Path(__file__).parents[2] / ".github/workflows/release-build.yml").read_text()
        self.assert_workflow_secret_contract(workflow)
        altered = workflow.replace("          KEY_ALIAS:", "          NEW_SECRET: ${{ secrets.NEW_SECRET }}\n          KEY_ALIAS:")
        with self.assertRaisesRegex(AssertionError, "unregistered"):
            self.assert_workflow_secret_contract(altered)
        bracket = workflow + "\n    EXFIL: ${{ secrets['KEY_PASSWORD'] }}\n"
        with self.assertRaises(AssertionError):
            self.assert_workflow_secret_contract(bracket)

    def assert_no_signing_bindings(self, text):
        self.assertIsNone(re.search(r"^\s*secrets\s*:\s*inherit\s*$", text, re.MULTILINE))
        self.assertIsNone(re.search(r"\bsecrets\s*\[", text), "unrecognized secret-binding syntax")
        for name in SIGNING_SECRETS:
            self.assertIsNone(re.search(r"^\s+" + name + r":", text, re.MULTILINE),
                              "signing input injected outside the protected build workflow")
            self.assertNotIn("secrets." + name, text, "signing secret referenced outside the protected build workflow")

    def test_other_workflows_never_inject_release_signing_inputs(self):
        directory = Path(__file__).parents[2] / ".github/workflows"
        for workflow in directory.glob("*.y*ml"):
            if workflow.name != "release-build.yml":
                self.assert_no_signing_bindings(workflow.read_text())
        with self.assertRaises(AssertionError):
            self.assert_no_signing_bindings("    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}")
        for text in ("    secrets: inherit", "    EXFIL: ${{ secrets['KEY_PASSWORD'] }}"):
            with self.assertRaises(AssertionError):
                self.assert_no_signing_bindings(text)

    def test_normal_and_failed_tool_exit_leave_no_signing_descendants(self):
        for status in (0, 1):
            with tempfile.TemporaryDirectory() as directory:
                token = uuid.uuid4().hex
                env = dict(self.environment, RUNNER_TEMP=directory, TEST_PROCESS_TOKEN=token)
                code = """
import os, pathlib, subprocess, sys
child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", os.environ["TEST_PROCESS_TOKEN"]])
pathlib.Path(os.environ["RUNNER_TEMP"], "residual.pid").write_text(str(child.pid))
sys.exit(int(sys.argv[1]))
"""
                pid = None
                try:
                    if status:
                        with self.assertRaises(ReleaseError):
                            run([sys.executable, "-c", code, str(status)], env, "Test exit", capture=False)
                    else:
                        run([sys.executable, "-c", code, str(status)], env, "Test exit", capture=False)
                    pid = int((Path(directory) / "residual.pid").read_text())
                    self.assert_process_stopped(pid)
                    pid = None
                finally:
                    if pid is not None:
                        self.stop_owned_test_process(pid, token)

    def test_confirmed_stopped_processes_are_never_signalled_again(self):
        with patch.object(os, "kill") as kill:
            self.test_normal_and_failed_tool_exit_leave_no_signing_descendants()
        kill.assert_not_called()

    def test_failure_cleanup_signals_only_a_matching_test_process(self):
        with patch("subprocess.run") as command, patch.object(os, "kill") as kill:
            command.return_value.stdout = "unrelated process"
            self.stop_owned_test_process(12345, "unique-test-token")
            kill.assert_not_called()
            command.return_value.stdout = "python test unique-test-token"
            self.stop_owned_test_process(12345, "unique-test-token")
            kill.assert_called_once_with(12345, signal.SIGKILL)

    def test_clean_checkout_accepts_only_ignored_generated_build_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = dict(self.environment)
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
            env = dict(self.environment)
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
            env = dict(self.environment, RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
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
                deadline = time.monotonic() + 30
                while not ready.exists() and worker.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists(), "child group did not start")
                worker.send_signal(signal.SIGTERM)
                self.assertEqual(1, worker.wait(timeout=30))
                self.assertFalse((Path(directory) / "websnag-release").exists())
                for file in ("child.pid", "grandchild.pid"):
                    pid = (Path(directory) / file).read_text()
                    self.assert_process_stopped(pid)
            finally:
                if worker.poll() is None:
                    worker.kill()
                    worker.wait()


if __name__ == "__main__":
    unittest.main()
