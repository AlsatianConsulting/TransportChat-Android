package dev.alsatianconsulting.transportchat.transport.messaging

import dev.alsatianconsulting.transportchat.data.model.MessageType
import dev.alsatianconsulting.transportchat.data.model.OutboundEnvelope
import org.json.JSONArray
import org.json.JSONObject

data class InboundEnvelope(
    val chatId: Long,
    val senderProfileId: String,
    val senderDeviceId: Int,
    val senderPublicKey: String,
    val groupId: String?,
    val groupTitle: String?,
    val groupMemberProfileIds: List<String>?,
    val messageType: MessageType,
    val signalCipherType: Int?,
    val ciphertext: String,
    val transferId: String?,
    val transferName: String?,
    val transferMimeType: String?,
    val transferTotalBytes: Long?,
    val transferChunkIndex: Int?,
    val transferChunkCount: Int?,
    val transferComplete: Boolean?,
    val sentAtEpochMs: Long,
    val expiresAtEpochMs: Long?
)

object LanMessageProtocol {
    fun encode(envelope: OutboundEnvelope, senderPublicKey: String): ByteArray {
        val json = JSONObject()
            .put("chatId", envelope.chatId)
            .put("senderProfileId", envelope.senderProfileId)
            .put("senderDeviceId", envelope.senderDeviceId)
            .put("senderPublicKey", senderPublicKey)
            .put("recipientProfileId", envelope.recipientProfileId)
            .put("groupId", envelope.groupId)
            .put("groupTitle", envelope.groupTitle)
            .put(
                "groupMemberProfileIds",
                envelope.groupMemberProfileIds?.let { memberIds ->
                    JSONArray().apply {
                        memberIds.forEach { put(it) }
                    }
                }
            )
            .put("messageType", envelope.messageType.name)
            .put("signalCipherType", envelope.signalCipherType)
            .put("ciphertext", envelope.ciphertext)
            .put("transferId", envelope.transferId)
            .put("transferName", envelope.transferName)
            .put("transferMimeType", envelope.transferMimeType)
            .put("transferTotalBytes", envelope.transferTotalBytes)
            .put("transferChunkIndex", envelope.transferChunkIndex)
            .put("transferChunkCount", envelope.transferChunkCount)
            .put("transferComplete", envelope.transferComplete)
            .put("sentAtEpochMs", envelope.sentAtEpochMs)
            .put("expiresAtEpochMs", envelope.expiresAtEpochMs)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(payload: ByteArray): InboundEnvelope? {
        return runCatching {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            InboundEnvelope(
                chatId = json.getLong("chatId"),
                senderProfileId = json.getString("senderProfileId"),
                senderDeviceId = json.optInt("senderDeviceId", 1),
                senderPublicKey = json.getString("senderPublicKey"),
                groupId = json.optStringOrNull("groupId"),
                groupTitle = json.optStringOrNull("groupTitle"),
                groupMemberProfileIds = json.optStringArrayOrNull("groupMemberProfileIds"),
                messageType = MessageType.valueOf(json.getString("messageType")),
                signalCipherType = json.optIntOrNull("signalCipherType"),
                ciphertext = json.getString("ciphertext"),
                transferId = json.optStringOrNull("transferId"),
                transferName = json.optStringOrNull("transferName"),
                transferMimeType = json.optStringOrNull("transferMimeType"),
                transferTotalBytes = json.optLongOrNull("transferTotalBytes"),
                transferChunkIndex = json.optIntOrNull("transferChunkIndex"),
                transferChunkCount = json.optIntOrNull("transferChunkCount"),
                transferComplete = json.optBooleanOrNull("transferComplete"),
                sentAtEpochMs = json.getLong("sentAtEpochMs"),
                expiresAtEpochMs = json.optLongOrNull("expiresAtEpochMs")
            )
        }.getOrNull()
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        return if (isNull(key)) null else getLong(key)
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (isNull(key)) null else getInt(key)
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return if (isNull(key)) null else getString(key)
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
        return if (isNull(key)) null else getBoolean(key)
    }

    private fun JSONObject.optStringArrayOrNull(key: String): List<String>? {
        if (isNull(key)) return null
        if (!has(key)) return null
        val array = optJSONArray(key) ?: return null
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index, "")
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
