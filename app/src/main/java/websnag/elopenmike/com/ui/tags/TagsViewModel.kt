package websnag.elopenmike.com.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.NfcTagRepository
import websnag.elopenmike.com.core.data.ActiveSessionMutationException
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.nfc.NfcManager
import websnag.elopenmike.com.core.nfc.ScannedTag
import java.io.IOException

sealed interface EnrollmentState {
    data object ReadyToScan : EnrollmentState
    data class TagDetected(
        val tagUid: String,
        val defaultLabel: String,
        val existingTag: NfcTagRecord? = null,
        val payload: String? = null
    ) : EnrollmentState
    data object Saved : EnrollmentState
}

class TagsViewModel(
    private val nfcTagRepository: NfcTagRepository,
    private val profileRepository: ProfileRepository,
    private val nfcManager: NfcManager
) : ViewModel() {

    val tags: StateFlow<List<NfcTagRecord>> = nfcTagRepository.tagsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profiles: StateFlow<List<Profile>> = profileRepository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.ReadyToScan)
    val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // Observe scanned tags during enrollment
        viewModelScope.launch {
            nfcManager.scannedTagFlow.collect { scanned ->
                onTagDiscovered(scanned)
            }
        }
    }

    fun resetEnrollment() {
        _enrollmentState.value = EnrollmentState.ReadyToScan
        _errorMessage.value = null
    }

    private suspend fun onTagDiscovered(scanned: ScannedTag) {
        val existing = nfcTagRepository.getTagForUid(scanned.uidHex)
        val defaultLabel = existing?.label ?: "NFC Tag ${tags.value.size + 1}"
        _enrollmentState.value = EnrollmentState.TagDetected(
            tagUid = scanned.uidHex,
            defaultLabel = defaultLabel,
            existingTag = existing,
            payload = scanned.customPayload
        )
    }

    fun saveEnrolledTag(label: String, description: String = "") {
        val current = _enrollmentState.value
        if (current is EnrollmentState.TagDetected) {
            viewModelScope.launch {
                try {
                    val enrolled = nfcTagRepository.enrollTag(
                        rawUid = current.tagUid,
                        label = label.ifBlank { current.defaultLabel },
                        customPayload = current.payload,
                        description = description,
                        existingId = current.existingTag?.id
                    )
                    if (enrolled != null) {
                        _errorMessage.value = null
                        _enrollmentState.value = EnrollmentState.Saved
                    } else {
                        _errorMessage.value = "Tag could not be enrolled. Scan a valid tag and check device key availability."
                    }
                } catch (failure: ActiveSessionMutationException) {
                    _errorMessage.value = failure.message
                } catch (_: IOException) {
                    _errorMessage.value = "Saved tags could not be updated."
                }
            }
        }
    }

    fun deleteTag(id: String) {
        viewModelScope.launch {
            try {
                nfcTagRepository.deleteTag(id)
                _errorMessage.value = null
            } catch (failure: ActiveSessionMutationException) {
                _errorMessage.value = failure.message
            } catch (_: IOException) {
                _errorMessage.value = "Saved tags could not be updated."
            }
        }
    }
}
