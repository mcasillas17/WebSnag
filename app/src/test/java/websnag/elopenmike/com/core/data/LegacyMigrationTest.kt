package websnag.elopenmike.com.core.data

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.UnlockPolicy
import websnag.elopenmike.com.core.model.UnlockCondition

class LegacyMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tagsKey = stringPreferencesKey("nfc_tags_json")
    private val profilesKey = stringPreferencesKey("profiles_json")

    private fun withStore(seed: Preferences, block: suspend (LocalDataStore, suspend () -> Preferences) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = temporary.newFolder().resolve("fixture.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            store.updateData { seed }
            block(LocalDataStore(store)) { store.data.first() }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun historicalMigrationPreservesMetadataAndSpecificBindings() = withStore(MigrationFixtures.load("alpha1")) { local, raw ->
        local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
        val tags = local.nfcTagsFlow.first()
        assertEquals(2, tags.size)
        assertEquals(1700000000000L, tags[0].createdAtEpochMs)
        assertEquals(1700000060000L, tags[0].lastUsedEpochMs)
        assertEquals("Synthetic \"desk\"\\tag\n雪", tags[0].label)
        assertEquals("Synthetic\ttext \"quoted\" \\ Ω", tags[0].description)
        assertNull(tags[1].customPayload)
        assertNull(tags[1].lastUsedEpochMs)
        val profiles = local.profilesFlow.first()
        assertEquals("synthetic-tag-a", profiles[0].linkedTagId)
        assertEquals("synthetic-tag-b", (profiles[0].unlockCondition as UnlockCondition.RequireNfcTag).requiredTagId)
        assertTrue(profiles[0].isActive)
        assertEquals(1700000100000L, profiles[0].activatedAtEpochMs)
        assertFalse(profiles[1].isActive)
        assertNull(profiles[1].activatedAtEpochMs)
        assertFalse("legacy identity fields must be removed", raw().asMap().values.any {
            it.toString().contains("uidHex") || it.toString().contains("linkedTagUid") || it.toString().contains("requiredTagUid")
        })
    }

    @Test fun mixedCollectionKeepsCurrentRecords() = withStore(MigrationFixtures.load("mixed")) { local, _ ->
        local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
        val tags = local.nfcTagsFlow.first()
        assertEquals(3, tags.size)
        assertEquals("SYNTHETIC_INSTALLATION_FINGERPRINT_0", tags.last().uidFingerprint)
    }

    @Test fun fingerprintFailurePreservesWholeTransaction() = withStore(MigrationFixtures.load("alpha1")) { local, raw ->
        val before = raw()
        var calls = 0
        runCatching { local.migrateLegacyTagIdentifiers(object : TagIdentityProtector {
            override fun fingerprint(rawUid: String): String? = if (++calls == 2) null else MigrationFixtures.protector.fingerprint(rawUid)
        }) }
        assertTrue("failed migration must leave original preferences available", before == raw())
    }

    @Test fun malformedProfilesDoNotCommitTags() {
        val seed = MigrationFixtures.load("alpha1").toMutablePreferences().apply { this[profilesKey] = "{synthetic broken json" }
        withStore(seed) { local, raw ->
            val before = raw()
            runCatching { local.migrateLegacyTagIdentifiers(MigrationFixtures.protector) }
            assertTrue("malformed profiles must roll back related tag changes", before == raw())
        }
    }

    @Test fun unresolvedRequiredUidDoesNotFallBackToDifferentLinkedTag() {
        val seed = MigrationFixtures.load("alpha1").toMutablePreferences()
        val profiles = MigrationFixtures.json.parseToJsonElement(seed[profilesKey]!!).jsonArray.toMutableList()
        val profile = profiles[0].jsonObject.toMutableMap()
        val condition = profile.getValue("unlockCondition").jsonObject.toMutableMap()
        condition["requiredTagUid"] = JsonPrimitive("ABCDEF00")
        profile["unlockCondition"] = JsonObject(condition)
        profiles[0] = JsonObject(profile)
        seed[profilesKey] = JsonArray(profiles).toString()
        withStore(seed) { local, raw ->
            val before = raw()
            val result = runCatching { local.migrateLegacyTagIdentifiers(MigrationFixtures.protector) }
            if (result.isFailure) {
                assertTrue("unresolved migration must preserve original state", before == raw())
            } else {
                val migrated = local.profilesFlow.first().first().unlockCondition
                assertFalse(UnlockPolicy.canEnd(migrated, EndRequest.Nfc("synthetic-tag-a", true)))
            }
        }
    }

    @Test fun currentStateAndSecondMigrationAreByteStable() = withStore(MigrationFixtures.load("alpha2-current")) { local, raw ->
        val before = raw()
        repeat(2) { local.migrateLegacyTagIdentifiers(object : TagIdentityProtector {
            override fun fingerprint(rawUid: String): String = error("current data must not access the key")
        }) }
        assertTrue("current preferences must be byte stable", before == raw())
    }

    @Test fun historicalMigrationIsIdempotent() = withStore(MigrationFixtures.load("alpha1")) { local, raw ->
        local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
        val once = raw()
        local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
        assertTrue("second migration must be byte stable", once == raw())
    }

    @Test fun dormantNfcTriggerMustNotInvalidateActiveProfile() = withStore(MigrationFixtures.load("dormant")) { local, raw ->
        local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
        val active = DefaultProfileRepository(local).activeProfileFlow.first()
        assertNotNull("dormant NFC references must not make the active profile undecodable", active)
        assertEquals(5, active!!.triggers.size)
        assertFalse("legacy trigger identity must be removed", raw()[profilesKey]!!.contains("tagUid"))
    }
    @Test fun ambiguousAndMalformedIdentityInputsAbortWithoutPartialWrites() {
        val source = MigrationFixtures.load("alpha1")
        val entries = MigrationFixtures.json.parseToJsonElement(source[tagsKey]!!).jsonArray
        val badTags = listOf(
            JsonArray(entries + entries[0]),
            JsonArray(entries + JsonObject(entries[0].jsonObject + ("id" to JsonPrimitive("synthetic-duplicate-uid")))),
            JsonArray(listOf(JsonObject(entries[0].jsonObject - "id")) + entries[1]),
            JsonArray(listOf(JsonObject(entries[0].jsonObject + ("uidHex" to JsonPrimitive("")))) + entries[1]),
            JsonArray(listOf(JsonObject(entries[0].jsonObject + ("uidHex" to JsonPrimitive("not hex")))) + entries[1]),
            JsonArray(listOf(JsonObject(entries[0].jsonObject + ("uidHex" to JsonPrimitive(12)))) + entries[1]),
            JsonArray(listOf(JsonObject(entries[0].jsonObject + ("uidHex" to kotlinx.serialization.json.JsonNull))) + entries[1]),
            JsonArray(listOf(JsonPrimitive(1)) + entries[1])
        )
        badTags.forEachIndexed { index, tags ->
            withStore(source.toMutablePreferences().apply { this[tagsKey] = tags.toString() }) { local, raw ->
                val before = raw()
                val outcome = runCatching { local.migrateLegacyTagIdentifiers(MigrationFixtures.protector) }
                assertTrue("invalid identity case $index must fail", outcome.isFailure)
                assertTrue("invalid identity case $index must preserve preferences", before == raw())
            }
        }
    }

    @Test fun thrownKeyFailureIsRetryableAndDoesNotExposePayload() = withStore(MigrationFixtures.load("alpha1")) { local, raw ->
        val before = raw()
        var calls = 0
        val result = runCatching { local.migrateLegacyTagIdentifiers(object : TagIdentityProtector {
            override fun fingerprint(rawUid: String): String? {
                if (++calls == 2) throw IllegalStateException("synthetic key failure")
                return MigrationFixtures.protector.fingerprint(rawUid)
            }
        }) }
        assertTrue(result.isFailure)
        assertTrue("thrown key failure must preserve preferences", before == raw())
        local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
        assertEquals(2, local.nfcTagsFlow.first().size)
    }

    @Test fun legacyReferencesWithoutTagCollectionCannotBecomeUnboundDurationUnlock() {
        val seed = MigrationFixtures.load("dormant").toMutablePreferences().apply { remove(tagsKey) }
        withStore(seed) { local, raw ->
            val before = raw()
            assertTrue(runCatching { local.migrateLegacyTagIdentifiers(MigrationFixtures.protector) }.isFailure)
            assertTrue("missing tags must preserve legacy profile references", before == raw())
        }
    }

    @Test fun historicalUidMatchingIsCaseInsensitive() {
        val seed = MigrationFixtures.load("alpha1").toMutablePreferences().apply {
            this[profilesKey] = this[profilesKey]!!.replace("A0B1C2D3", "a0b1c2d3").replace("D4E5F607", "d4e5f607")
        }
        withStore(seed) { local, _ ->
            local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
            assertEquals("synthetic-tag-b", (local.profilesFlow.first()[0].unlockCondition as UnlockCondition.RequireNfcTag).requiredTagId)
        }
    }

    @Test fun malformedUnknownAndConflictingProfileReferencesAbort() {
        val source = MigrationFixtures.load("alpha1")
        val profiles = MigrationFixtures.json.parseToJsonElement(source[profilesKey]!!).jsonArray
        val active = profiles[0].jsonObject
        val condition = active.getValue("unlockCondition").jsonObject
        val badProfiles = listOf(
            JsonObject(active + ("linkedTagUid" to JsonPrimitive("00112233"))),
            JsonObject(active + ("linkedTagUid" to JsonPrimitive(12))),
            JsonObject(active + ("linkedTagId" to JsonPrimitive("synthetic-conflicting-id"))),
            JsonObject(active + ("unlockCondition" to JsonObject(condition + ("requiredTagUid" to JsonPrimitive(12))))),
            JsonObject(active + ("unlockCondition" to JsonObject(condition + ("requiredTagUid" to JsonPrimitive(""))))),
            JsonObject(active + ("unlockCondition" to JsonObject(condition + ("requiredTagId" to JsonPrimitive("synthetic-conflicting-id"))))),
            JsonObject(active + ("unlockCondition" to JsonPrimitive("synthetic malformed condition")))
        )
        badProfiles.forEachIndexed { index, activeProfile ->
            val seed = source.toMutablePreferences().apply { this[profilesKey] = JsonArray(listOf(activeProfile, profiles[1])).toString() }
            withStore(seed) { local, raw ->
                val before = raw()
                assertTrue("invalid profile reference $index must fail", runCatching { local.migrateLegacyTagIdentifiers(MigrationFixtures.protector) }.isFailure)
                assertTrue("invalid profile reference $index must roll back", before == raw())
            }
        }
    }

    @Test fun legacyNullBindingNeverBecomesAnyEnrolledAuthorization() {
        val source = MigrationFixtures.load("alpha1")
        val profiles = MigrationFixtures.json.parseToJsonElement(source[profilesKey]!!).jsonArray
        val active = profiles[0].jsonObject.toMutableMap().apply {
            this["linkedTagUid"] = kotlinx.serialization.json.JsonNull
            this["unlockCondition"] = JsonObject(profiles[0].jsonObject.getValue("unlockCondition").jsonObject +
                ("requiredTagUid" to kotlinx.serialization.json.JsonNull))
        }
        withStore(source.toMutablePreferences().apply { this[profilesKey] = JsonArray(listOf(JsonObject(active), profiles[1])).toString() }) { local, _ ->
            local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
            val condition = local.profilesFlow.first()[0].unlockCondition as UnlockCondition.RequireNfcTag
            assertNull(condition.requiredTagId)
            assertFalse(condition.allowAnyEnrolledTag)
            assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Nfc("synthetic-tag-b", true)))
            assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Manual))
        }
    }

    @Test fun legacyProfileOnlyReferencesCanResolveAlreadyProtectedTags() = withStore(MigrationFixtures.load("alpha1")) { local, _ ->
        val originalProfiles = MigrationFixtures.load("alpha1")[profilesKey]!!
        local.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
        val migratedTags = local.nfcTagsFlow.first()
        val seed = MigrationFixtures.load("alpha1").toMutablePreferences().apply {
            this[tagsKey] = MigrationFixtures.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(websnag.elopenmike.com.core.model.NfcTagRecord.serializer()), migratedTags)
            this[profilesKey] = originalProfiles
        }
        withStore(seed) { retry, _ ->
            retry.migrateLegacyTagIdentifiers(MigrationFixtures.protector)
            assertEquals("synthetic-tag-b", (retry.profilesFlow.first()[0].unlockCondition as UnlockCondition.RequireNfcTag).requiredTagId)
        }
    }

}
