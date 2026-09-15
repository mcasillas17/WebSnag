"""Emergency safety methods must be present, not merely their containing classes."""
import unittest
import device_tests
import test_device_tests

EMERGENCY_CASES = (
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
)


class EmergencyGatesTest(unittest.TestCase):
    def test_every_emergency_safety_class_is_selected_in_smoke_and_full(self):
        self.assertEqual(16, len(device_tests.SMOKE_CLASSES))
        self.assertEqual(19, len(device_tests.FULL_CLASSES))
        self.assertEqual(12, len(device_tests.EMERGENCY_REQUIRED_METHODS))
        for classname, _ in EMERGENCY_CASES:
            for selected in (device_tests.SMOKE_CLASSES, device_tests.FULL_CLASSES):
                self.assertIn(device_tests.PACKAGE + "." + classname, selected)

    def test_each_failed_errored_or_skipped_emergency_method_fails_both_lanes(self):
        helper = test_device_tests.DeviceTestsTest()
        helper.setUp()
        try:
            required = [(device_tests.PACKAGE + "." + c, method, None) for c, method in EMERGENCY_CASES]
            cases = [(c, "syntheticCheck", None) for c in device_tests.FULL_CLASSES]
            cases += [(*device_tests.ACCEPTANCE_TEST, None), (*device_tests.RECOVERY_ACCEPTANCE_TEST, None)]
            for target in required:
                for status in ("failure", "error", "skipped"):
                    for lane in ("smoke", "full"):
                        with self.subTest(target=target, status=status, lane=lane):
                            helper.report(cases + [(c, m, status if (c, m) == target[:2] else None)
                                                   for c, m, _ in required])
                            with self.assertRaisesRegex(device_tests.DeviceTestError, "failures, errors, or skipped"):
                                helper.check(lane)
        finally:
            helper.doCleanups()

    def test_each_missing_emergency_method_fails_both_lanes(self):
        helper = test_device_tests.DeviceTestsTest()
        helper.setUp()
        try:
            required = [(device_tests.PACKAGE + "." + c, method, None) for c, method in EMERGENCY_CASES]
            cases = [(c, "syntheticCheck", None) for c in device_tests.FULL_CLASSES]
            cases += [(*device_tests.ACCEPTANCE_TEST, None), (*device_tests.RECOVERY_ACCEPTANCE_TEST, None)]
            for missing in required:
                for lane in ("smoke", "full"):
                    with self.subTest(missing=missing, lane=lane):
                        helper.report(cases + [case for case in required if case != missing])
                        with self.assertRaisesRegex(device_tests.DeviceTestError, "emergency.*method"):
                            helper.check(lane)
        finally:
            helper.doCleanups()


if __name__ == "__main__":
    unittest.main()
