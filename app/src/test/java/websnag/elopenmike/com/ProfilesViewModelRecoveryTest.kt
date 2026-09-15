package websnag.elopenmike.com

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.core.data.DefaultProfileRepository
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.ui.profiles.ProfilesViewModel
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun TestScope.withEditor(
        save: suspend (DefaultProfileRepository, Profile) -> Unit,
        assertions: suspend (ProfilesViewModel, DefaultProfileRepository) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var editor: ProfilesViewModel? = null
        try {
            val local = LocalDataStore(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporary.newFolder().resolve("editor.preferences_pb")
            })
            val backing = DefaultProfileRepository(local)
            backing.saveProfile(Profile("synthetic", "Original", linkedTagId = "synthetic-tag",
                unlockCondition = UnlockCondition.RequireNfcTag(
                    requiredTagId = "synthetic-tag", requireIntentionPhrase = false)))
            val repository = object : ProfileRepository by backing {
                override suspend fun saveProfile(profile: Profile) = save(backing, profile)
            }
            val engine = EnforcementEngine(repository, local, backgroundScope)
            val model = ProfilesViewModel(repository, FakeNfcTagRepository(), { emptyList() }, engine)
            editor = model
            model.loadProfileForEditing("synthetic")
            model.editorState.first { it.profileId == "synthetic" }
            model.onNameChanged("My pending edit")
            model.onDescriptionChanged("Keep this text")
            model.onEmergencyCooldownChanged(17)
            assertions(model, backing)
        } finally {
            editor?.viewModelScope?.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun activationDuringSaveReportsErrorAndPreservesEnteredFields() = runTest {
        withEditor(save = { repository, profile ->
            repository.setActiveProfile(profile.id)
            repository.saveProfile(profile)
        }) { model, repository ->
            model.saveProfile()
            runCurrent()
            assertFalse(model.editorState.value.isSaving)
            assertFalse(model.editorState.value.isSaved)
            assertEquals("End the active session before changing this profile.", model.editorState.value.errorMessage)
            assertEquals("My pending edit", model.editorState.value.name)
            assertEquals("Keep this text", model.editorState.value.description)
            assertEquals(17, model.editorState.value.emergencyCooldownMinutes)
            val persisted = repository.readEnforcementSnapshot().activeProfile!!
            assertEquals("Original", persisted.name)
            assertFalse((persisted.unlockCondition as UnlockCondition.RequireNfcTag).requireIntentionPhrase)
        }
    }

    @Test fun storageSaveFailureIsReportedWithoutLosingEdits() = runTest {
        for (failure in listOf(
            IOException("synthetic unavailable storage"),
            SecurityException("synthetic denied storage"),
            SerializationException("synthetic malformed storage"),
            IllegalStateException("synthetic store state error")
        )) {
            withEditor(save = { _, _ -> throw failure }) { model, repository ->
                model.saveProfile()
                runCurrent()
                assertFalse(model.editorState.value.isSaving)
                assertFalse(model.editorState.value.isSaved)
                assertEquals("Could not save the profile. Check saved-data recovery and try again.", model.editorState.value.errorMessage)
                assertEquals("My pending edit", model.editorState.value.name)
                assertEquals("Original", repository.getProfileById("synthetic")!!.name)
            }
        }
    }

    @Test fun cancellationClearsSavingWithoutBecomingAnErrorOrSuccess() = runTest {
        var saveJob: Job? = null
        withEditor(save = { _, _ ->
            saveJob = currentCoroutineContext().job
            throw CancellationException("synthetic cancellation")
        }) { model, _ ->
            model.saveProfile()
            runCurrent()
            assertTrue(saveJob!!.isCancelled)
            assertFalse(model.editorState.value.isSaving)
            assertFalse(model.editorState.value.isSaved)
            assertNull(model.editorState.value.errorMessage)
            assertEquals("My pending edit", model.editorState.value.name)
        }
    }
}
