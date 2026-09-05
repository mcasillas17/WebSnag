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
import websnag.elopenmike.com.core.backup.BackupCodec
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.UnlockPolicy
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.Trigger
import websnag.elopenmike.com.core.model.UnlockCondition

/** Synthetic compatibility probes of declared alpha.1 types; not evidence of historical UI reachability. */
class DormantCompatibilityFixtureTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun withMigratedProfile(assertions: suspend (Profile, LocalDataStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = temporary.newFolder().resolve("fixture.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            store.updateData { MigrationFixtures.load("dormant") }
            val local = LocalDataStore(store)
            local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
            assertions(local.profilesFlow.first().first(), local)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun profileTriggersSurviveMigrationAndEncryptedBackup() = withMigratedProfile { profile, local ->
        assertEquals(5, profile.triggers.size)
        assertEquals("synthetic-tag-a", profile.triggers.filterIsInstance<Trigger.NfcTag>().single().tagId)
        val snapshot = local.createBackupSnapshot(true)
        val passphrase = "synthetic dormant fixture passphrase".toCharArray()
        assertEquals(snapshot, BackupCodec.decrypt(BackupCodec.encrypt(snapshot, passphrase), passphrase))
    }

    @Test fun timeScheduleFieldsSurviveWithoutActivatingTheScheduleEngine() = withMigratedProfile { profile, local ->
        assertEquals(Trigger.TimeSchedule("synthetic-time", 1, 2, 3, 4, setOf(1, 3, 5), "Synthetic time"),
            profile.triggers.filterIsInstance<Trigger.TimeSchedule>().single())
        assertEquals(1, local.schedulesFlow.first().size)
        assertFalse(local.schedulesFlow.first().single().isEnabled)
    }

    @Test fun locationFieldsSurviveAsDormantData() = withMigratedProfile { profile, _ ->
        assertEquals(Trigger.Location("synthetic-location", "Synthetic null island", 0.0, 0.0, 42f, "Synthetic location"),
            profile.triggers.filterIsInstance<Trigger.Location>().single())
    }

    @Test fun wifiSsidFieldsSurviveAsDormantData() = withMigratedProfile { profile, _ ->
        assertEquals(Trigger.WifiSsid("synthetic-wifi", "SYNTHETIC_WIFI_ONLY", "Synthetic Wi-Fi"),
            profile.triggers.filterIsInstance<Trigger.WifiSsid>().single())
    }

    @Test fun durationExpiryKeepsItsSpecificTagAndExistingPolicy() = withMigratedProfile { profile, _ ->
        val condition = profile.unlockCondition as UnlockCondition.DurationExpiry
        assertEquals(UnlockCondition.DurationExpiry(31, true, "synthetic-tag-b"), condition)
        assertTrue(UnlockPolicy.canEnd(condition, EndRequest.Nfc("synthetic-tag-b", true)))
        assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Nfc("synthetic-tag-a", true)))
        assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Nfc("synthetic-tag-b", false)))
        assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Manual))
    }
}
