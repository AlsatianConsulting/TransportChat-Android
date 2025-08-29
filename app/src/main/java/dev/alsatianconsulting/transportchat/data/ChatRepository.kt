package dev.alsatianconsulting.transportchat.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory chat + transfer store (clears when app process dies).
 * - Stores messages per normalized host key (scope-id stripped).
 * - Tracks unread counts per host.
 * - Tracks file transfers/offers with progress.
 */
object ChatRepository {

    enum class Direction { IN, OUT }
    enum class TransferStatus {
        /** Incoming offer waiting for user decision; or outgoing offer awaiting recipient decision. */
        WAITING,
        /** Bytes are flowing. */
        RECEIVING, SENDING,
        /** Finished writing to the chosen location (incoming) or finished sending (outgoing). */
        SAVED, COMPLETED,
        /** Rejected by the recipient (outgoing) or user rejected (incoming). */
        REJECTED,
        /** Something failed. */
        FAILED
    }

    data class ChatMessage(
        val id: Long,
        val hostKey: String,
        val direction: Direction,
        val text: String,
        val timestampMs: Long
    )

    data class TransferSnapshot(
        val id: Long,
        val hostKey: String,
        val direction: Direction,
        val name: String,
        val size: Long,
        val bytes: Long,
        val status: TransferStatus,
        val tempFilePath: String? = null, // for local outgoing source (best-effort)
        val destUri: String? = null,      // for incoming saved destination (ACTION_VIEW)
        val error: String? = null
    )

    private fun normHost(h: String): String = h.substringBefore('%')

    private class HostStore {
        val msgs = MutableStateFlow<List<ChatMessage>>(emptyList())
        val unread = MutableStateFlow(0)
        val transfers = MutableStateFlow<List<TransferSnapshot>>(emptyList())

        fun append(m: ChatMessage) { msgs.value = msgs.value + m }

        fun incUnread() { unread.value = unread.value + 1 }
        fun clearUnread() { unread.value = 0 }

        fun upsertTransfer(t: TransferSnapshot) {
            val list = transfers.value
            val idx = list.indexOfFirst { it.id == t.id }
            transfers.value = if (idx >= 0) {
                list.toMutableList().apply { this[idx] = t }
            } else list + t
        }

        fun clear() {
            msgs.value = emptyList()
            unread.value = 0
            transfers.value = emptyList()
        }
    }

    private val stores = ConcurrentHashMap<String, HostStore>()
    private fun storeFor(key: String) = stores.getOrPut(key) { HostStore() }

    private val seq = AtomicLong(1L)

    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread

    private fun recomputeTotalUnread() {
        _totalUnread.value = stores.values.sumOf { it.unread.value }
    }

    // --------- MESSAGES ---------

    fun appendIncoming(fromHost: String, text: String) {
        val key = normHost(fromHost)
        val msg = ChatMessage(
            id = seq.getAndIncrement(),
            hostKey = key,
            direction = Direction.IN,
            text = text,
            timestampMs = System.currentTimeMillis()
        )
        storeFor(key).append(msg)
        // NOTE: Unread increment happens in IncomingDispatcher depending on focus.
    }

    fun appendOutgoing(toHost: String, text: String) {
        val key = normHost(toHost)
        val msg = ChatMessage(
            id = seq.getAndIncrement(),
            hostKey = key,
            direction = Direction.OUT,
            text = text,
            timestampMs = System.currentTimeMillis()
        )
        storeFor(key).append(msg)
    }

    fun incrementUnread(host: String) {
        val key = normHost(host)
        storeFor(key).incUnread()
        recomputeTotalUnread()
    }

    fun markThreadRead(aliases: Set<String>) {
        val keys = aliases.map(::normHost).toSet()
        keys.forEach { k -> storeFor(k).clearUnread() }
        recomputeTotalUnread()
    }

    fun messagesFlowFor(aliases: Set<String>): Flow<List<ChatMessage>> {
        if (aliases.isEmpty()) return flowOf(emptyList())
        val keys = aliases.map(::normHost).toSet()
        val flows: List<StateFlow<List<ChatMessage>>> = keys.map { k -> storeFor(k).msgs }
        return when (flows.size) {
            0 -> flowOf(emptyList())
            1 -> flows.first()
            else -> combine(flows) { arr: Array<List<ChatMessage>> ->
                arr.flatMap { it }.sortedBy { it.timestampMs }
            }
        }
    }

    fun unreadCountFlowFor(host: String): StateFlow<Int> = storeFor(normHost(host)).unread

    // --------- TRANSFERS / OFFERS ---------

    private val transferIndex = ConcurrentHashMap<Long, String>() // id -> hostKey

    fun transfersFlowFor(aliases: Set<String>): Flow<List<TransferSnapshot>> {
        if (aliases.isEmpty()) return flowOf(emptyList())
        val keys = aliases.map(::normHost).toSet()
        val flows: List<StateFlow<List<TransferSnapshot>>> = keys.map { k -> storeFor(k).transfers }
        return when (flows.size) {
            0 -> flowOf(emptyList())
            1 -> flows.first()
            else -> combine(flows) { arr: Array<List<TransferSnapshot>> ->
                arr.flatMap { it }.sortedBy { it.id }
            }
        }
    }

    /** Incoming side: create an offer row (WAITING) and return its id. */
    fun startIncomingOffer(fromHost: String, name: String, size: Long): Long {
        val key = normHost(fromHost)
        val id = seq.getAndIncrement()
        val snap = TransferSnapshot(
            id = id, hostKey = key, direction = Direction.IN,
            name = name, size = size, bytes = 0L, status = TransferStatus.WAITING
        )
        storeFor(key).upsertTransfer(snap)
        transferIndex[id] = key
        return id
    }

    /** Called when user accepts and bytes will start flowing. */
    fun acceptIncomingStarted(id: Long) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(status = TransferStatus.RECEIVING, bytes = 0L))
    }

    /** Persist the chosen destination URI (for "Open" later). */
    fun noteIncomingDestination(id: Long, destUri: String) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(destUri = destUri))
    }

    fun updateIncomingProgress(id: Long, bytes: Long) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(bytes = bytes))
    }

    /** Incoming finished directly to user-chosen destination. */
    fun finishIncomingSaved(id: Long) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(bytes = cur.size, status = TransferStatus.SAVED))
    }

    fun rejectIncoming(id: Long) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(status = TransferStatus.REJECTED))
    }

    fun failIncomingTransfer(id: Long, error: String?) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(status = TransferStatus.FAILED, error = error))
    }

    /** Outgoing side: create an offer row (WAITING) and return its id. */
    fun startOutgoingOffer(toHost: String, name: String, size: Long, localPath: String? = null): Long {
        val key = normHost(toHost)
        val id = seq.getAndIncrement()
        val snap = TransferSnapshot(
            id = id, hostKey = key, direction = Direction.OUT,
            name = name, size = size, bytes = 0L, status = TransferStatus.WAITING,
            tempFilePath = localPath
        )
        storeFor(key).upsertTransfer(snap)
        transferIndex[id] = key
        return id
    }

    /** Recipient accepted; we are about to send bytes. */
    fun markOutgoingAccepted(id: Long) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(status = TransferStatus.SENDING, bytes = 0L))
    }

    fun updateOutgoingProgress(id: Long, bytes: Long) = updateIncomingProgress(id, bytes)

    fun finishOutgoingTransfer(id: Long) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(bytes = cur.size, status = TransferStatus.COMPLETED))
    }

    fun rejectOutgoing(id: Long) {
        val key = transferIndex[id] ?: return
        val store = storeFor(key)
        val cur = store.transfers.value.firstOrNull { it.id == id } ?: return
        store.upsertTransfer(cur.copy(status = TransferStatus.REJECTED))
    }

    fun failOutgoingTransfer(id: Long, error: String?) = failIncomingTransfer(id, error)

    /** Legacy helper retained for compatibility (not used by accept-first flow). */
    fun exportIncomingToUri(context: Context, transferId: Long, dest: Uri): Boolean {
        val key = transferIndex[transferId] ?: return false
        val store = storeFor(key)
        val snap = store.transfers.value.firstOrNull { it.id == transferId } ?: return false
        val path = snap.tempFilePath ?: return false

        return try {
            context.contentResolver.openOutputStream(dest)?.use { out ->
                FileInputStream(File(path)).use { inp ->
                    inp.copyTo(out)
                }
            } ?: return false
            store.upsertTransfer(snap.copy(status = TransferStatus.SAVED))
            true
        } catch (t: Throwable) {
            store.upsertTransfer(snap.copy(status = TransferStatus.FAILED, error = t.message))
            false
        }
    }

    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
        _totalUnread.value = 0
        transferIndex.clear()
    }
}
