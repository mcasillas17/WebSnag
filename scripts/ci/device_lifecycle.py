"""Bounded, host-ordered instrumentation for real process termination and reboot."""
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import tempfile
import time

PACKAGE = "websnag.elopenmike.com"
LIFECYCLE_CLASS = PACKAGE + ".core.data.EmergencyRecoveryLifecycleTest"
PHASE_METHODS = ("seedRecovery", "verifyProcessRestoration", "verifyRebootRestoration")
PHASE_TIMEOUT_SECONDS = 60
INSTALL_TIMEOUT_SECONDS = 30
REBOOT_TIMEOUT_SECONDS = 300
# Shared budget, including both APK installs and management commands. Per-step caps never reset it.
LIFECYCLE_TIMEOUT_SECONDS = 540
MAX_PHASE_OUTPUT_BYTES = 128 * 1024
ROOT = Path(__file__).resolve().parents[2]


class LifecycleError(RuntimeError):
    """Fixed, non-sensitive lifecycle failure labels, never raw instrumentation output."""


def phase_result(method, status="passed"):
    return {"class": LIFECYCLE_CLASS, "name": method, "status": status}


def parse_phase_output(output, method):
    """Require AndroidJUnitRunner's exact single-test start, success and terminal report."""
    if method not in PHASE_METHODS or len(output.encode("utf-8")) > MAX_PHASE_OUTPUT_BYTES:
        raise LifecycleError("Invalid or oversized lifecycle phase output.")
    statuses, bundle, terminal = [], {}, []
    result_seen = False
    for line in output.splitlines():
        if line.startswith(("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")):
            raise LifecycleError("Lifecycle instrumentation did not complete.")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if result_seen or terminal:
                raise LifecycleError("Lifecycle status arrived after its final result.")
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if not separator or key in bundle:
                raise LifecycleError("Malformed or duplicate lifecycle status field.")
            bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            if result_seen or terminal:
                raise LifecycleError("Lifecycle status arrived after its final result.")
            value = line.removeprefix("INSTRUMENTATION_STATUS_CODE: ")
            if not re.fullmatch(r"-?[0-9]{1,2}", value):
                raise LifecycleError("Malformed lifecycle status code.")
            statuses.append((int(value), bundle))
            bundle = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if not result_seen or bundle:
                raise LifecycleError("Lifecycle terminal report arrived out of order.")
            terminal.append(line.removeprefix("INSTRUMENTATION_CODE: "))
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            # In particular, shortMsg/longMsg indicate an instrumentation startup/process crash.
            if (result_seen or terminal or bundle or len(statuses) != 2 or
                    not line.startswith("INSTRUMENTATION_RESULT: stream=")):
                raise LifecycleError("Lifecycle instrumentation reported an unexpected result.")
            result_seen = True
        elif line.startswith("INSTRUMENTATION_"):
            raise LifecycleError("Unrecognized lifecycle instrumentation report.")
    expected = {"class": LIFECYCLE_CLASS, "test": method, "current": "1",
                "numtests": "1", "id": "AndroidJUnitRunner"}
    if (bundle or terminal != ["-1"] or len(statuses) != 2 or
            [code for code, _ in statuses] != [1, 0] or
            len(re.findall(r"(?m)^OK \(1 test\)\s*$", output)) != 1 or
            not output.rstrip().endswith("INSTRUMENTATION_CODE: -1")):
        raise LifecycleError("Missing, duplicate or unsuccessful lifecycle result.")
    for _, fields in statuses:
        if ({key: fields.get(key) for key in expected} != expected or
                set(fields) - set(expected) - {"stream"}):
            raise LifecycleError("Lifecycle identity, counters or status fields did not match.")
    return phase_result(method)


def remaining_timeout(deadline, maximum):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise LifecycleError("Lifecycle deadline exceeded.")
    return min(maximum, remaining)


def phase_command(serial, method):
    if method not in PHASE_METHODS:
        raise LifecycleError("Unknown lifecycle method.")
    return ["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", LIFECYCLE_CLASS + "#" + method,
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]


def write_private_diagnostic(label, output):
    """Keep bounded raw failure detail private and outside every repository/upload artifact."""
    try:
        directory = Path(os.environ.get("WEBSNAG_DEVICE_DIAGNOSTICS_DIR", tempfile.gettempdir())).resolve()
        repository = ROOT.resolve()
        if directory == repository or repository in directory.parents:
            raise OSError("Diagnostics must be outside the repository.")
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        label = re.sub(r"[^A-Za-z0-9_-]", "_", label)[:60]
        descriptor, path = tempfile.mkstemp(prefix=f"websnag-{label}-", suffix=".log", dir=directory)
        content = output if isinstance(output, (bytes, bytearray)) else str(output or "No output was captured.").encode("utf-8")
        with os.fdopen(descriptor, "wb") as destination:
            destination.write(content[:MAX_PHASE_OUTPUT_BYTES])
        # mkstemp creates mode 0600. Only this location, never raw contents, reaches the console.
        print(f"Private lifecycle diagnostic: {path}", file=sys.stderr, flush=True)
    except (OSError, RuntimeError):
        print("Private lifecycle diagnostic could not be saved.", file=sys.stderr, flush=True)


def install_lifecycle_apks(serial, root, deadline, adb):
    """AGP can uninstall after connected tests; install this build once before seeding state."""
    apks = (
        Path(root) / "app/build/outputs/apk/debug/app-debug.apk",
        Path(root) / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    )
    # Validate both first, so a missing test APK cannot cause a partial installation.
    if any(path.is_symlink() or not path.is_file() or path.stat().st_size == 0 for path in apks):
        raise LifecycleError("Missing or invalid built lifecycle APK.")
    for path in apks:
        try:
            output = adb(serial, "install", "-r", "-t", str(path),
                         timeout=remaining_timeout(deadline, INSTALL_TIMEOUT_SECONDS))
        except (subprocess.SubprocessError, OSError) as failure:
            write_private_diagnostic("install-" + path.stem, getattr(failure, "output", None) or str(failure))
            raise LifecycleError("Lifecycle APK installation failed or timed out.") from None
        if output.splitlines()[-1:] != ["Success"]:
            write_private_diagnostic("install-" + path.stem, output)
            raise LifecycleError("Lifecycle APK installation did not report success.")


def run_phase(serial, method, deadline):
    remaining_timeout(deadline, PHASE_TIMEOUT_SECONDS)
    phase_deadline = min(deadline, time.monotonic() + PHASE_TIMEOUT_SECONDS)
    # Reserve a little of the same phase budget to reap our client on timeout/cancellation.
    io_deadline = phase_deadline - min(1, remaining_timeout(phase_deadline, PHASE_TIMEOUT_SECONDS) / 10)
    output = bytearray()
    # Drain incrementally: a crashing/noisy test cannot consume unbounded host memory or disk.
    process = subprocess.Popen(phase_command(serial, method), stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT)
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                if not selector.select(remaining_timeout(io_deadline, PHASE_TIMEOUT_SECONDS)):
                    raise LifecycleError("Lifecycle phase timed out.")
                chunk = os.read(process.stdout.fileno(), 16 * 1024)
                if not chunk:
                    break
                output.extend(chunk)
                if len(output) > MAX_PHASE_OUTPUT_BYTES:
                    raise LifecycleError("Lifecycle phase output exceeded its bound.")
        if process.wait(timeout=remaining_timeout(io_deadline, PHASE_TIMEOUT_SECONDS)) != 0:
            raise LifecycleError("Lifecycle instrumentation command failed.")
        try:
            return parse_phase_output(output.decode("utf-8"), method)
        except UnicodeError:
            raise LifecycleError("Lifecycle instrumentation output was not UTF-8.") from None
    except (LifecycleError, subprocess.SubprocessError, OSError):
        write_private_diagnostic(method, output)
        raise
    finally:
        if process.poll() is None:
            # This is our adb client, not the shared adb server or another device's process.
            process.kill()
        try:
            process.wait(timeout=max(0, phase_deadline - time.monotonic()))
        finally:
            process.stdout.close()


def wait_for_reboot(serial, deadline, adb, previous_boot):
    boot_deadline = min(deadline, time.monotonic() + REBOOT_TIMEOUT_SECONDS)
    while True:
        try:
            ready = adb(serial, "shell", "getprop", "sys.boot_completed",
                        timeout=remaining_timeout(boot_deadline, 5))
            if ready == "1":
                boot = adb(serial, "shell", "settings", "get", "global", "boot_count",
                           timeout=remaining_timeout(boot_deadline, 5))
                if re.fullmatch(r"[0-9]{1,10}", boot) and previous_boot < int(boot) <= 2_147_483_647:
                    return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            # Offline/adbd restarts are expected only inside this bounded boot wait.
            pass
        time.sleep(remaining_timeout(boot_deadline, 2))


def run_lifecycle(serial, verify_device, adb, on_result=lambda result: None):
    deadline = time.monotonic() + LIFECYCLE_TIMEOUT_SECONDS
    results = []

    def verify():
        verify_device(serial, deadline=deadline)

    def command(*args):
        return adb(serial, *args, timeout=remaining_timeout(deadline, 30))

    def phase(method):
        verify()
        try:
            result = run_phase(serial, method, deadline)
            remaining_timeout(deadline, PHASE_TIMEOUT_SECONDS)
        except (LifecycleError, subprocess.SubprocessError, OSError):
            on_result(phase_result(method, "unverified"))
            raise
        if result != phase_result(method):
            raise LifecycleError("Unexpected lifecycle phase identity or result.")
        results.append(result)
        on_result(result)

    verify()
    install_lifecycle_apks(serial, ROOT, deadline, adb)
    phase(PHASE_METHODS[0])
    verify()
    command("shell", "am", "force-stop", PACKAGE)
    phase(PHASE_METHODS[1])
    previous_boot = command("shell", "settings", "get", "global", "boot_count")
    if not re.fullmatch(r"[0-9]{1,10}", previous_boot) or int(previous_boot) > 2_147_483_647:
        raise LifecycleError("Device boot identity is unavailable.")
    verify()
    command("reboot")
    wait_for_reboot(serial, deadline, adb, int(previous_boot))
    verify()
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        command("shell", "settings", "put", "global", setting, "0")
    command("shell", "settings", "put", "secure", "spell_checker_enabled", "0")
    phase(PHASE_METHODS[2])
    return results
