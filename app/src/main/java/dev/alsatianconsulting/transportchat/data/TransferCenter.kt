package dev.alsatianconsulting.transportchat.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class TransferDirection { OUTGOING, INCOMING }
enum class TransferStatus { WAITING, TRANSFERRING, DONE, FAILED, REJECTED, CANCELLED }

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
    val error: String? = null,
    val savedTo: Uri? = null,
    // NEW: stable timestamp used to interleave with chat messages
    val createdAt: Long = System.currentTimeMillis(),
    // NEW: completion timestamp for “X in Y” wording
    val finishedAt: Long? = null
)

object TransferCenter {
    private val _snapshots = MutableStateFlow<Map<String, TransferSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, TransferSnapshot>> get() = _snapshots

    fun newId(): String = UUID.randomUUID().toString()

    fun startOutgoing(id: String, host: String, port: Int, name: String, mime: String?, size: Long) {
        val now = System.currentTimeMillis()
        _snapshots.update {
            it + (id to TransferSnapshot(
                id = id,
                direction = TransferDirection.OUTGOING,
                host = host,
                port = port,
                name = name,
                mime = mime,
                size = size,
                bytes = 0L,
                status = TransferStatus.WAITING,
                createdAt = now
            ))
        }
    }

    fun startIncoming(id: String, host: String, port: Int, name: String, mime: String?, size: Long) {
        val now = System.currentTimeMillis()
        _snapshots.update {
            it + (id to TransferSnapshot(
                id = id,
                direction = TransferDirection.INCOMING,
                host = host,
                port = port,
                name = name,
                mime = mime,
                size = size,
                bytes = 0L,
                status = TransferStatus.WAITING,
                createdAt = now
            ))
        }
    }

    fun progress(id: String, bytes: Long) {
        _snapshots.update {
            val s = it[id] ?: return@update it
            it + (id to s.copy(bytes = bytes, status = TransferStatus.TRANSFERRING))
        }
    }

    fun finishedOutgoing(id: String) {
        _snapshots.update {
            val s = it[id] ?: return@update it
            it + (id to s.copy(status = TransferStatus.DONE, finishedAt = System.currentTimeMillis()))
        }
    }

    fun finishedIncoming(id: String, savedTo: Uri?) {
        _snapshots.update {
            val s = it[id] ?: return@update it
            it + (id to s.copy(status = TransferStatus.DONE, savedTo = savedTo, finishedAt = System.currentTimeMillis()))
        }
    }

    fun failed(id: String, reason: String?) {
        _snapshots.update {
            val s = it[id] ?: return@update it
            it + (id to s.copy(status = TransferStatus.FAILED, error = reason, finishedAt = System.currentTimeMillis()))
        }
    }

    fun rejected(id: String) {
        _snapshots.update {
            val s = it[id] ?: return@update it
            it + (id to s.copy(status = TransferStatus.REJECTED, finishedAt = System.currentTimeMillis()))
        }
    }

    // --- Cancellation support (already present in your build) ---
    private val _cancelFlags = mutableSetOf<String>()

    @Synchronized
    fun requestCancel(id: String) {
        _cancelFlags.add(id)
        // reflect cancelled state in UI immediately
        _snapshots.update {
            val s = it[id] ?: return@update it
            it + (id to s.copy(status = TransferStatus.CANCELLED, finishedAt = System.currentTimeMillis()))
        }
    }

    @Synchronized
    fun isCancelled(id: String): Boolean = _cancelFlags.contains(id)

    @Synchronized
    fun clearCancel(id: String) {
        _cancelFlags.remove(id)
    }

    fun cancelled(id: String) {
        _snapshots.update {
            val s = it[id] ?: return@update it
            it + (id to s.copy(status = TransferStatus.CANCELLED, finishedAt = System.currentTimeMillis()))
        }
        clearCancel(id)
    }
}
