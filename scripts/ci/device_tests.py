"""Run synthetic Android instrumentation on a disposable WebSnag test emulator."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import xml.etree.ElementTree as ET
from device_lifecycle import (
    LIFECYCLE_CLASS, PHASE_METHODS, LifecycleError, phase_result,
    remaining_timeout, run_lifecycle,
)


PACKAGE = "websnag.elopenmike.com"
SMOKE_CLASSES = tuple(PACKAGE + "." + name for name in (
    "AndroidKeystoreActivitySignerTest",
    "ComponentHardeningTest",
    "DiagnosticsRepositoryTest",
    "PrivacyManifestTest",
    "RemediationSettingsIntentFactoryTest",
    "StorageRecoveryScreenTest",
    "EmergencyRecoveryScreenTest",
    "EmergencyRecoveryActivityTest",
    "core.data.EmergencyRecoveryDeviceTest",
    "core.data.BackupRestoreFixtureTest",
    "core.data.MigrationEnforcementAcceptanceTest",
    "core.data.MigrationFailureTest",
    "core.data.PersistedStateFixtureTest",
    "core.data.ScheduleBackupConsistencyTest",
    "core.data.UpgradeMigrationTest",
    "core.schedule.ScheduleReceiverActionTest",
))
FULL_CLASSES = SMOKE_CLASSES + tuple(PACKAGE + "." + name for name in (
    "ActivityScreenTest",
    "ActivitySelectionStateTest",
    "DiagnosticsScreenTest",
))
ACCEPTANCE_TEST = (
    PACKAGE + ".core.data.MigrationEnforcementAcceptanceTest",
    "failedMigrationMustNotSilentlyDisableRuntimeBlocking",
)
RECOVERY_ACCEPTANCE_TEST = (
    ACCEPTANCE_TEST[0],
    "approvedRecoveryRestartsTheIntendedSessionWithoutWeakeningIt",
)
EMERGENCY_REQUIRED_METHODS = tuple((PACKAGE + "." + classname, method) for classname, method in (
    ("EmergencyRecoveryScreenTest", "configuredOptionalPhraseStartsWithoutConfirmation"),
    ("EmergencyRecoveryScreenTest", "requiredPhraseAndDisabledRecoveryRemainGated"),
    ("EmergencyRecoveryScreenTest", "authoritativeCountdownAndReplacementResetPhrase"),
    ("EmergencyRecoveryScreenTest", "profileSaveRaceShowsAnErrorAndKeepsTheEditorOpen"),
    ("EmergencyRecoveryActivityTest", "dashboardLostTagRecoveryIsReachableWithEmptyBlocklist"),
    ("EmergencyRecoveryActivityTest", "overlayActivityRecreationKeepsPersistedRecoveryWithoutRestarting"),
    ("EmergencyRecoveryActivityTest", "editingAnOptionalPhraseProfilePreservesItsPolicy"),
    ("EmergencyRecoveryActivityTest", "legacyDialogsKeepCountdownVisibleWhenTheSessionUuidIsBound"),
    ("core.data.EmergencyRecoveryDeviceTest", "durableCompletionAtExactBoundarySurvivesStoreReopen"),
    ("core.data.EmergencyRecoveryDeviceTest", "processStyleCloseReopenRetainsElapsedProgressAndRequestIdentity"),
    ("core.data.EmergencyRecoveryDeviceTest", "rebootAndUnavailableBootIdentityRestartFullPersistedWait"),
    ("core.data.EmergencyRecoveryDeviceTest", "releasedFourFieldFixtureRemainsReadableAndCannotInventPhraseConfirmation"),
))
MAX_REPORT_BYTES = 10 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / "app/build/outputs/androidTest-results/connected"


class DeviceTestError(RuntimeError):
    pass


def run(command, timeout):
    # A separate group lets timeout/cancellation stop Gradle and its children, not other builds.
    process = subprocess.Popen(command, start_new_session=True)
    try:
        code = process.wait(timeout=timeout)
        if code:
            raise subprocess.CalledProcessError(code, command)
    finally:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            finally:
                os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def adb(serial, *arguments, timeout=30):
    return subprocess.check_output(
        ["adb", "-s", serial, *arguments], text=True, timeout=timeout, stderr=subprocess.STDOUT
    ).strip()


def verify_device(serial, deadline=None):
    if not re.fullmatch(r"emulator-\d+", serial):
        raise DeviceTestError("Set ANDROID_SERIAL to the dedicated emulator; physical devices are refused.")
    def query(*arguments):
        return adb(serial, *arguments, timeout=remaining_timeout(deadline, 30)) if deadline is not None else adb(serial, *arguments)
    if (query("get-state") != "device" or
            query("shell", "getprop", "ro.kernel.qemu") != "1" or
            query("emu", "avd", "name").splitlines()[:1] != ["websnag-ci-api36"] or
            query("shell", "getprop", "ro.build.version.sdk") != "36"):
        raise DeviceTestError("Expected a booted, disposable websnag-ci-api36 AVD on API 36.")


def uninstall_test_packages(serial):
    installed = adb(serial, "shell", "pm", "list", "packages").splitlines()
    for package in (PACKAGE + ".test", PACKAGE):
        if f"package:{package}" in installed:
            if adb(serial, "uninstall", package) != "Success":
                raise DeviceTestError("Could not remove a synthetic test installation.")


def gradle_command(suite):
    command = [
        "./gradlew", ":app:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=60000",
        # These three methods run once each below, around real host-controlled lifecycle events.
        "-Pandroid.testInstrumentationRunnerArguments.notClass=" + LIFECYCLE_CLASS,
        "--rerun-tasks", "--no-build-cache", "--no-configuration-cache", "--no-daemon",
    ]
    if suite == "smoke":
        command.append("-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(SMOKE_CLASSES))
    return command


def check_reports(reports, output, suite, pending_lifecycle=False):
    """Validate ordinary JUnit evidence; pending lifecycle phases cannot produce an overall pass."""
    summary = {"suite": suite, "status": "failed", "executed": 0, "tests": []}
    if pending_lifecycle:
        summary["lifecycle"] = [phase_result(method, "not_run") for method in PHASE_METHODS]
    try:
        paths = sorted(reports.rglob("TEST-*.xml"))
        if not paths or len(paths) > 100:
            raise DeviceTestError("Missing or excessive JUnit reports.")
        seen = set()
        for path in paths:
            if path.is_symlink() or path.stat().st_size > MAX_REPORT_BYTES:
                raise DeviceTestError("Unsafe or oversized JUnit report.")
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                raise DeviceTestError("Malformed JUnit report.") from None
            if root.tag not in ("testsuite", "testsuites"):
                raise DeviceTestError("Expected JUnit testsuite or testsuites.")
            cases = list(root.iter("testcase"))
            for case in cases:
                classname, name = case.get("classname", ""), case.get("name", "")
                if (not classname.startswith(PACKAGE + ".") or
                        not re.fullmatch(r"[A-Za-z0-9_.]{1,200}", classname) or
                        not re.fullmatch(r"[A-Za-z0-9_]{1,200}(?:\[\d+\])?", name)):
                    raise DeviceTestError("Unexpected test identity in JUnit report.")
                identity = (classname, name)
                if classname == LIFECYCLE_CLASS:
                    raise DeviceTestError("Host-ordered lifecycle methods ran outside their ordered phases.")
                if identity in seen or len(seen) >= 1000:
                    raise DeviceTestError("Duplicate or excessive JUnit test cases.")
                seen.add(identity)
                status = "passed"
                for tag in ("failure", "error", "skipped"):
                    if case.find(tag) is not None:
                        status = tag
                summary["executed"] += status != "skipped"
                summary["tests"].append({"class": classname, "name": name, "status": status})
            for node in root.iter():
                if node.tag in ("testsuite", "testsuites"):
                    children = list(node.iter("testcase"))
                    counts = {"tests": len(children)}
                    for tag, counter in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                        counts[counter] = sum(case.find(tag) is not None for case in children)
                    for counter, actual in counts.items():
                        value = node.get(counter, "0")
                        if not value.isdecimal() or int(value) != actual:
                            raise DeviceTestError("JUnit counters disagree with test cases.")
        required = SMOKE_CLASSES if suite == "smoke" else FULL_CLASSES
        if not summary["executed"]:
            raise DeviceTestError("No tests executed.")
        if not set(required).issubset({classname for classname, _ in seen}):
            raise DeviceTestError("A required test class did not execute.")
        if not {ACCEPTANCE_TEST, RECOVERY_ACCEPTANCE_TEST}.issubset(seen):
            raise DeviceTestError("A migration runtime acceptance method did not execute.")
        if not set(EMERGENCY_REQUIRED_METHODS).issubset(seen):
            raise DeviceTestError("An emergency recovery safety method did not execute.")
        if any(case["status"] != "passed" for case in summary["tests"]):
            raise DeviceTestError("Instrumentation reported failures, errors, or skipped tests.")
        if not pending_lifecycle:
            summary["status"] = "passed"
    except DeviceTestError as error:
        summary["error"] = str(error)
        raise
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        # Do not export XML properties, assertion payloads, stdout, logcat, or app files.
        output.write_text(json.dumps(summary, indent=2) + "\n")
        print(f"Device {suite}: {summary['executed']} executed; {summary['status']}", flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", choices=("smoke", "full"))
    args = parser.parse_args()
    os.chdir(ROOT)
    output = ROOT / f"app/build/device-tests/{args.suite}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"suite": args.suite, "status": "failed", "executed": 0,
                                  "error": "Device run did not complete.",
                                  "lifecycle": [phase_result(method, "not_run") for method in PHASE_METHODS]}) + "\n")
    serial = os.environ.get("ANDROID_SERIAL", "")
    try:
        verify_device(serial)
        # These are generated connected-test outputs, never application preferences or SDK files.
        if REPORTS.exists():
            shutil.rmtree(REPORTS)
        try:
            uninstall_test_packages(serial)
            try:
                run(gradle_command(args.suite), timeout=720 if args.suite == "smoke" else 1080)
            finally:
                summary = check_reports(REPORTS, output, args.suite, pending_lifecycle=True)
            ordinary_tests = list(summary["tests"])
            ordinary_count = summary["executed"]

            def record_phase(result):
                method = result.get("name")
                if (method not in PHASE_METHODS or result.get("status") not in ("passed", "unverified") or
                        result != phase_result(method, result["status"])):
                    raise DeviceTestError("Unexpected ordered lifecycle phase metadata.")
                summary["lifecycle"][PHASE_METHODS.index(method)] = result
                passed = [case for case in summary["lifecycle"] if case["status"] == "passed"]
                summary["tests"] = ordinary_tests + passed
                summary["executed"] = ordinary_count + len(passed)
                # Never publish a passed artifact before the entire ordered sequence completes.
                output.write_text(json.dumps(summary, indent=2) + "\n")

            phases = run_lifecycle(serial, verify_device, adb, on_result=record_phase)
            if phases != [phase_result(method) for method in PHASE_METHODS]:
                raise DeviceTestError("Missing, out-of-order or unsuccessful ordered lifecycle methods.")
            for phase in phases:
                record_phase(phase)
        finally:
            uninstall_test_packages(serial)
        summary["status"] = "passed"
        output.write_text(json.dumps(summary, indent=2) + "\n")
        print(f"Device {args.suite}: {summary['executed']} executed; passed (including real lifecycle)", flush=True)
    except (DeviceTestError, LifecycleError, subprocess.SubprocessError, OSError, KeyboardInterrupt) as error:
        summary = json.loads(output.read_text())
        summary["status"] = "failed"
        summary["error"] = str(error) if isinstance(error, (DeviceTestError, LifecycleError)) else type(error).__name__
        output.write_text(json.dumps(summary, indent=2) + "\n")
        raise


def interrupted(signum, frame):
    raise InterruptedError("Device test run cancelled.")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except (DeviceTestError, LifecycleError, subprocess.SubprocessError, OSError, KeyboardInterrupt) as error:
        print(f"Device test gate failed: {type(error).__name__}. See device summary and task output.", file=sys.stderr)
        sys.exit(1)
