package websnag.elopenmike.com.core.backup

import websnag.elopenmike.com.core.model.Profile

object BackupRestorePolicy {
    sealed interface Result {
        data object Ready : Result
        data object ActiveLockConflict : Result
    }

    fun check(activeProfile: Profile?, snapshot: BackupSnapshot): Result {
        return if (activeProfile?.isActive == true) Result.ActiveLockConflict else Result.Ready
    }
}
