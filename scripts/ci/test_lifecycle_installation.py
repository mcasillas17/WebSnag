"""AGP removes test installations; lifecycle setup must explicitly reinstall its built APKs."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, call, patch

import device_lifecycle as lifecycle
import device_tests
import test_device_tests

APK_PATHS = (
    "app/build/outputs/apk/debug/app-debug.apk",
    "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
)


class LifecycleInstallationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apks = [self.root / path for path in APK_PATHS]
        for apk in self.apks:
            apk.parent.mkdir(parents=True, exist_ok=True)
            apk.write_bytes(b"synthetic-apk")
        self.diagnostics = self.root / "private-diagnostics"
        environment = patch.dict(os.environ, {"WEBSNAG_DEVICE_DIAGNOSTICS_DIR": str(self.diagnostics)})
        environment.start()
        self.addCleanup(environment.stop)

    def installer(self):
        installer = getattr(lifecycle, "install_lifecycle_apks", None)
        self.assertTrue(callable(installer), "bounded lifecycle APK installation is missing")
        return installer

    def test_installs_app_then_test_exactly_once_after_verification_and_before_seed(self):
        events = []
        boot = [1]
        def adb(serial, *args, **kwargs):
            events.append(("adb", serial, args))
            if args == ("reboot",):
                boot[0] += 1
            if args == ("shell", "settings", "get", "global", "boot_count"):
                return str(boot[0])
            if args == ("shell", "getprop", "sys.boot_completed"):
                return "1"
            return "Success"
        def phase(serial, method, deadline):
            events.append(("phase", method))
            return lifecycle.phase_result(method)
        def verify(serial, **kwargs):
            events.append(("verify", serial))
        with patch.object(lifecycle, "ROOT", self.root, create=True), \
                patch.object(lifecycle, "run_phase", side_effect=phase):
            lifecycle.run_lifecycle("emulator-5556", verify, adb)
        installs = [event for event in events if event[0] == "adb" and event[2][0] == "install"]
        self.assertEqual([
            ("adb", "emulator-5556", ("install", "-r", "-t", str(apk))) for apk in self.apks
        ], installs)
        self.assertEqual(("verify", "emulator-5556"), events[0])
        seed = events.index(("phase", lifecycle.PHASE_METHODS[0]))
        self.assertTrue(all(events.index(install) < seed for install in installs))
        self.assertFalse(any(event in installs for event in events[seed:]))

    def test_installers_share_remaining_overall_budget_and_are_individually_bounded(self):
        installer = self.installer()
        adb = Mock(return_value="Performing Streamed Install\nSuccess")
        with patch.object(lifecycle.time, "monotonic", side_effect=[100, 105]):
            installer("emulator-5556", self.root, 120, adb)
        self.assertEqual([
            call("emulator-5556", "install", "-r", "-t", str(self.apks[0]), timeout=20),
            call("emulator-5556", "install", "-r", "-t", str(self.apks[1]), timeout=15),
        ], adb.call_args_list)
        self.assertEqual(30, lifecycle.INSTALL_TIMEOUT_SECONDS)

    def test_missing_empty_or_symlinked_apk_prevents_any_install(self):
        installer = self.installer()
        for kind in ("missing", "empty", "symlink"):
            with self.subTest(kind=kind):
                test_apk = self.apks[1]
                test_apk.unlink(missing_ok=True)
                if kind == "empty":
                    test_apk.touch()
                elif kind == "symlink":
                    test_apk.symlink_to(self.apks[0])
                adb = Mock(return_value="Success")
                with self.assertRaises(lifecycle.LifecycleError):
                    installer("emulator-5556", self.root, 1_000_000_000, adb)
                adb.assert_not_called()

    def test_failed_timed_out_or_false_success_install_stops_before_next_apk(self):
        installer = self.installer()
        for response in (
            subprocess.CalledProcessError(1, ["adb"], output="PRIVATE_SENTINEL installation failure"),
            subprocess.TimeoutExpired(["adb"], 30, output=b"PRIVATE_SENTINEL partial installation"),
            "Failure [PRIVATE_SENTINEL]",
            "Success\nFailure [PRIVATE_SENTINEL]",
            "",
        ):
            with self.subTest(response=type(response).__name__):
                adb = Mock(side_effect=response) if isinstance(response, Exception) else Mock(return_value=response)
                with self.assertRaises(lifecycle.LifecycleError) as failure:
                    installer("emulator-5556", self.root, 1_000_000_000, adb)
                self.assertEqual(1, adb.call_count)
                self.assertNotIn("PRIVATE_SENTINEL", str(failure.exception))
        self.assertTrue(any("PRIVATE_SENTINEL" in p.read_text() for p in self.diagnostics.glob("*.log")))

    def test_unverified_device_never_installs_and_failed_install_never_runs_a_phase(self):
        self.installer()
        adb = Mock(return_value="Failure [synthetic]")
        with patch.object(lifecycle, "ROOT", self.root), \
                patch.object(lifecycle, "run_phase") as phase:
            with self.assertRaises(device_tests.DeviceTestError):
                lifecycle.run_lifecycle("emulator-5556", Mock(side_effect=device_tests.DeviceTestError("wrong AVD")), adb)
            adb.assert_not_called()
            with self.assertRaises(lifecycle.LifecycleError):
                lifecycle.run_lifecycle("emulator-5556", Mock(), adb)
            phase.assert_not_called()

    def test_second_apk_failure_and_expired_deadline_cannot_continue(self):
        installer = self.installer()
        adb = Mock(side_effect=["Success", subprocess.CalledProcessError(1, ["adb"], output="synthetic test APK failure")])
        with self.assertRaises(lifecycle.LifecycleError):
            installer("emulator-5556", self.root, 1_000_000_000, adb)
        self.assertEqual(2, adb.call_count)
        adb.reset_mock()
        with self.assertRaises(lifecycle.LifecycleError):
            installer("emulator-5556", self.root, -1, adb)
        adb.assert_not_called()

    def test_install_failure_after_ordinary_success_fails_both_lane_artifacts(self):
        self.installer()
        helper = test_device_tests.DeviceTestsTest()
        helper.setUp()
        self.addCleanup(helper.doCleanups)
        for suite in ("smoke", "full"):
            def build(*args, **kwargs):
                helper.reports.mkdir(exist_ok=True)
                classes = device_tests.SMOKE_CLASSES if suite == "smoke" else device_tests.FULL_CLASSES
                helper.report(
                    [(name, "syntheticCheck", None) for name in classes] +
                    [(*device_tests.ACCEPTANCE_TEST, None), (*device_tests.RECOVERY_ACCEPTANCE_TEST, None)] +
                    [(*method, None) for method in device_tests.EMERGENCY_REQUIRED_METHODS]
                )
            with patch.object(device_tests, "ROOT", helper.root), \
                    patch.object(device_tests, "REPORTS", helper.reports), \
                    patch.object(device_tests.os, "chdir"), \
                    patch.object(device_tests, "verify_device"), \
                    patch.object(device_tests, "uninstall_test_packages"), \
                    patch.object(device_tests, "run", side_effect=build), \
                    patch.object(lifecycle, "install_lifecycle_apks", side_effect=lifecycle.LifecycleError("Lifecycle APK install failed.")), \
                    patch.object(lifecycle, "run_phase") as phase, \
                    patch.object(sys, "argv", ["device_tests.py", suite]):
                with self.assertRaises(lifecycle.LifecycleError):
                    device_tests.main()
                phase.assert_not_called()
            summary = json.loads((helper.root / f"app/build/device-tests/{suite}.json").read_text())
            self.assertEqual("failed", summary["status"])
            self.assertEqual(["not_run"] * 3, [case["status"] for case in summary["lifecycle"]])

    def test_command_failure_retains_capped_private_output_without_exporting_it(self):
        program = "print('PRIVATE_SENTINEL Unable to find instrumentation info'); raise SystemExit(1)"
        with patch.object(lifecycle, "phase_command", return_value=[sys.executable, "-c", program]):
            with self.assertRaises(lifecycle.LifecycleError) as failure:
                lifecycle.run_phase("emulator-5556", lifecycle.PHASE_METHODS[0], lifecycle.time.monotonic() + 5)
        self.assertNotIn("PRIVATE_SENTINEL", str(failure.exception))
        logs = list(self.diagnostics.glob("*.log"))
        self.assertEqual(1, len(logs))
        self.assertIn("PRIVATE_SENTINEL Unable to find instrumentation info", logs[0].read_text())
        self.assertEqual(0, logs[0].stat().st_mode & 0o077)
        self.assertLessEqual(logs[0].stat().st_size, lifecycle.MAX_PHASE_OUTPUT_BYTES)
        workflow = (device_tests.ROOT / ".github/workflows/device-tests.yml").read_text()
        self.assertIn("path: app/build/device-tests/*.json", workflow)
        self.assertNotIn(str(self.diagnostics), workflow)

    def test_private_output_is_size_capped_and_repo_destinations_are_refused(self):
        save = getattr(lifecycle, "write_private_diagnostic", None)
        self.assertTrue(callable(save))
        save("bounded", b"x" * (lifecycle.MAX_PHASE_OUTPUT_BYTES + 100))
        log = next(self.diagnostics.glob("*.log"))
        self.assertEqual(lifecycle.MAX_PHASE_OUTPUT_BYTES, log.stat().st_size)
        self.assertEqual(0, log.stat().st_mode & 0o077)
        with patch.object(lifecycle, "ROOT", self.root):
            save("must-not-save", b"PRIVATE_SENTINEL")
        self.assertEqual([log], list(self.diagnostics.glob("*.log")))


if __name__ == "__main__":
    unittest.main()
