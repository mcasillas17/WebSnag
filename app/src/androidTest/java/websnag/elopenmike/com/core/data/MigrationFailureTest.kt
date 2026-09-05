package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MigrationFailureTest {
    private lateinit var harness: MigrationStoreHarness
    private val good = object : TagIdentityProtector {
        override fun fingerprint(rawUid: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(rawUid.trim().uppercase().toByteArray()))
    }
    @Before fun setup() { harness = MigrationStoreHarness() }
    @After fun cleanup() = runBlocking { if (::harness.isInitialized) harness.close() }

    @Test fun nullAndThrownFingerprintFailuresKeepPersistedStateForRetry() = runBlocking {
        for (throwFailure in listOf(false, true)) {
            harness.seed("mixed")
            val before = harness.raw()
            var calls = 0
            val failing = object : TagIdentityProtector {
                override fun fingerprint(rawUid: String): String? {
                    if (++calls % 2 == 0) {
                        if (throwFailure) error("synthetic key unavailable")
                        return null
                    }
                    return good.fingerprint(rawUid)
                }
            }
            harness.open(webSnagPreferenceMigrations(failing))
            val local = harness.local
            val repository = DefaultProfileRepository(local)
            val result = runCatching { repository.initializeDefaultProfilesIfNeeded() }
            assertTrue("initialization must refuse an incomplete migration", result.exceptionOrNull() is LegacyTagMigrationException)
            val resolver = NfcActionResolver(repository, DefaultNfcTagRepository(local, failing))
            assertTrue("failed startup must not emit an unlock action", runCatching { resolver.resolve("D4E5F607") }.isFailure)
            harness.open()
            assertTrue("a failed initialization must preserve the original file", before == harness.raw())
            harness.open(webSnagPreferenceMigrations(good))
            assertEquals(3, harness.local.nfcTagsFlow.first().size)
            assertNotNull(DefaultProfileRepository(harness.local).activeProfileFlow.first())
        }
    }

    @Test fun malformedRelatedCollectionRollsBackOnDisk() = runBlocking {
        harness.seed("alpha1")
        harness.store.updateData { it.toMutablePreferences().apply {
            this[stringPreferencesKey("profiles_json")] = "{synthetic malformed profile"
        } }
        val before = harness.raw()
        harness.open(webSnagPreferenceMigrations(good))
        assertTrue(runCatching { harness.raw() }.exceptionOrNull() is LegacyTagMigrationException)
        harness.open()
        assertTrue("tag conversion cannot commit ahead of profile validation", before == harness.raw())
    }

    @Test fun concurrentConsumersCannotSeePremigrationStateOrSeedDefaults() = runBlocking {
        harness.seed("dormant")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : TagIdentityProtector {
            override fun fingerprint(rawUid: String): String? {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "synthetic migration gate timed out" }
                return good.fingerprint(rawUid)
            }
        }
        harness.open(webSnagPreferenceMigrations(blocking))
        val local = harness.local
        val repository = DefaultProfileRepository(local)
        try {
            withTimeout(30_000) {
                val reader = async(Dispatchers.IO) { repository.activeProfileFlow.first() }
                val defaults = async(Dispatchers.IO) { repository.initializeDefaultProfilesIfNeeded() }
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                assertFalse("profile reader must wait for the migration transaction", reader.isCompleted)
                assertFalse("default writer must wait for the migration transaction", defaults.isCompleted)
                release.countDown()
                assertEquals("synthetic-profile-active", reader.await()!!.id)
                defaults.await()
                assertEquals(2, local.profilesFlow.first().size)
            }
        } finally { release.countDown() }
    }
}
