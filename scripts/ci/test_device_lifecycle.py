"""Host sequencing and wire-result gates for real process/reboot acceptance."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import device_tests
import test_device_tests

try:
    import device_lifecycle
except ModuleNotFoundError:
    device_lifecycle = None

CLASS = "websnag.elopenmike.com.core.data.EmergencyRecoveryLifecycleTest"
METHODS = ("seedRecovery", "verifyProcessRestoration", "verifyRebootRestoration")


def phase_output(method=METHODS[0], code=0):
    def status(result):
        return (
            f"INSTRUMENTATION_STATUS: class={CLASS}\n"
            "INSTRUMENTATION_STATUS: current=1\n"
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
            "INSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS: stream=.\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS_CODE: {result}\n"
        )
    return status(1) + status(code) + (
        "INSTRUMENTATION_RESULT: stream=\nTime: 0.123\n\nOK (1 test)\n\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


class LifecycleIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.helper = test_device_tests.DeviceTestsTest()
        self.helper.setUp()
        self.addCleanup(self.helper.doCleanups)

    def test_full_excludes_only_host_ordered_class_not_coverage(self):
        full = device_tests.gradle_command("full")
        exclusions = [arg for arg in full if "Arguments.notClass=" in arg]
        self.assertEqual(["-Pandroid.testInstrumentationRunnerArguments.notClass=" + CLASS], exclusions)
        self.assertFalse(any("Arguments.class=" in arg for arg in full))

    def test_phase_results_in_the_ordinary_run_are_rejected_as_out_of_order(self):
        self.helper.report()
        import xml.etree.ElementTree as ET
        path = self.helper.reports / "TEST-synthetic.xml"
        root = ET.parse(path).getroot()
        ET.SubElement(root, "testcase", classname=CLASS, name=METHODS[0])
        root.set("tests", str(int(root.get("tests")) + 1))
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(device_tests.DeviceTestError, "ordered"):
            self.helper.check()

    def invoke_main(self, phase_results, suite="smoke"):
        def build(*args, **kwargs):
            self.helper.reports.mkdir(exist_ok=True)
            classes = device_tests.SMOKE_CLASSES if suite == "smoke" else device_tests.FULL_CLASSES
            self.helper.report(
                [(name, "syntheticCheck", None) for name in classes] +
                [(*device_tests.ACCEPTANCE_TEST, None), (*device_tests.RECOVERY_ACCEPTANCE_TEST, None)] +
                [(*method, None) for method in device_tests.EMERGENCY_REQUIRED_METHODS]
            )

        with patch.object(device_tests, "ROOT", self.helper.root), \
                patch.object(device_tests, "REPORTS", self.helper.reports), \
                patch.object(device_tests.os, "chdir"), \
                patch.object(device_tests, "verify_device"), \
                patch.object(device_tests, "uninstall_test_packages"), \
                patch.object(device_tests, "run", side_effect=build), \
                patch.object(device_tests, "run_lifecycle", create=True, return_value=phase_results) as lifecycle, \
                patch.object(sys, "argv", ["device_tests.py", suite]):
            device_tests.main()
            lifecycle.assert_called_once()
        return json.loads((self.helper.root / f"app/build/device-tests/{suite}.json").read_text())

    def test_missing_reordered_duplicate_or_failed_phases_cannot_leave_passed_artifact(self):
        valid = [{"class": CLASS, "name": method, "status": "passed"} for method in METHODS]
        for cases in ([], valid[:-1], list(reversed(valid)), valid + valid[:1],
                      [dict(case, status="skipped") for case in valid],
                      [dict(case, status="failure") for case in valid]):
            for suite in ("smoke", "full"):
                with self.subTest(cases=cases, suite=suite):
                    with self.assertRaises(device_tests.DeviceTestError):
                        self.invoke_main(cases, suite)
                    summary = json.loads((self.helper.root / f"app/build/device-tests/{suite}.json").read_text())
                    self.assertEqual("failed", summary["status"])

    def test_all_three_methods_are_counted_in_final_artifact_only_after_success(self):
        cases = [{"class": CLASS, "name": method, "status": "passed"} for method in METHODS]
        for suite in ("smoke", "full"):
            summary = self.invoke_main(cases, suite)
            self.assertEqual("passed", summary["status"])
            self.assertEqual(cases, summary["tests"][-3:])
            self.assertEqual(len(summary["tests"]), summary["executed"])

    def test_workflow_has_approved_bounded_lifecycle_allowance(self):
        workflow = (device_tests.ROOT / ".github/workflows/device-tests.yml").read_text()
        self.assertIn("timeout-minutes: 42", workflow)
        self.assertIn("timeout-minutes: 32", workflow)

    def test_failed_phase_retains_all_three_identities_and_never_publishes_early_pass(self):
        self.assertIsNotNone(device_lifecycle)
        output = self.helper.root / "app/build/device-tests/smoke.json"
        def build(*args, **kwargs):
            self.helper.reports.mkdir(exist_ok=True)
            self.helper.report()
        def lifecycle(serial, verify, adb, on_result):
            self.assertEqual("failed", json.loads(output.read_text())["status"])
            on_result({"class": CLASS, "name": METHODS[0], "status": "passed"})
            self.assertEqual("failed", json.loads(output.read_text())["status"])
            on_result({"class": CLASS, "name": METHODS[1], "status": "unverified"})
            raise device_lifecycle.LifecycleError("Lifecycle phase timed out.")
        with patch.object(device_tests, "ROOT", self.helper.root), \
                patch.object(device_tests, "REPORTS", self.helper.reports), \
                patch.object(device_tests.os, "chdir"), \
                patch.object(device_tests, "verify_device"), \
                patch.object(device_tests, "uninstall_test_packages"), \
                patch.object(device_tests, "run", side_effect=build), \
                patch.object(device_tests, "run_lifecycle", side_effect=lifecycle), \
                patch.object(sys, "argv", ["device_tests.py", "smoke"]):
            with self.assertRaises(device_lifecycle.LifecycleError):
                device_tests.main()
        summary = json.loads(output.read_text())
        self.assertEqual("failed", summary["status"])
        self.assertEqual(list(METHODS), [case["name"] for case in summary["lifecycle"]])
        self.assertEqual(["passed", "unverified", "not_run"], [case["status"] for case in summary["lifecycle"]])
        self.assertEqual({"class": CLASS, "name": METHODS[0], "status": "passed"}, summary["tests"][-1])


class LifecycleParserTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(device_lifecycle, "real lifecycle runner is not implemented")
        diagnostics = tempfile.TemporaryDirectory()
        self.addCleanup(diagnostics.cleanup)
        environment = patch.dict(os.environ, {"WEBSNAG_DEVICE_DIAGNOSTICS_DIR": diagnostics.name})
        environment.start()
        self.addCleanup(environment.stop)

    def test_valid_exact_single_method_returns_only_status_metadata(self):
        result = device_lifecycle.parse_phase_output(phase_output(), METHODS[0])
        self.assertEqual({"class": CLASS, "name": METHODS[0], "status": "passed"}, result)

    def test_failure_error_ignore_assumption_and_unknown_status_are_not_passes(self):
        for code in (-1, -2, -3, -4, 2, 99):
            with self.subTest(code=code):
                with self.assertRaises(device_lifecycle.LifecycleError):
                    device_lifecycle.parse_phase_output(phase_output(code=code), METHODS[0])

    def test_truncated_empty_counter_only_wrong_identity_and_duplicate_output_fail(self):
        good = phase_output()
        bad = [
            "", "OK (1 test)\nINSTRUMENTATION_CODE: -1\n",
            good.replace("INSTRUMENTATION_CODE: -1", ""),
            good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: " + "9" * 5000),
            good.replace("numtests=1", "numtests=0"),
            good.replace("current=1", "current=2"),
            good.replace("id=AndroidJUnitRunner", "id=other"),
            good.replace(CLASS, CLASS + "Other"),
            good.replace("test=seedRecovery", "test=verifyProcessRestoration"),
            good + good,
            good.replace("OK (1 test)", "OK (0 tests)"),
            good.replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: 0"),
            good.replace("INSTRUMENTATION_STATUS: current=1", "INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: current=1"),
            good + "INSTRUMENTATION_FAILED: PRIVATE_SENTINEL\n",
            good.replace("INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_RESULT: shortMsg=PRIVATE_SENTINEL\nINSTRUMENTATION_RESULT: stream="),
            "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\n" + good.replace("OK (1 test)", ""),
        ]
        for output in bad:
            with self.subTest(output=output[:80]):
                with self.assertRaises(device_lifecycle.LifecycleError) as failure:
                    device_lifecycle.parse_phase_output(output, METHODS[0])
                self.assertNotIn("PRIVATE_SENTINEL", str(failure.exception))

    def test_oversized_output_is_refused(self):
        with self.assertRaises(device_lifecycle.LifecycleError):
            device_lifecycle.parse_phase_output("x" * (device_lifecycle.MAX_PHASE_OUTPUT_BYTES + 1), METHODS[0])

    def test_phase_command_is_exact_and_does_not_reinstall_or_clear_data(self):
        command = device_lifecycle.phase_command("emulator-5556", METHODS[1])
        self.assertEqual(["adb", "-s", "emulator-5556", "shell", "am", "instrument", "-w", "-r",
                          "-e", "class", CLASS + "#" + METHODS[1],
                          "websnag.elopenmike.com.test/androidx.test.runner.AndroidJUnitRunner"], command)

    def test_phase_client_failure_noise_timeout_and_non_utf8_are_bounded(self):
        import time
        programs = (
            "print(" + repr(phase_output()) + "); raise SystemExit(7)",
            "import sys; sys.stdout.write('x' * 262144); sys.stdout.flush()",
            "import time; time.sleep(5)",
            "import sys; sys.stdout.buffer.write(bytes([255])); sys.stdout.flush()",
        )
        for program in programs:
            with self.subTest(program=program[:35]), \
                    patch.object(device_lifecycle, "phase_command", return_value=[sys.executable, "-c", program]), \
                    patch.object(device_lifecycle, "PHASE_TIMEOUT_SECONDS", 0.1):
                before = time.monotonic()
                with self.assertRaises(device_lifecycle.LifecycleError):
                    device_lifecycle.run_phase("emulator-5556", METHODS[0], before + 5)
                self.assertLess(time.monotonic() - before, 2)
        with patch.object(device_lifecycle, "phase_command", return_value=[
            sys.executable, "-c", "print(" + repr(phase_output()) + ")"
        ]):
            self.assertEqual({"class": CLASS, "name": METHODS[0], "status": "passed"},
                             device_lifecycle.run_phase("emulator-5556", METHODS[0], time.monotonic() + 5))

    def test_expired_deadline_does_not_launch_a_phase_client(self):
        with patch.object(device_lifecycle.subprocess, "Popen") as process:
            with self.assertRaises(device_lifecycle.LifecycleError):
                device_lifecycle.run_phase("emulator-5556", METHODS[0], -1)
            process.assert_not_called()

    def test_sequence_revalidates_before_reboot_and_after_boot_and_restores_settings(self):
        calls = []
        rebooted = [False]
        def adb(serial, *args, **kwargs):
            calls.append(("adb", serial, args))
            if args == ("reboot",):
                rebooted[0] = True
            if args == ("shell", "settings", "get", "global", "boot_count"):
                return "2" if rebooted[0] else "1"
            return "1" if args == ("shell", "getprop", "sys.boot_completed") else ""
        def verify(serial, **kwargs):
            calls.append(("verify", serial))
        def phase(serial, method, deadline):
            calls.append(("phase", method))
            return {"class": CLASS, "name": method, "status": "passed"}
        with patch.object(device_lifecycle, "install_lifecycle_apks"), \
                patch.object(device_lifecycle, "run_phase", side_effect=phase):
            result = device_lifecycle.run_lifecycle("emulator-5556", verify, adb)
        self.assertEqual(list(METHODS), [case["name"] for case in result])
        self.assertTrue(all(call[1] == "emulator-5556" for call in calls if call[0] in ("adb", "verify")))
        force_stop = calls.index(("adb", "emulator-5556", ("shell", "am", "force-stop", device_tests.PACKAGE)))
        reboot = calls.index(("adb", "emulator-5556", ("reboot",)))
        self.assertLess(calls.index(("phase", METHODS[0])), force_stop)
        self.assertLess(force_stop, calls.index(("phase", METHODS[1])))
        self.assertLess(calls.index(("phase", METHODS[1])), reboot)
        self.assertEqual(("verify", "emulator-5556"), calls[reboot - 1])
        restored = ("adb", "emulator-5556", ("shell", "settings", "put", "global", "window_animation_scale", "0"))
        self.assertGreater(calls.index(restored), reboot)
        self.assertIn(("verify", "emulator-5556"), calls[reboot + 1:calls.index(restored)])
        self.assertLess(calls.index(restored), calls.index(("phase", METHODS[2])))

    def test_unverified_device_or_failed_phase_never_reboots(self):
        from unittest.mock import Mock
        for failing_phase in (None, METHODS[0], METHODS[1]):
            adb = Mock()
            verify = Mock(side_effect=RuntimeError("wrong AVD") if failing_phase is None else None)
            def phase(serial, method, deadline):
                if method == failing_phase:
                    raise device_lifecycle.LifecycleError("phase failed")
                return {"class": CLASS, "name": method, "status": "passed"}
            with patch.object(device_lifecycle, "install_lifecycle_apks"), \
                    patch.object(device_lifecycle, "run_phase", side_effect=phase):
                with self.assertRaises((RuntimeError, device_lifecycle.LifecycleError)):
                    device_lifecycle.run_lifecycle("emulator-5556", verify, adb)
            self.assertFalse(any(call.args[1:] == ("reboot",) for call in adb.call_args_list))

    def test_reboot_and_overall_deadlines_fail_closed(self):
        self.assertEqual(60, device_lifecycle.PHASE_TIMEOUT_SECONDS)
        self.assertEqual(300, device_lifecycle.REBOOT_TIMEOUT_SECONDS)
        self.assertGreaterEqual(device_lifecycle.LIFECYCLE_TIMEOUT_SECONDS, 3 * 60 + 300)
        with patch.object(device_lifecycle.time, "monotonic", return_value=20):
            with self.assertRaises(device_lifecycle.LifecycleError):
                device_lifecycle.remaining_timeout(19, 30)
        now = [0.0]
        def sleep(seconds):
            now[0] += seconds
        def adb(serial, *args, **kwargs):
            return "0"
        with patch.object(device_lifecycle.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(device_lifecycle.time, "sleep", side_effect=sleep):
            with self.assertRaises(device_lifecycle.LifecycleError):
                device_lifecycle.wait_for_reboot("emulator-5556", 540, adb, previous_boot=1)
        self.assertLessEqual(now[0], 300)

    def test_ready_property_without_changed_boot_identity_does_not_prove_reboot(self):
        now = [0.0]
        def sleep(seconds):
            now[0] += seconds
        def adb(serial, *args, **kwargs):
            return "1"
        with patch.object(device_lifecycle.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(device_lifecycle.time, "sleep", side_effect=sleep):
            with self.assertRaises(device_lifecycle.LifecycleError):
                device_lifecycle.wait_for_reboot("emulator-5556", 540, adb, previous_boot=1)
        self.assertEqual(300, now[0])

    def test_post_reboot_device_mismatch_prevents_settings_writes_and_final_phase(self):
        from unittest.mock import Mock
        # Calls: install, seed, force-stop, process, pre-reboot, then post-reboot verification.
        verify = Mock(side_effect=[None, None, None, None, None, device_tests.DeviceTestError("wrong AVD")])
        adb = Mock(return_value="1")
        with patch.object(device_lifecycle, "install_lifecycle_apks"), \
                patch.object(device_lifecycle, "wait_for_reboot"), \
                patch.object(device_lifecycle, "run_phase", side_effect=lambda s, m, d: {
                    "class": CLASS, "name": m, "status": "passed"
                }) as phase:
            with self.assertRaises(device_tests.DeviceTestError):
                device_lifecycle.run_lifecycle("emulator-5556", verify, adb)
        self.assertEqual(2, phase.call_count)
        self.assertFalse(any("put" in call.args for call in adb.call_args_list))


if __name__ == "__main__":
    unittest.main()
