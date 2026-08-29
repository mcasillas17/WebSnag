package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Test
import websnag.elopenmike.com.core.backup.BackupRestorePolicy
import websnag.elopenmike.com.core.backup.BackupSnapshot
import websnag.elopenmike.com.core.model.Profile

class BackupRestorePolicyTest {

    @Test
    fun rejectsRestoreWhileAnExistingProfileIsActive() {
        val activeProfile = Profile(id = "active", name = "Active", isActive = true)

        val result = BackupRestorePolicy.check(activeProfile, BackupSnapshot())

        assertEquals(BackupRestorePolicy.Result.ActiveLockConflict, result)
    }
}
