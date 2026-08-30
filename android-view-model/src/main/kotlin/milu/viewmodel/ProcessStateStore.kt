package milu.viewmodel

import android.os.Parcelable
import kotlinx.coroutines.flow.Flow

/**
 * Storage boundary for synchronizing [StateViewModel] state across Android processes.
 *
 * Implementations can use DataStore, Room, a ContentProvider, Binder, or another IPC-safe backend.
 * Blocking IO should switch dispatcher inside the implementation. The concrete implementation is
 * also responsible for defining how [State] is encoded for transport or persistence.
 */
public interface ProcessStateStore<State> {
    /** Returns a point-in-time snapshot. Prefer [observe] when synchronizing a ViewModel. */
    public suspend fun read(): ProcessStateRecord<State>?

    public suspend fun write(record: ProcessStateRecord<State>)

    /**
     * Emits the current record, when present, followed by every accepted change.
     *
     * Implementations must register for changes before reading the current record, or provide an
     * equivalent atomic/replaying stream. This prevents an update from being lost between the
     * initial snapshot and observation.
     */
    public fun observe(): Flow<ProcessStateRecord<State>>
}

/**
 * Android IPC specialization whose state can be transported through Binder or a [android.os.Bundle].
 *
 * State implementations normally use `@Parcelize` and implement [Parcelable]. Parcelable is a
 * transport format, not a durable storage format; stores that persist state should use a separately
 * versioned disk encoding.
 */
public interface ParcelableProcessStateStore<State : Parcelable> : ProcessStateStore<State>

/**
 * Versioned state snapshot used by [ProcessStateStore].
 */
public data class ProcessStateRecord<State>(
    val state: State,
    val version: Long,
    val sourceId: String,
)
