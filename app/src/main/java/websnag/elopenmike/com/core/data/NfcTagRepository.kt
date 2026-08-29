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
    suspend fun getTagForUid(rawUid: String): NfcTagRecord?
    suspend fun saveTag(tag: NfcTagRecord)
    suspend fun deleteTag(id: String)
    suspend fun recordTagUsage(tagId: String)
    suspend fun enrollTag(rawUid: String, label: String, customPayload: String?, description: String, existingId: String? = null): NfcTagRecord?
}

class DefaultNfcTagRepository(
    private val localDataStore: LocalDataStore,
    private val tagIdentityProtector: TagIdentityProtector = AndroidKeystoreTagIdentityProtector()
) : NfcTagRepository {

    override val tagsFlow: Flow<List<NfcTagRecord>> = localDataStore.nfcTagsFlow

    override suspend fun getTags(): List<NfcTagRecord> {
        return localDataStore.nfcTagsFlow.first()
    }

    override suspend fun getTagForUid(rawUid: String): NfcTagRecord? {
        val fingerprint = tagIdentityProtector.fingerprint(rawUid) ?: return null
        return getTags().firstOrNull { it.uidFingerprint == fingerprint }
    }

    override suspend fun saveTag(tag: NfcTagRecord) {
        val current = getTags().toMutableList()
        val index = current.indexOfFirst { it.id == tag.id || it.uidFingerprint == tag.uidFingerprint }
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

    override suspend fun recordTagUsage(tagId: String) {
        val current = getTags().map { tag ->
            if (tag.id == tagId) {
                tag.copy(lastUsedEpochMs = System.currentTimeMillis())
            } else {
                tag
            }
        }
        localDataStore.saveNfcTags(current)
    }

    override suspend fun enrollTag(
        rawUid: String,
        label: String,
        customPayload: String?,
        description: String,
        existingId: String?
    ): NfcTagRecord? {
        val fingerprint = tagIdentityProtector.fingerprint(rawUid) ?: return null
        val existing = getTags().firstOrNull { it.id == existingId || it.uidFingerprint == fingerprint }
        val record = NfcTagRecord(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            uidFingerprint = fingerprint,
            label = label,
            customPayload = customPayload,
            description = description,
            createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
            lastUsedEpochMs = existing?.lastUsedEpochMs
        )
        saveTag(record)
        return record
    }
}
