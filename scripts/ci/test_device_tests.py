import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import device_tests


class DeviceTestsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.reports = self.root / "reports"
        self.reports.mkdir()
        self.output = self.root / "summary.json"

    def report(self, cases=None, **attributes):
        if cases is None:
            cases = [(name, "syntheticCheck", None) for name in device_tests.SMOKE_CLASSES]
            cases.append((*device_tests.ACCEPTANCE_TEST, None))
            cases.append((*device_tests.RECOVERY_ACCEPTANCE_TEST, None))
            cases.extend((*method, None) for method in device_tests.EMERGENCY_REQUIRED_METHODS)
        suite = ET.Element("testsuite", tests=str(len(cases)), failures="0", errors="0", skipped="0")
        for classname, name, status in cases:
            case = ET.SubElement(suite, "testcase", classname=classname, name=name)
            if status:
                ET.SubElement(case, status).text = "PRIVATE_SENTINEL"
                counter = {"failure": "failures", "error": "errors", "skipped": "skipped"}[status]
                suite.set(counter, str(int(suite.get(counter)) + 1))
        ET.SubElement(suite, "system-out").text = "PRIVATE_SENTINEL"
        suite.attrib.update(attributes)
        ET.ElementTree(suite).write(self.reports / "TEST-synthetic.xml")

    def check(self, suite="smoke"):
        return device_tests.check_reports(self.reports, self.output, suite)

    def test_accepts_real_cases_and_emits_only_bounded_status_metadata(self):
        self.report()
        self.check()
        data = json.loads(self.output.read_text())
        self.assertGreater(data["executed"], 0)
        self.assertEqual("passed", data["status"])
        self.assertNotIn("PRIVATE_SENTINEL", self.output.read_text())

    def test_missing_empty_malformed_and_counter_only_reports_fail(self):
        for content in (None, "", "<broken", '<testsuite tests="42" failures="0" errors="0"/>',
                        '<testsuite tests="0" failures="0" errors="0"/>'):
            with self.subTest(content=content):
                path = self.reports / "TEST-synthetic.xml"
                if path.exists():
                    path.unlink()
                if content is not None:
                    path.write_text(content)
                with self.assertRaises(device_tests.DeviceTestError):
                    self.check()
                self.assertEqual("failed", json.loads(self.output.read_text())["status"])

    def test_failed_errored_or_skipped_case_fails_even_with_other_passing_cases(self):
        for status in ("failure", "error", "skipped"):
            with self.subTest(status=status):
                cases = [(name, "syntheticCheck", None) for name in device_tests.SMOKE_CLASSES]
                self.report(cases + [(*device_tests.ACCEPTANCE_TEST, status),
                                     (*device_tests.RECOVERY_ACCEPTANCE_TEST, None)] +
                            [(*method, None) for method in device_tests.EMERGENCY_REQUIRED_METHODS])
                with self.assertRaisesRegex(device_tests.DeviceTestError, "failures, errors, or skipped"):
                    self.check()
                self.assertEqual("failed", json.loads(self.output.read_text())["status"])
                self.assertNotIn("PRIVATE_SENTINEL", self.output.read_text())

    def test_entirely_skipped_selection_fails_with_consistent_counters(self):
        cases = [(name, "syntheticCheck", "skipped") for name in device_tests.SMOKE_CLASSES]
        self.report(cases + [(*device_tests.ACCEPTANCE_TEST, "skipped"),
                             (*device_tests.RECOVERY_ACCEPTANCE_TEST, "skipped")])
        with self.assertRaisesRegex(device_tests.DeviceTestError, "No tests executed"):
            self.check()
        summary = json.loads(self.output.read_text())
        self.assertEqual("failed", summary["status"])
        self.assertEqual(0, summary["executed"])

    def test_missing_selected_class_or_exact_acceptance_method_fails(self):
        for excluded in (device_tests.SMOKE_CLASSES[0], device_tests.ACCEPTANCE_TEST[0]):
            self.report([(name, "syntheticCheck", None)
                         for name in device_tests.SMOKE_CLASSES if name != excluded])
            with self.assertRaises(device_tests.DeviceTestError):
                self.check()
        self.report([(name, "syntheticCheck", None) for name in device_tests.SMOKE_CLASSES])
        with self.assertRaises(device_tests.DeviceTestError):
            self.check()

    def test_full_requires_ui_coverage_in_addition_to_smoke(self):
        self.report()
        with self.assertRaises(device_tests.DeviceTestError):
            self.check("full")
        cases = [(name, "syntheticCheck", None) for name in device_tests.FULL_CLASSES]
        self.report(cases + [(*device_tests.ACCEPTANCE_TEST, None),
                             (*device_tests.RECOVERY_ACCEPTANCE_TEST, None)] +
                    [(*method, None) for method in device_tests.EMERGENCY_REQUIRED_METHODS])
        self.check("full")

    def test_agp_testsuites_wrapper_and_aggregate_counters(self):
        self.report()
        path = self.reports / "TEST-synthetic.xml"
        suite = ET.parse(path).getroot()
        root = ET.Element("testsuites", **suite.attrib)
        root.append(suite)
        ET.ElementTree(root).write(path)
        self.check()
        root.set("failures", "1")
        ET.ElementTree(root).write(path)
        with self.assertRaises(device_tests.DeviceTestError):
            self.check()

    def test_duplicate_or_inconsistent_results_fail(self):
        for attributes in ({"tests": "999"}, {"failures": "1"}, {"errors": "1"},
                           {"skipped": "1"}, {"tests": "-1"}):
            self.report(**attributes)
            with self.assertRaises(device_tests.DeviceTestError):
                self.check()
        self.report([(*device_tests.ACCEPTANCE_TEST, None)] * 2)
        with self.assertRaises(device_tests.DeviceTestError):
            self.check()

    def test_large_report_fails_without_including_its_content(self):
        (self.reports / "TEST-synthetic.xml").write_bytes(b"x" * (device_tests.MAX_REPORT_BYTES + 1))
        with self.assertRaises(device_tests.DeviceTestError):
            self.check()
        self.assertLess(self.output.stat().st_size, 65536)

    def test_smoke_selects_all_safety_classes_and_full_has_no_inclusion_filter(self):
        smoke = device_tests.gradle_command("smoke")
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.class=" +
                      ",".join(device_tests.SMOKE_CLASSES), smoke)
        self.assertFalse(any("Arguments.class=" in arg for arg in device_tests.gradle_command("full")))
        for suite in ("smoke", "full"):
            command = device_tests.gradle_command(suite)
            self.assertIn("--rerun-tasks", command)
            self.assertIn("--no-build-cache", command)
            self.assertIn("--no-daemon", command)
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.timeout_msec=60000", command)

    def test_registered_ci_dispatch_can_select_full_without_changing_pr_smoke(self):
        workflow = (device_tests.ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("  workflow_dispatch:\n    inputs:\n      suite:", workflow)
        self.assertIn("        options: [smoke, full]", workflow)
        self.assertIn("        default: smoke", workflow)
        self.assertIn("suite: ${{ github.event_name == 'workflow_dispatch' && inputs.suite || 'smoke' }}",
                      workflow)

    def test_smoke_includes_the_user_facing_recovery_safety_suite(self):
        self.assertIn(device_tests.PACKAGE + ".StorageRecoveryScreenTest", device_tests.SMOKE_CLASSES)

    def test_smoke_includes_schedule_receiver_action_validation(self):
        self.assertIn(device_tests.PACKAGE + ".core.schedule.ScheduleReceiverActionTest",
                      device_tests.SMOKE_CLASSES)

    def test_approved_recovery_method_cannot_be_missing_from_a_passing_gate(self):
        cases = [(name, "syntheticCheck", None) for name in device_tests.SMOKE_CLASSES]
        self.report(cases + [(*device_tests.ACCEPTANCE_TEST, None)])
        with self.assertRaisesRegex(device_tests.DeviceTestError, "acceptance method"):
            self.check()

    def test_device_guard_rejects_physical_wrong_avd_or_wrong_api(self):
        with self.assertRaises(device_tests.DeviceTestError):
            device_tests.verify_device("physical-device")
        for answers in (["device", "0", "websnag-ci-api36\nOK", "36"],
                        ["device", "1", "personal\nOK", "36"],
                        ["device", "1", "websnag-ci-api36\nOK", "35"]):
            with patch.object(device_tests, "adb", side_effect=answers):
                with self.assertRaises(device_tests.DeviceTestError):
                    device_tests.verify_device("emulator-5556")
        with patch.object(device_tests, "adb", side_effect=["device", "1", "websnag-ci-api36\nOK", "36"]):
            device_tests.verify_device("emulator-5556")

    def test_child_failure_and_timeout_are_not_success(self):
        with self.assertRaises(subprocess.CalledProcessError):
            device_tests.run([sys.executable, "-c", "raise SystemExit(7)"], timeout=5)
        with self.assertRaises(subprocess.TimeoutExpired):
            device_tests.run([sys.executable, "-c", "import time; time.sleep(30)"], timeout=0.1)

    def test_fresh_emulator_without_app_is_valid_and_only_exact_packages_are_removed(self):
        with patch.object(device_tests, "adb", return_value="") as adb:
            device_tests.uninstall_test_packages("emulator-5556")
            adb.assert_called_with("emulator-5556", "shell", "pm", "list", "packages")
        installed = "package:" + device_tests.PACKAGE + "\npackage:" + device_tests.PACKAGE + ".unrelated"
        with patch.object(device_tests, "adb", side_effect=[installed, "Success"]) as adb:
            device_tests.uninstall_test_packages("emulator-5556")
            adb.assert_called_with("emulator-5556", "uninstall", device_tests.PACKAGE)

    def test_gradle_failure_cannot_leave_a_passed_summary(self):
        def failed_build(*args, **kwargs):
            self.reports.mkdir(exist_ok=True)
            self.report()
            raise subprocess.CalledProcessError(1, ["gradle"])

        with patch.object(device_tests, "ROOT", self.root), \
                patch.object(device_tests, "REPORTS", self.reports), \
                patch.object(device_tests.os, "chdir"), \
                patch.object(device_tests, "verify_device"), \
                patch.object(device_tests, "uninstall_test_packages") as cleanup, \
                patch.object(device_tests, "run", side_effect=failed_build), \
                patch.object(sys, "argv", ["device_tests.py", "smoke"]):
            with self.assertRaises(subprocess.CalledProcessError):
                device_tests.main()
            summary = json.loads((self.root / "app/build/device-tests/smoke.json").read_text())
            self.assertEqual("failed", summary["status"])
            self.assertEqual(2, cleanup.call_count)

    def test_cancellation_stops_the_owned_child_group(self):
        pid_file = self.root / "child.pid"
        child = ("import os, pathlib, signal, time; "
                 "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                 f"pathlib.Path({str(pid_file)!r}).write_text(str(os.getpid())); time.sleep(60)")
        worker = ("import signal, device_tests; "
                  "signal.signal(signal.SIGTERM, device_tests.interrupted); "
                  f"device_tests.run({[sys.executable, '-c', child]!r}, timeout=60)")
        process = subprocess.Popen(
            [sys.executable, "-B", "-c", worker],
            env={**os.environ, "PYTHONPATH": str(Path(device_tests.__file__).parent)},
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        try:
            deadline = time.monotonic() + 10
            while not pid_file.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(pid_file.exists(), "owned child did not start")
            process.send_signal(signal.SIGTERM)
            self.assertNotEqual(0, process.wait(timeout=15))
            child_pid = int(pid_file.read_text())
            state = subprocess.run(["ps", "-p", str(child_pid), "-o", "stat="],
                                   capture_output=True, text=True).stdout.strip()
            self.assertTrue(not state or state.startswith("Z"), "owned child survived cancellation")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


if __name__ == "__main__":
    unittest.main()
