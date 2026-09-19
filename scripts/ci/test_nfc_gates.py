"""Combined authorization coverage cannot disappear behind a passing class result."""
import re
import unittest

import device_tests
import test_device_tests


class NfcGatesTest(unittest.TestCase):
    def test_every_method_in_new_safety_classes_is_guarded(self):
        for classname in ("core.data.NfcAuthorizationDeviceTest", "core.data.ProtectedMutationDeviceTest",
                          "NfcAuthorizationActivityTest", "EmergencyRecoveryActivityTest",
                          "EmergencyRecoveryScreenTest"):
            path = device_tests.ROOT / "app/src/androidTest/java" / (
                device_tests.PACKAGE.replace(".", "/") + "/" + classname.replace(".", "/") + ".kt")
            methods = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", path.read_text()))
            self.assertTrue(methods, classname)
            required = {method for name, method in device_tests.SAFETY_REQUIRED_METHODS
                        if name == device_tests.PACKAGE + "." + classname}
            self.assertEqual(methods, required, classname)

    def test_missing_failed_errored_or_skipped_nfc_method_fails_both_lanes(self):
        helper = test_device_tests.DeviceTestsTest()
        helper.setUp()
        self.addCleanup(helper.doCleanups)
        base = [(name, "syntheticCheck", None) for name in device_tests.FULL_CLASSES]
        base += [(*device_tests.ACCEPTANCE_TEST, None), (*device_tests.RECOVERY_ACCEPTANCE_TEST, None)]
        for target in device_tests.NFC_REQUIRED_METHODS:
            for status in ("missing", "failure", "error", "skipped"):
                for lane in ("smoke", "full"):
                    with self.subTest(target=target, status=status, lane=lane):
                        cases = [(*method, status if method == target else None)
                                 for method in device_tests.SAFETY_REQUIRED_METHODS
                                 if not (method == target and status == "missing")]
                        helper.report(base + cases)
                        with self.assertRaisesRegex(device_tests.DeviceTestError,
                                                    "NFC.*method|failures, errors, or skipped"):
                            helper.check(lane)


if __name__ == "__main__":
    unittest.main()
