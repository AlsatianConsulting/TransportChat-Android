package dev.alsatianconsulting.transportchat.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class TransferDirection { OUTGOING, INCOMING }
enum class TransferStatus { WAITING, TRANSFERRING, DONE, FAILED, REJECTED }

data class TransferSnapshot(
    val id: String,
    val direction: TransferDirection,
    val host: String,
    val port: Int,
    val name: String,
    val mime: String?,
    val size: Long,
    val bytes: Long,
    val status: TransferStatus,
    val savedTo: Uri? = null,
    val error: String? = null
)

object TransferCenter {
    private val _snapshots = MutableStateFlow<Map<String, TransferSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, TransferSnapshot>> = _snapshots

    fun newId(): String = UUID.randomUUID().toString()

    fun startIncoming(id: String, host: String, port: Int, name: String, mime: String?, size: Long) {
        _snapshots.update { it + (id to TransferSnapshot(id, TransferDirection.INCOMING, host, port, name, mime, size, 0L, TransferStatus.WAITING)) }
    }

    fun startOutgoing(id: String, host: String, port: Int, name: String, mime: String?, size: Long) {
        _snapshots.update { it + (id to TransferSnapshot(id, TransferDirection.OUTGOING, host, port, name, mime, size, 0L, TransferStatus.TRANSFERRING)) }
    }

    fun progress(id: String, bytesSoFar: Long) {
        _snapshots.update {
            val s = it[id] ?: return
            it + (id to s.copy(bytes = bytesSoFar, status = TransferStatus.TRANSFERRING))
        }
    }

    fun finishedIncoming(id: String, savedTo: Uri) {
        _snapshots.update {
            val s = it[id] ?: return
            it + (id to s.copy(bytes = s.size, status = TransferStatus.DONE, savedTo = savedTo))
        }
    }

    fun finishedOutgoing(id: String) {
        _snapshots.update {
            val s = it[id] ?: return
            it + (id to s.copy(bytes = s.size, status = TransferStatus.DONE))
        }
    }

    fun failed(id: String, message: String?) {
        _snapshots.update {
            val s = it[id] ?: return
            it + (id to s.copy(status = TransferStatus.FAILED, error = message))
        }
    }

    fun rejected(id: String) {
        _snapshots.update {
            val s = it[id] ?: return
            it + (id to s.copy(status = TransferStatus.REJECTED))
        }
    }
}
