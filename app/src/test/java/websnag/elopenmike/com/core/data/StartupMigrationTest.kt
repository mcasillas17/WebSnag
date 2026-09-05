package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun firstRepositoryReadAndDefaultsWaitForPersistedMigration() = runBlocking {
        val file = temporary.newFolder().resolve("fixture.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seedStore = PreferenceDataStoreFactory.create(scope = seedScope) { file }
        try { seedStore.updateData { MigrationFixtures.load("dormant") } }
        finally { seedScope.coroutineContext[Job]!!.cancelAndJoin() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope,
            migrations = webSnagPreferenceMigrations(MigrationFixtures.protector)) { file }
        try {
            val local = LocalDataStore(store)
            val repository = DefaultProfileRepository(local)
            assertNotNull("first consumer must see the migrated active profile", repository.activeProfileFlow.first())
            repository.initializeDefaultProfilesIfNeeded()
            assertEquals("synthetic-profile-active", repository.activeProfileFlow.first()!!.id)
            assertEquals(2, local.nfcTagsFlow.first().size)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
