package websnag.elopenmike.com.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import websnag.elopenmike.com.core.model.NfcTagRecord

/**
 * Repository interface for managing physical NFC tag records.
 */
interface NfcTagRepository {
    val tagsFlow: Flow<List<NfcTagRecord>>

    suspend fun getTags(): List<NfcTagRecord>
    suspend fun getTagByUid(uidHex: String): NfcTagRecord?
    suspend fun saveTag(tag: NfcTagRecord)
    suspend fun deleteTag(id: String)
    suspend fun recordTagUsage(uidHex: String)
}

class DefaultNfcTagRepository(
    private val localDataStore: LocalDataStore
) : NfcTagRepository {

    override val tagsFlow: Flow<List<NfcTagRecord>> = localDataStore.nfcTagsFlow

    override suspend fun getTags(): List<NfcTagRecord> {
        return localDataStore.nfcTagsFlow.first()
    }

    override suspend fun getTagByUid(uidHex: String): NfcTagRecord? {
        return getTags().firstOrNull { it.uidHex.equals(uidHex, ignoreCase = true) }
    }

    override suspend fun saveTag(tag: NfcTagRecord) {
        val current = getTags().toMutableList()
        val index = current.indexOfFirst { it.id == tag.id || it.uidHex.equals(tag.uidHex, ignoreCase = true) }
        if (index >= 0) {
            current[index] = tag
        } else {
            current.add(tag)
        }
        localDataStore.saveNfcTags(current)
    }

    override suspend fun deleteTag(id: String) {
        val current = getTags().filterNot { it.id == id }
        localDataStore.saveNfcTags(current)
    }

    override suspend fun recordTagUsage(uidHex: String) {
        val current = getTags().map { tag ->
            if (tag.uidHex.equals(uidHex, ignoreCase = true)) {
                tag.copy(lastUsedEpochMs = System.currentTimeMillis())
            } else {
                tag
            }
        }
        localDataStore.saveNfcTags(current)
    }
}
