package websnag.elopenmike.com.core.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Every failed read advances generation, fencing older reads even while already failed. Episode
 * advances only on entry to failure, so repeated failures do not invalidate a deliberate pause.
 */
data class StorageRecoveryState(
    val generation: Long = 0,
    val required: Boolean = false,
    val episode: Long = 0
)

/**
 * Read-only projection with a live value, not an asynchronously cached mirror. Changes only wake
 * collectors; each emission and direct value read derives from the current authoritative inputs.
 */
internal fun <T> currentStateFlow(changes: Flow<*>, read: () -> T): StateFlow<T> =
    object : StateFlow<T> {
        override val value: T get() = read()
        override val replayCache: List<T> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            changes.map { value }.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
