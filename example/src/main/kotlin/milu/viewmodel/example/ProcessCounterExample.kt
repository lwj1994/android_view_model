package milu.viewmodel.example

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import milu.viewmodel.ParcelableProcessStateStore
import milu.viewmodel.ProcessStateRecord
import milu.viewmodel.StateViewModel
import milu.viewmodel.ViewModelBindingProvider
import milu.viewmodel.rememberRetainedViewModelBinding
import milu.viewmodel.viewModelSpec
import milu.viewmodel.watchViewModel

@Parcelize
data class ProcessCounterState(
    val count: Int = 0,
) : Parcelable

class ProcessCounterViewModel(
    context: Context,
) : StateViewModel<ProcessCounterState>(
    initialState = ProcessCounterState(),
    equals = { previous, current -> previous == current },
    processStateStore = ContentProviderProcessStateStore(context.applicationContext),
) {
    fun increment() {
        setState(state.copy(count = state.count + 1))
    }

    fun reset() {
        setState(ProcessCounterState())
    }
}

/**
 * Runs in the app's `:remote` process. It owns a different ViewModel instance from MainActivity,
 * while both instances synchronize state through ProcessCounterStateProvider.
 */
class RemoteProcessActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ViewModelBindingProvider(binding = rememberRetainedViewModelBinding()) {
                    Surface {
                        ProcessCounterPanel(showOpenRemoteButton = false)
                    }
                }
            }
        }
    }
}

@Composable
fun ProcessCounterPanel(showOpenRemoteButton: Boolean) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val spec = remember(appContext) {
        viewModelSpec(key = "process-counter") {
            ProcessCounterViewModel(appContext)
        }
    }
    val counter by watchViewModel(spec)
    val processLabel = if (showOpenRemoteButton) "main" else ":remote"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Cross-process count: ${counter.state.count}",
            style = MaterialTheme.typography.h6,
        )
        Text(text = "Activity process: $processLabel (pid ${android.os.Process.myPid()})")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { counter.increment() }) {
                Text("Process +1")
            }
            Button(onClick = { counter.reset() }) {
                Text("Reset")
            }
            if (showOpenRemoteButton) {
                Button(
                    onClick = {
                        context.startActivity(Intent(context, RemoteProcessActivity::class.java))
                    },
                ) {
                    Text("Open :remote")
                }
            }
        }
    }
}

/**
 * Parcelable transport implemented with ContentResolver.call and ContentObserver.
 * Resolver calls execute inside the provider's dedicated `:state_store` process.
 */
private class ContentProviderProcessStateStore(
    context: Context,
) : ParcelableProcessStateStore<ProcessCounterState> {
    private val resolver: ContentResolver = context.contentResolver
    private val uri: Uri = ProcessCounterContract.uri(context)

    override suspend fun read(): ProcessStateRecord<ProcessCounterState>? =
        withContext(Dispatchers.IO) {
            resolver.call(uri, ProcessCounterContract.METHOD_READ, null, null)
                ?.toProcessStateRecord()
        }

    override suspend fun write(record: ProcessStateRecord<ProcessCounterState>) {
        withContext(Dispatchers.IO) {
            resolver.call(
                uri,
                ProcessCounterContract.METHOD_WRITE,
                null,
                record.toBundle(),
            )
        }
    }

    override fun observe(): Flow<ProcessStateRecord<ProcessCounterState>> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                launch {
                    read()?.let { trySend(it) }
                }
            }
        }

        resolver.registerContentObserver(uri, false, observer)
        read()?.let { trySend(it) }
        awaitClose {
            resolver.unregisterContentObserver(observer)
        }
    }
}

/**
 * Single source of truth hosted in the manifest-declared `:state_store` process.
 */
class ProcessCounterStateProvider : ContentProvider() {
    private val lock = Any()
    private var current: ProcessStateRecord<ProcessCounterState>? = null

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        ProcessCounterContract.METHOD_READ -> synchronized(lock) {
            current?.toBundle()
        }

        ProcessCounterContract.METHOD_WRITE -> {
            val candidate = extras?.toProcessStateRecord()
                ?: throw IllegalArgumentException("Missing process state record")
            val accepted = synchronized(lock) {
                val existing = current
                if (existing == null || candidate.isNewerThan(existing)) {
                    current = candidate
                }
                current
            }
            // Notify even when a concurrent candidate loses. Its caller must observe the winner.
            val providerContext = attachedContext()
            providerContext.contentResolver.notifyChange(ProcessCounterContract.uri(providerContext), null)
            accepted?.toBundle()
        }

        else -> super.call(method, arg, extras)
    }

    override fun getType(uri: Uri): String = ProcessCounterContract.MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun attachedContext(): Context = checkNotNull(context) {
        "ProcessCounterStateProvider is not attached"
    }
}

private object ProcessCounterContract {
    fun uri(context: Context): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority("${context.packageName}.process-counter-state")
        .appendPath("state")
        .build()

    const val METHOD_READ: String = "read"
    const val METHOD_WRITE: String = "write"
    const val MIME_TYPE: String = "vnd.android.cursor.item/vnd.milu.viewmodel.process-counter-state"
    const val KEY_STATE: String = "state"
    const val KEY_VERSION: String = "version"
    const val KEY_SOURCE_ID: String = "sourceId"
}

private fun ProcessStateRecord<ProcessCounterState>.toBundle(): Bundle = Bundle().apply {
    putParcelable(ProcessCounterContract.KEY_STATE, state)
    putLong(ProcessCounterContract.KEY_VERSION, version)
    putString(ProcessCounterContract.KEY_SOURCE_ID, sourceId)
}

private fun Bundle.toProcessStateRecord(): ProcessStateRecord<ProcessCounterState>? {
    classLoader = ProcessCounterState::class.java.classLoader
    val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(ProcessCounterContract.KEY_STATE, ProcessCounterState::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(ProcessCounterContract.KEY_STATE)
    } ?: return null
    val sourceId = getString(ProcessCounterContract.KEY_SOURCE_ID) ?: return null
    return ProcessStateRecord(
        state = state,
        version = getLong(ProcessCounterContract.KEY_VERSION),
        sourceId = sourceId,
    )
}

private fun ProcessStateRecord<ProcessCounterState>.isNewerThan(
    other: ProcessStateRecord<ProcessCounterState>,
): Boolean = version > other.version || (version == other.version && sourceId > other.sourceId)
