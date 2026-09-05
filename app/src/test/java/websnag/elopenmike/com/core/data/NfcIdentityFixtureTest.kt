package websnag.elopenmike.com.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import websnag.elopenmike.com.core.nfc.NfcTagAction

class NfcIdentityFixtureTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun withStore(block: suspend (LocalDataStore, DataStore<Preferences>) -> Unit) = runBlocking {
        val file = temporary.newFolder().resolve("fixture.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            store.updateData { MigrationFixtures.load("alpha1") }
            val local = LocalDataStore(store)
            local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
            block(local, store)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun currentDuplicateIdentifiersCannotAuthorizeOrRewriteState() = withStore { local, store ->
        val valid = local.nfcTagsFlow.first()
        val profiles = DefaultProfileRepository(local)
        val repository = DefaultNfcTagRepository(local, MigrationFixtures.protector)
        val resolver = NfcActionResolver(profiles, repository)
        val ambiguous = listOf(
            listOf(valid[1], valid[0].copy(id = valid[1].id)),
            listOf(valid[1], valid[1].copy(id = "synthetic-other-id")))
        for (tags in ambiguous) {
            store.updateData { it.toMutablePreferences().apply {
                this[stringPreferencesKey("nfc_tags_json")] = MigrationFixtures.json.encodeToString(tags)
            } }
            val before = store.data.first()
            assertTrue(resolver.resolve("D4E5F607") is NfcTagAction.UnlockRejected)
            assertTrue(resolver.resolve("A0B1C2D3") is NfcTagAction.UnlockRejected)
            assertTrue("ambiguous resolution must preserve stored bytes", before == store.data.first())
        }
    }

    @Test fun tagWritesRejectAmbiguousIdsFingerprintsAndEmptyIdentityWithoutPartialWrites() = withStore { local, store ->
        val valid = local.nfcTagsFlow.first()
        val invalid = listOf(valid + valid[0].copy(uidFingerprint = "SYNTHETIC_OTHER_FINGERPRINT"),
            valid + valid[0].copy(id = "synthetic-other-id"), valid + valid[0].copy(id = ""),
            valid + valid[0].copy(id = "synthetic-empty-fingerprint", uidFingerprint = ""))
        val before = store.data.first()
        invalid.forEach { tags ->
            assertTrue(runCatching { local.saveNfcTags(tags) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue("invalid tag write must preserve state", before == store.data.first())
            val backup = local.createBackupSnapshot(false).copy(tags = tags.map {
                websnag.elopenmike.com.core.backup.BackupTagMetadata(it.id, it.uidFingerprint, it.label, it.createdAtEpochMs)
            })
            assertTrue(runCatching { local.replaceFromBackupIfNoActiveProfile(backup) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue("invalid direct replacement must preserve state", before == store.data.first())
        }
    }
}
