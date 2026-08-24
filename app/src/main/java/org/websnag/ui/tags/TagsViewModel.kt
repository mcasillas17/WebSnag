package org.websnag.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.websnag.core.data.NfcTagRepository
import org.websnag.core.data.ProfileRepository
import org.websnag.core.model.NfcTagRecord
import org.websnag.core.model.Profile
import org.websnag.core.nfc.NfcManager
import org.websnag.core.nfc.ScannedTag
import java.util.UUID

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
    }

    private suspend fun onTagDiscovered(scanned: ScannedTag) {
        val existing = nfcTagRepository.getTagByUid(scanned.uidHex)
        val defaultLabel = existing?.label ?: "NFC Tag ${scanned.uidHex.takeLast(4)}"
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
                val record = NfcTagRecord(
                    id = current.existingTag?.id ?: UUID.randomUUID().toString(),
                    uidHex = current.tagUid,
                    label = label.ifBlank { current.defaultLabel },
                    customPayload = current.payload,
                    description = description
                )
                nfcTagRepository.saveTag(record)
                _enrollmentState.value = EnrollmentState.Saved
            }
        }
    }

    fun deleteTag(id: String) {
        viewModelScope.launch {
            nfcTagRepository.deleteTag(id)
        }
    }
}
