import base64
import os
from pathlib import Path
import tempfile
import unittest
import sys

from release_build import ReleaseError, check_context, recorded_digest, run, signing_workspace


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


if __name__ == "__main__":
    unittest.main()
