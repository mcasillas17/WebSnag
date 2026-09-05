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
from unittest.mock import patch
from unittest.mock import ANY
import validate_local

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

    def context(self):
        return {
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
            "GITHUB_REPOSITORY": "mcasillas17/WebSnag", "GITHUB_REF_PROTECTED": "true",
            "GITHUB_SHA": "a" * 40,
            "GITHUB_WORKFLOW_REF": "mcasillas17/WebSnag/.github/workflows/release-build.yml@refs/heads/main",
            "ANDROID_HOME": "/test-sdk",
            "RUNNER_TEMP": str(self.artifacts[0].parent),
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
            for value in ("", "not-hex", "a" * 63, "A" * 64, ":".join(["AA"] * 32)):
                config.write_text("certificateSha256=" + value + "\n")
                with self.assertRaises(ReleaseError):
                    recorded_digest(root)
            config.write_text("certificateSha256=" + "a" * 64 + "\n")
            self.assertEqual("a" * 64, recorded_digest(root))
            config.write_bytes(("certificateSha256=" + "a" * 64 + "\r\n").encode())
            self.assertEqual("a" * 64, recorded_digest(root))

    def test_digest_configuration_distinguishes_missing_from_malformed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            config = root / "config/prerelease-signing.properties"
            config.write_text("certificateSha256=\n")
            with self.assertRaisesRegex(ReleaseError, "not configured"):
                recorded_digest(root)
            for value in ("A" * 64, "a" * 64 + " "):
                config.write_text("certificateSha256=" + value + "\n")
                with self.assertRaisesRegex(ReleaseError, "64 lowercase"):
                    recorded_digest(root)
            config.write_text(("certificateSha256=" + "a" * 64 + "\n") * 2)
            with self.assertRaisesRegex(ReleaseError, "exactly one"):
                recorded_digest(root)

    def test_local_validator_installs_interrupt_handlers_before_key_creation(self):
        with patch("signal.signal") as handlers, \
                patch("argparse.ArgumentParser.parse_args"), patch("validate_local.os.umask"), \
                patch("validate_local.tempfile.TemporaryDirectory", side_effect=RuntimeError("before key creation")):
            with self.assertRaisesRegex(RuntimeError, "before key creation"):
                validate_local.main()
            handlers.assert_any_call(signal.SIGTERM, ANY)
            handlers.assert_any_call(signal.SIGINT, ANY)

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

    def test_darwin_cleanup_requires_positive_zombie_only_evidence(self):
        for code, states, accepted in ((0, "Z\n", True), (0, " Zs+\nZ\n", True),
                                        (1, "", False), (0, "", False),
                                        (0, "S\n", False), (0, "Zgarbage\n", False)):
            result = subprocess.CompletedProcess([], code, states, "")
            with patch("release_build.sys.platform", "darwin"), \
                    patch("release_build.os.killpg", side_effect=PermissionError), \
                    patch("release_build.subprocess.run", return_value=result):
                if accepted:
                    run([sys.executable, "-c", "pass"], self.environment, "Cleanup evidence", False)
                else:
                    with self.assertRaisesRegex(ReleaseError, "cleanup"):
                        run([sys.executable, "-c", "pass"], self.environment, "Cleanup evidence", False)

    def test_darwin_probe_ignores_legacy_command_mode(self):
        with patch("release_build.sys.platform", "darwin"), \
                patch("release_build.os.killpg", side_effect=PermissionError), \
                patch("release_build.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "Z\n", "")) as probe:
            run([sys.executable, "-c", "pass"], dict(self.environment, COMMAND_MODE="legacy"),
                "Probe mode", capture=False)
        self.assertEqual("unix2003", probe.call_args.kwargs["env"]["COMMAND_MODE"])

    def test_deadline_failure_is_distinct_from_cancellation(self):
        with self.assertRaisesRegex(ReleaseError, "Test deadline timed out") as failure:
            run([sys.executable, "-c", "import time; time.sleep(5)"], self.environment,
                "Test deadline", capture=False, timeout=0.05)
        self.assertNotIn("interrupted", str(failure.exception))

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

    def test_main_names_missing_runtime_variables_before_trust(self):
        for field in ("ANDROID_HOME", "RUNNER_TEMP"):
            for value in (None, " "):
                env = self.context()
                if value is None:
                    env.pop(field)
                else:
                    env[field] = value
                with patch.dict(os.environ, env, clear=True), \
                        patch("release_build.sys.argv", ["release_build.py", "preflight"]), \
                        patch("release_build.signal.signal"), patch("release_build.os.umask"), \
                        patch("release_build.trust") as trust_call, \
                        patch("release_build.recorded_digest", return_value="a" * 64), patch("release_build.version"):
                    with self.assertRaisesRegex(ReleaseError, field):
                        main()
                    trust_call.assert_not_called()

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
        for v1, v3, internet in ((False, False, False), (False, True, True),
                                (False, True, False), (True, True, False)):
            def output(command, env, phase, capture=True):
                if "apksigner" in command[0]:
                    return (f"Verified using v1 scheme (JAR signing): {str(v1).lower()}\n"
                            "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                            f"Verified using v3 scheme (APK Signature Scheme v3): {str(v3).lower()}\n"
                            f"Signer #1 certificate SHA-256 digest: {digest}")
                return {"version-name": "1.0.0", "version-code": "100009000",
                        "application-id": "websnag.elopenmike.com", "debuggable": "false",
                        "print": '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                                 '<uses-permission android:name="android.permission.'
                                 + ("INTERNET" if internet else "NFC") + '"/></manifest>'}[command[2]]
            with patch("release_build.run", side_effect=output):
                if v1 or not v3 or internet:
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
        # Conservatively reserve this token for the four bindings, even in comments or strings.
        secret_reference = r"(?i)\bsecrets\b"
        bindings = re.findall(r"^\s+([A-Z_][A-Z0-9_]*):\s*\$\{\{\s*secrets\.([A-Z_][A-Z0-9_]*)\s*\}\}\s*$",
                              text, re.MULTILINE)
        expected = {"KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS"}
        self.assertEqual(sorted(bindings), sorted((name, name) for name in expected))
        self.assertEqual(len(re.findall(secret_reference, text)), len(bindings),
                         "the 'secrets' token is reserved for the four identity bindings; change this contract deliberately")
        self.assertIsNone(re.search(r"^\s*['\"]?secrets['\"]?\s*:\s*inherit\s*$", text, re.MULTILINE))
        self.assertTrue({name for name, _ in bindings} <= set(SIGNING_SECRETS), "unregistered signing input")
        sign = re.search(r"^  sign:\n(.*?)(?=^  [A-Za-z_][A-Za-z0-9_-]*:\s*$|\Z)",
                         text, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(sign, "expected sign job")
        body = sign.group(1)
        self.assertRegex(body, r"(?m)^    environment: prerelease-signing$")
        self.assertRegex(body, r"(?m)^    needs: preflight$")
        self.assertIsNone(re.search(r"^    if:", body, re.MULTILINE), "sign must use the default success condition")
        step = re.search(r"^      - name: Build and check signed candidates\n(.*?)(?=^      - name:|\Z)",
                         body, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(step, "expected credentialed build step")
        self.assertEqual(len(re.findall(secret_reference, step.group(1))), len(bindings),
                         "signing inputs must be scoped to the protected build step")

    def test_every_workflow_signing_secret_is_registered(self):
        workflow = (Path(__file__).parents[2] / ".github/workflows/release-build.yml").read_text()
        self.assert_workflow_secret_contract(workflow)
        with self.assertRaisesRegex(AssertionError, "token is reserved"):
            self.assert_workflow_secret_contract(workflow + "\n# secrets are configured separately\n")
        altered = workflow.replace("          KEY_ALIAS:", "          NEW_SECRET: ${{ secrets.NEW_SECRET }}\n          KEY_ALIAS:")
        with self.assertRaises(AssertionError):
            self.assert_workflow_secret_contract(altered)
        bracket = workflow + "\n    EXFIL: ${{ secrets['KEY_PASSWORD'] }}\n"
        with self.assertRaises(AssertionError):
            self.assert_workflow_secret_contract(bracket)
        for expression in ("format('{0}', secrets.KEY_PASSWORD)", "fromJSON('{\"a\":1}').a && secrets.KEYSTORE_BASE64",
                           "format('}}', toJSON(secrets))"):
            with self.assertRaises(AssertionError):
                self.assert_workflow_secret_contract(workflow + "\n    EXFIL: ${{ " + expression + " }}\n")
        with self.assertRaises(AssertionError):
            self.assert_workflow_secret_contract(workflow.replace("    environment: prerelease-signing\n", ""))
        secret_lines = [line for line in workflow.splitlines() if "secrets." in line]
        moved = workflow
        for line in secret_lines:
            moved = moved.replace(line + "\n", "")
        preflight_env = "    env:\n" + "".join("      " + line.strip() + "\n" for line in secret_lines)
        moved = moved.replace("  preflight:\n", "  preflight:\n" + preflight_env)
        with self.assertRaises(AssertionError):
            self.assert_workflow_secret_contract(moved)
        fields = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS")
        for field in fields:
            for wrong in fields:
                if field != wrong:
                    altered = workflow.replace(f"{field}: ${{{{ secrets.{field} }}}}",
                                               f"{field}: ${{{{ secrets.{wrong} }}}}")
                    with self.assertRaises(AssertionError):
                        self.assert_workflow_secret_contract(altered)

    def assert_no_signing_bindings(self, text):
        self.assertIsNone(re.search(r"^\s*['\"]?secrets['\"]?\s*:\s*inherit\s*$", text, re.MULTILINE))
        self.assertIsNone(re.search(r"\bsecrets\b", text, re.IGNORECASE),
                          "reserved credential token outside protected workflow")
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
        for text in ("    secrets: inherit", "    'secrets': inherit",
                     "    EXFIL: ${{ secrets['KEY_PASSWORD'] }}", "    EXFIL: ${{ toJSON(secrets) }}",
                     "    EXFIL: ${{ format('{0}', toJSON(secrets)) }}",
                     "    EXFIL: ${{ format('}}', SECRETS.KEY_PASSWORD) }}"):
            with self.assertRaises(AssertionError):
                self.assert_no_signing_bindings(text)

    def test_normal_and_failed_tool_exit_leave_no_signing_descendants(self):
        for status in (0, 1):
            with tempfile.TemporaryDirectory() as directory:
                lease = Path(directory) / "child-lease"
                lease.touch()
                env = dict(self.environment, RUNNER_TEMP=directory)
                sleeper = """
import pathlib, sys, time
lease = pathlib.Path(sys.argv[1])
deadline = time.monotonic() + 60
while lease.exists() and time.monotonic() < deadline:
    time.sleep(0.02)
"""
                code = """
import os, pathlib, subprocess, sys
child = subprocess.Popen([sys.executable, "-c", sys.argv[2], sys.argv[3]])
pathlib.Path(os.environ["RUNNER_TEMP"], "residual.pid").write_text(str(child.pid))
sys.exit(int(sys.argv[1]))
"""
                pid = None
                try:
                    if status:
                        with self.assertRaises(ReleaseError):
                            run([sys.executable, "-c", code, str(status), sleeper, str(lease)],
                                env, "Test exit", capture=False)
                    else:
                        run([sys.executable, "-c", code, str(status), sleeper, str(lease)],
                            env, "Test exit", capture=False)
                    pid = int((Path(directory) / "residual.pid").read_text())
                    self.assert_process_stopped(pid)
                    pid = None
                finally:
                    lease.unlink(missing_ok=True)
                    if pid is not None:
                        self.assert_process_stopped(pid)

    def test_confirmed_stopped_processes_are_never_signalled_again(self):
        with patch.object(os, "kill") as kill:
            self.test_normal_and_failed_tool_exit_leave_no_signing_descendants()
        kill.assert_not_called()

    def test_failure_cleanup_releases_lease_without_signalling_a_pid(self):
        with patch.object(self, "assert_process_stopped", side_effect=[AssertionError("test failure"), None]), \
                patch.object(os, "kill") as kill:
            with self.assertRaisesRegex(AssertionError, "test failure"):
                self.test_normal_and_failed_tool_exit_leave_no_signing_descendants()
        kill.assert_not_called()

    def test_clean_checkout_accepts_only_ignored_generated_build_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = dict(self.environment)
            run(["git", "init", "-q", str(root)], env, "Test repository")
            (root / ".git/info/exclude").write_text((Path(__file__).parents[2] / ".gitignore").read_text())
            for relative in (".gradle/cache", ".kotlin/cache", "app/build/output", "buildSrc/build/output",
                             "scripts/release/__pycache__/release_build.cpython-3xx.pyc"):
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

    def test_key_ignores_are_protected_and_tracked_keystores_are_rejected(self):
        repository = Path(__file__).parents[2]
        self.assertIn("/.gitignore @mcasillas17", (repository / ".github/CODEOWNERS").read_text())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = self.environment
            run(["git", "init", "-q", str(root)], env, "Test repository")
            (root / ".git/info/exclude").write_text((repository / ".gitignore").read_text())
            for filename in ("signing.jks", "signing.keystore", "signing.p12", "signing.pfx", "signing.P12"):
                run(["git", "-C", str(root), "check-ignore", "-q", filename], env, "Keystore ignore policy")
            fixture = root / "signing.P12"
            fixture.write_text("Synthetic placeholder, not a keystore")
            run(["git", "-C", str(root), "add", "-f", fixture.name], env, "Synthetic tracked fixture")
            run(["git", "-C", str(root), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                 "commit", "-q", "-m", "Synthetic fixture"], env, "Synthetic fixture commit")
            with self.assertRaisesRegex(ReleaseError, "tracks signing keystore"):
                clean_checkout(env, root)

    def test_sigterm_stops_child_group_before_temporary_key_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(self.environment, RUNNER_TEMP=directory, KEYSTORE_BASE64="eA==",
                       PYTHONPATH=str(Path(__file__).parent.resolve()))
            worker_code = """
import os, signal, sys
from release_build import ReleaseError, install_interrupt_handlers, run, signing_workspace
install_interrupt_handlers()
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
except ReleaseError as error:
    print(str(error), flush=True)
    sys.exit(1)
"""
            worker = subprocess.Popen([sys.executable, "-B", "-c", worker_code], env=env,
                                      stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
            try:
                ready = Path(directory) / "grandchild.pid"
                deadline = time.monotonic() + 30
                while not ready.exists() and worker.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists(), "child group did not start")
                worker.send_signal(signal.SIGTERM)
                self.assertEqual(1, worker.wait(timeout=30))
                output, _ = worker.communicate(timeout=30)
                self.assertIn("Cancellation test interrupted.", output)
                self.assertNotIn("timed out", output)
                self.assertFalse((Path(directory) / "websnag-release").exists())
                for file in ("child.pid", "grandchild.pid"):
                    pid = (Path(directory) / file).read_text()
                    self.assert_process_stopped(pid)
            finally:
                if worker.poll() is None:
                    worker.kill()
                    worker.wait()
                worker.stdout.close()


if __name__ == "__main__":
    unittest.main()
