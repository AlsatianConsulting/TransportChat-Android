package dev.alsatianconsulting.transportchat.data.model

import org.json.JSONObject

enum class FileTransferOfferStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    CANCELED
}

data class FileTransferOffer(
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long?,
    val status: FileTransferOfferStatus
)

object FileTransferOfferCodec {
    fun encode(offer: FileTransferOffer): String {
        return JSONObject()
            .put("kind", KIND)
            .put("transferId", offer.transferId)
            .put("fileName", offer.fileName)
            .put("mimeType", offer.mimeType)
            .put("totalBytes", offer.totalBytes)
            .put("status", offer.status.name)
            .toString()
    }

    fun decode(raw: String): FileTransferOffer? {
        if (!raw.trim().startsWith("{")) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optString("kind") != KIND) return null

        val transferId = json.optString("transferId")
        val fileName = json.optString("fileName")
        val mimeType = json.optString("mimeType")
        val statusName = json.optString("status", FileTransferOfferStatus.PENDING.name)

        if (transferId.isBlank() || fileName.isBlank() || mimeType.isBlank()) return null

        val status = runCatching { FileTransferOfferStatus.valueOf(statusName) }
            .getOrDefault(FileTransferOfferStatus.PENDING)

        return FileTransferOffer(
            transferId = transferId,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = json.optLong("totalBytes").takeIf { !json.isNull("totalBytes") },
            status = status
        )
    }

    private const val KIND = "transportchat.file.offer.meta.v1"
}
