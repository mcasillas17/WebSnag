package websnag.elopenmike.com.core.backup

import kotlinx.coroutines.flow.first
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository

class BackupRepository(
    private val localDataStore: LocalDataStore,
    private val profileRepository: ProfileRepository
) {
    sealed interface RestoreResult {
        data object Restored : RestoreResult
        data object ActiveLockConflict : RestoreResult
    }

    suspend fun export(passphrase: CharArray, includeHistory: Boolean): ByteArray {
        return BackupCodec.encrypt(localDataStore.createBackupSnapshot(includeHistory), passphrase)
    }

    suspend fun restore(envelope: ByteArray, passphrase: CharArray): RestoreResult {
        val snapshot = BackupCodec.decrypt(envelope, passphrase)
        if (BackupRestorePolicy.check(profileRepository.activeProfileFlow.first(), snapshot) ==
            BackupRestorePolicy.Result.ActiveLockConflict
        ) return RestoreResult.ActiveLockConflict
        return if (localDataStore.replaceFromBackupIfNoActiveProfile(snapshot)) {
            RestoreResult.Restored
        } else {
            RestoreResult.ActiveLockConflict
        }
    }
}
