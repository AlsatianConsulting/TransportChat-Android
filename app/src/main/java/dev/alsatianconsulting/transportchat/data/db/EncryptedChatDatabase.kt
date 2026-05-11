package dev.alsatianconsulting.transportchat.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import dev.alsatianconsulting.transportchat.data.model.Chat
import dev.alsatianconsulting.transportchat.data.model.ChatType
import dev.alsatianconsulting.transportchat.data.model.Contact
import dev.alsatianconsulting.transportchat.data.model.ContactTrustStatus
import dev.alsatianconsulting.transportchat.data.model.DeliveryStatus
import dev.alsatianconsulting.transportchat.data.model.Message
import dev.alsatianconsulting.transportchat.data.model.MessageType
import dev.alsatianconsulting.transportchat.data.model.SignalPreKeyBundleData
import dev.alsatianconsulting.transportchat.data.model.VerificationRecord
import net.zetetic.database.sqlcipher.SQLiteDatabase

class EncryptedChatDatabase(private val context: Context) {
    @Volatile
    private var database: SQLiteDatabase? = null

    private fun ensureSqlCipherLoaded() {
        if (sqlCipherLoaded) return
        synchronized(EncryptedChatDatabase::class.java) {
            if (sqlCipherLoaded) return
            System.loadLibrary("sqlcipher")
            sqlCipherLoaded = true
        }
    }

    @Synchronized
    fun unlock(passphrase: ByteArray) {
        if (database?.isOpen == true) return
        ensureSqlCipherLoaded()
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, passphrase, null, null)
        db.execSQL("PRAGMA foreign_keys=ON;")
        createSchemaIfNeeded(db)
        database = db
    }

    @Synchronized
    fun lock() {
        database?.close()
        database = null
    }

    fun isUnlocked(): Boolean = database?.isOpen == true

    fun upsertContact(
        profileId: String,
        displayName: String,
        host: String,
        port: Int,
        publicKey: String,
        safetyPhrase: String,
        signalBundle: SignalPreKeyBundleData?,
        trustStatus: ContactTrustStatus,
        isManualPeer: Boolean
    ): Long {
        val db = requireDb()
        val now = System.currentTimeMillis()
        val existingId = db.rawQuery("SELECT id FROM contacts WHERE profile_id=?", arrayOf(profileId)).useCursor {
            if (moveToFirst()) getLong(0) else null
        }

        val values = ContentValues().apply {
            put("profile_id", profileId)
            put("display_name", displayName)
            put("host", host)
            put("port", port)
            put("public_key", publicKey)
            put("safety_phrase", safetyPhrase)
            if (signalBundle != null) {
                put("signal_registration_id", signalBundle.registrationId)
                put("signal_device_id", signalBundle.deviceId)
                put("signal_pre_key_id", signalBundle.preKeyId)
                put("signal_pre_key_public_key", signalBundle.preKeyPublicKey)
                put("signal_signed_pre_key_id", signalBundle.signedPreKeyId)
                put("signal_signed_pre_key_public_key", signalBundle.signedPreKeyPublicKey)
                put("signal_signed_pre_key_signature", signalBundle.signedPreKeySignature)
            }
            put("trust_status", trustStatus.name)
            put("is_manual_peer", if (isManualPeer) 1 else 0)
            put("last_seen_epoch_ms", now)
        }

        return if (existingId == null) {
            db.insertOrThrow("contacts", null, values)
        } else {
            db.update("contacts", values, "id=?", arrayOf(existingId.toString()))
            existingId
        }
    }

    fun updateNickname(contactId: Long, nickname: String?) {
        val db = requireDb()
        val values = ContentValues().apply { put("nickname", nickname) }
        db.update("contacts", values, "id=?", arrayOf(contactId.toString()))
    }

    fun getContacts(): List<Contact> {
        val db = requireDb()
        return db.rawQuery(
            """
            SELECT id, profile_id, display_name, nickname, host, port, public_key, safety_phrase,
                   signal_registration_id, signal_device_id, signal_pre_key_id, signal_pre_key_public_key,
                   signal_signed_pre_key_id, signal_signed_pre_key_public_key, signal_signed_pre_key_signature,
                   trust_status, is_manual_peer, last_seen_epoch_ms
            FROM contacts
            ORDER BY COALESCE(nickname, display_name) COLLATE NOCASE ASC
            """.trimIndent(),
            emptyArray()
        ).useCursor {
            buildList {
                while (moveToNext()) {
                    add(
                        Contact(
                            id = getLong(0),
                            profileId = getString(1),
                            displayName = getString(2),
                            nickname = getStringOrNull(3),
                            host = getString(4),
                            port = getInt(5),
                            publicKey = getString(6),
                            safetyPhrase = getString(7),
                            signalRegistrationId = getIntOrNull(8),
                            signalDeviceId = getIntOrNull(9),
                            signalPreKeyId = getIntOrNull(10),
                            signalPreKeyPublicKey = getStringOrNull(11),
                            signalSignedPreKeyId = getIntOrNull(12),
                            signalSignedPreKeyPublicKey = getStringOrNull(13),
                            signalSignedPreKeySignature = getStringOrNull(14),
                            trustStatus = ContactTrustStatus.valueOf(getString(15)),
                            isManualPeer = getInt(16) == 1,
                            lastSeenEpochMs = getLong(17)
                        )
                    )
                }
            }
        }
    }

    fun getContactByProfileId(profileId: String): Contact? {
        val db = requireDb()
        return db.rawQuery(
            """
            SELECT id, profile_id, display_name, nickname, host, port, public_key, safety_phrase,
                   signal_registration_id, signal_device_id, signal_pre_key_id, signal_pre_key_public_key,
                   signal_signed_pre_key_id, signal_signed_pre_key_public_key, signal_signed_pre_key_signature,
                   trust_status, is_manual_peer, last_seen_epoch_ms
            FROM contacts WHERE profile_id=? LIMIT 1
            """.trimIndent(),
            arrayOf(profileId)
        ).useCursor {
            if (!moveToFirst()) return@useCursor null
            Contact(
                id = getLong(0),
                profileId = getString(1),
                displayName = getString(2),
                nickname = getStringOrNull(3),
                host = getString(4),
                port = getInt(5),
                publicKey = getString(6),
                safetyPhrase = getString(7),
                signalRegistrationId = getIntOrNull(8),
                signalDeviceId = getIntOrNull(9),
                signalPreKeyId = getIntOrNull(10),
                signalPreKeyPublicKey = getStringOrNull(11),
                signalSignedPreKeyId = getIntOrNull(12),
                signalSignedPreKeyPublicKey = getStringOrNull(13),
                signalSignedPreKeySignature = getStringOrNull(14),
                trustStatus = ContactTrustStatus.valueOf(getString(15)),
                isManualPeer = getInt(16) == 1,
                lastSeenEpochMs = getLong(17)
            )
        }
    }

    fun ensureDirectChat(remoteProfileId: String, title: String, createdByProfileId: String): Long {
        val db = requireDb()
        val existing = db.rawQuery(
            "SELECT id FROM chats WHERE remote_id=? AND type=? LIMIT 1",
            arrayOf(remoteProfileId, ChatType.DIRECT.name)
        ).useCursor { if (moveToFirst()) getLong(0) else null }
        if (existing != null) {
            val values = ContentValues().apply { put("title", title) }
            db.update(
                "chats",
                values,
                "id=? AND COALESCE(is_custom_title, 0)=0",
                arrayOf(existing.toString())
            )
            return existing
        }

        val values = ContentValues().apply {
            put("remote_id", remoteProfileId)
            put("type", ChatType.DIRECT.name)
            put("title", title)
            put("is_custom_title", 0)
            put("created_by_profile_id", createdByProfileId)
            put("created_at_epoch_ms", System.currentTimeMillis())
            putNull("disappearing_seconds")
        }
        return db.insertOrThrow("chats", null, values)
    }

    fun createGroupChat(groupId: String, title: String, createdByProfileId: String, memberProfileIds: List<String>): Long {
        val db = requireDb()
        val now = System.currentTimeMillis()
        val chatValues = ContentValues().apply {
            put("remote_id", groupId)
            put("type", ChatType.GROUP.name)
            put("title", title)
            put("created_by_profile_id", createdByProfileId)
            put("created_at_epoch_ms", now)
            putNull("disappearing_seconds")
        }
        val chatId = db.insertOrThrow("chats", null, chatValues)

        memberProfileIds.forEach { memberId ->
            val memberValues = ContentValues().apply {
                put("chat_id", chatId)
                put("member_profile_id", memberId)
                put("role", if (memberId == createdByProfileId) "admin" else "member")
                put("joined_at_epoch_ms", now)
            }
            db.insertOrThrow("group_members", null, memberValues)
        }
        return chatId
    }

    fun ensureGroupChat(
        groupId: String,
        title: String,
        createdByProfileId: String,
        memberProfileIds: List<String>,
        joinedAtEpochMs: Long = System.currentTimeMillis()
    ): Long {
        val db = requireDb()
        val existingChatId = db.rawQuery(
            "SELECT id FROM chats WHERE remote_id=? AND type=? LIMIT 1",
            arrayOf(groupId, ChatType.GROUP.name)
        ).useCursor {
            if (moveToFirst()) getLong(0) else null
        }

        val chatId = existingChatId ?: run {
            val now = System.currentTimeMillis()
            val chatValues = ContentValues().apply {
                put("remote_id", groupId)
                put("type", ChatType.GROUP.name)
                put("title", title)
                put("created_by_profile_id", createdByProfileId)
                put("created_at_epoch_ms", now)
                putNull("disappearing_seconds")
            }
            db.insertOrThrow("chats", null, chatValues)
        }

        memberProfileIds.distinct().forEach { memberId ->
            val role = if (memberId == createdByProfileId) "admin" else "member"
            db.execSQL(
                """
                INSERT OR IGNORE INTO group_members (chat_id, member_profile_id, role, joined_at_epoch_ms)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(chatId, memberId, role, joinedAtEpochMs)
            )
        }

        return chatId
    }

    fun getChats(): List<Chat> {
        val db = requireDb()
        return db.rawQuery(
            """
            SELECT id, remote_id, type, title, created_by_profile_id, created_at_epoch_ms, disappearing_seconds
            FROM chats
            ORDER BY created_at_epoch_ms DESC
            """.trimIndent(),
            emptyArray()
        ).useCursor {
            buildList {
                while (moveToNext()) {
                    add(
                        Chat(
                            id = getLong(0),
                            remoteId = getString(1),
                            type = ChatType.valueOf(getString(2)),
                            title = getString(3),
                            createdByProfileId = getString(4),
                            createdAtEpochMs = getLong(5),
                            disappearingSeconds = getLongOrNull(6)
                        )
                    )
                }
            }
        }
    }

    fun getGroupMemberProfileIds(chatId: Long): List<String> {
        val db = requireDb()
        return db.rawQuery(
            """
            SELECT member_profile_id
            FROM group_members
            WHERE chat_id=?
            ORDER BY id ASC
            """.trimIndent(),
            arrayOf(chatId.toString())
        ).useCursor {
            buildList {
                while (moveToNext()) {
                    add(getString(0))
                }
            }
        }
    }

    fun replaceGroupMembers(
        chatId: Long,
        createdByProfileId: String,
        memberProfileIds: List<String>,
        joinedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val db = requireDb()
        val existingJoinedAt = db.rawQuery(
            """
            SELECT member_profile_id, joined_at_epoch_ms
            FROM group_members
            WHERE chat_id=?
            """.trimIndent(),
            arrayOf(chatId.toString())
        ).useCursor {
            buildMap {
                while (moveToNext()) {
                    put(getString(0), getLong(1))
                }
            }
        }
        db.beginTransaction()
        try {
            db.delete("group_members", "chat_id=?", arrayOf(chatId.toString()))
            memberProfileIds.distinct().forEach { memberId ->
                val memberValues = ContentValues().apply {
                    put("chat_id", chatId)
                    put("member_profile_id", memberId)
                    put("role", if (memberId == createdByProfileId) "admin" else "member")
                    put("joined_at_epoch_ms", existingJoinedAt[memberId] ?: joinedAtEpochMs)
                }
                db.insertOrThrow("group_members", null, memberValues)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateGroupAdmin(chatId: Long, createdByProfileId: String) {
        val db = requireDb()
        val chatValues = ContentValues().apply { put("created_by_profile_id", createdByProfileId) }
        db.update("chats", chatValues, "id=?", arrayOf(chatId.toString()))

        val demoteValues = ContentValues().apply { put("role", "member") }
        db.update("group_members", demoteValues, "chat_id=?", arrayOf(chatId.toString()))

        val promoteValues = ContentValues().apply { put("role", "admin") }
        db.update(
            "group_members",
            promoteValues,
            "chat_id=? AND member_profile_id=?",
            arrayOf(chatId.toString(), createdByProfileId)
        )
    }

    fun groupMemberJoinedAt(chatId: Long, profileId: String): Long? {
        val db = requireDb()
        return db.rawQuery(
            """
            SELECT joined_at_epoch_ms
            FROM group_members
            WHERE chat_id=? AND member_profile_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(chatId.toString(), profileId)
        ).useCursor {
            if (!moveToFirst()) return@useCursor null
            getLong(0)
        }
    }

    fun blockProfile(profileId: String) {
        val db = requireDb()
        db.execSQL(
            """
            INSERT OR REPLACE INTO blocked_profiles (profile_id, blocked_at_epoch_ms)
            VALUES (?, ?)
            """.trimIndent(),
            arrayOf<Any>(profileId, System.currentTimeMillis())
        )
    }

    fun unblockProfile(profileId: String) {
        val db = requireDb()
        db.delete("blocked_profiles", "profile_id=?", arrayOf(profileId))
    }

    fun getBlockedProfileIds(): Set<String> {
        val db = requireDb()
        return db.rawQuery(
            "SELECT profile_id FROM blocked_profiles",
            emptyArray()
        ).useCursor {
            buildSet {
                while (moveToNext()) {
                    add(getString(0))
                }
            }
        }
    }

    fun renameChat(chatId: Long, newTitle: String) {
        val db = requireDb()
        val values = ContentValues().apply {
            put("title", newTitle)
            put("is_custom_title", 1)
        }
        db.update("chats", values, "id=?", arrayOf(chatId.toString()))
    }

    fun updateChatDisappearingSeconds(chatId: Long, seconds: Long?) {
        val db = requireDb()
        val values = ContentValues().apply {
            if (seconds == null) putNull("disappearing_seconds") else put("disappearing_seconds", seconds)
        }
        db.update("chats", values, "id=?", arrayOf(chatId.toString()))
    }

    fun updateChatLastReadEpoch(chatId: Long, epochMs: Long) {
        val db = requireDb()
        val values = ContentValues().apply { put("last_read_epoch_ms", epochMs) }
        db.update("chats", values, "id=?", arrayOf(chatId.toString()))
    }

    fun loadChatLastReadEpochs(): Map<Long, Long> {
        val db = requireDb()
        return db.rawQuery("SELECT id, last_read_epoch_ms FROM chats", null).useCursor {
            buildMap {
                while (moveToNext()) {
                    val epoch = getLong(1)
                    if (epoch > 0L) put(getLong(0), epoch)
                }
            }
        }
    }

    fun insertMessage(
        chatId: Long,
        senderProfileId: String,
        encryptedBody: String,
        decryptedPreview: String,
        type: MessageType,
        signalCipherType: Int?,
        expiresAtEpochMs: Long?,
        localOnly: Boolean,
        sentAtEpochMs: Long = System.currentTimeMillis(),
        deliveryStatus: DeliveryStatus = DeliveryStatus.SENT
    ): Long {
        val db = requireDb()
        val values = ContentValues().apply {
            put("chat_id", chatId)
            put("sender_profile_id", senderProfileId)
            put("encrypted_body", encryptedBody)
            put("decrypted_preview", decryptedPreview)
            put("type", type.name)
            if (signalCipherType == null) putNull("signal_cipher_type") else put("signal_cipher_type", signalCipherType)
            put("sent_at_epoch_ms", sentAtEpochMs)
            if (expiresAtEpochMs == null) putNull("expires_at_epoch_ms") else put("expires_at_epoch_ms", expiresAtEpochMs)
            put("local_only", if (localOnly) 1 else 0)
            put("delivery_status", deliveryStatus.name)
        }
        return db.insertOrThrow("messages", null, values)
    }

    fun updateDeliveryStatusBySentAt(
        chatId: Long,
        senderProfileId: String,
        sentAtEpochMs: Long,
        status: DeliveryStatus,
        readAtEpochMs: Long = 0L
    ) {
        val db = requireDb()
        val values = ContentValues().apply {
            put("delivery_status", status.name)
            if (readAtEpochMs > 0L) put("read_at_epoch_ms", readAtEpochMs)
        }
        db.update(
            "messages", values,
            "chat_id=? AND sender_profile_id=? AND sent_at_epoch_ms=?",
            arrayOf(chatId.toString(), senderProfileId, sentAtEpochMs.toString())
        )
    }

    fun updateMessageContent(messageId: Long, encryptedBody: String?, decryptedPreview: String) {
        val db = requireDb()
        val values = ContentValues().apply {
            put("decrypted_preview", decryptedPreview)
            if (encryptedBody != null) {
                put("encrypted_body", encryptedBody)
            }
        }
        db.update("messages", values, "id=?", arrayOf(messageId.toString()))
    }

    fun getMessages(chatId: Long): List<Message> {
        val db = requireDb()
        deleteExpiredMessages(chatId)
        return db.rawQuery(
            """
            SELECT id, chat_id, sender_profile_id, encrypted_body, decrypted_preview, type,
                   signal_cipher_type, expires_at_epoch_ms, sent_at_epoch_ms, local_only, delivery_status,
                   read_at_epoch_ms
            FROM messages
            WHERE chat_id=?
            ORDER BY sent_at_epoch_ms ASC
            """.trimIndent(),
            arrayOf(chatId.toString())
        ).useCursor {
            buildList {
                while (moveToNext()) {
                    add(
                        Message(
                            id = getLong(0),
                            chatId = getLong(1),
                            senderProfileId = getString(2),
                            encryptedBody = getString(3),
                            decryptedPreview = getString(4),
                            type = MessageType.valueOf(getString(5)),
                            signalCipherType = getIntOrNull(6),
                            expiresAtEpochMs = getLongOrNull(7),
                            sentAtEpochMs = getLong(8),
                            localOnly = getInt(9) == 1,
                            deliveryStatus = runCatching { DeliveryStatus.valueOf(getString(10)) }.getOrDefault(DeliveryStatus.SENT),
                            readAtEpochMs = getLong(11)
                        )
                    )
                }
            }
        }
    }

    fun clearChat(chatId: Long) {
        val db = requireDb()
        db.delete("messages", "chat_id=?", arrayOf(chatId.toString()))
    }

    fun exportChatPlaintext(chatId: Long, fallbackName: String): String {
        val messages = getMessages(chatId)
        val builder = StringBuilder()
        builder.appendLine("Chat Export: $fallbackName")
        builder.appendLine("Exported At: ${System.currentTimeMillis()}")
        builder.appendLine("----------------------------------------")
        messages.forEach { message ->
            builder.appendLine("${message.sentAtEpochMs} | ${message.senderProfileId}: ${message.decryptedPreview}")
        }
        return builder.toString()
    }

    fun upsertVerification(
        contactId: Long,
        observedSafetyPhrase: String,
        expectedSafetyPhrase: String,
        verified: Boolean
    ) {
        val db = requireDb()
        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("observed_safety_phrase", observedSafetyPhrase)
            put("expected_safety_phrase", expectedSafetyPhrase)
            put("verified", if (verified) 1 else 0)
            put("verified_at_epoch_ms", System.currentTimeMillis())
            putNull("dismissed_at_epoch_ms")
        }
        db.insertOrThrow("verification_records", null, values)

        val contactValues = ContentValues().apply {
            put(
                "trust_status",
                if (verified) ContactTrustStatus.VERIFIED.name else ContactTrustStatus.MISMATCH.name
            )
        }
        db.update("contacts", contactValues, "id=?", arrayOf(contactId.toString()))
    }

    fun dismissVerificationWarning(contactId: Long) {
        val db = requireDb()
        db.execSQL(
            """
            UPDATE verification_records
            SET dismissed_at_epoch_ms=?
            WHERE id=(
                SELECT id FROM verification_records
                WHERE contact_id=?
                ORDER BY verified_at_epoch_ms DESC
                LIMIT 1
            )
            """.trimIndent(),
            arrayOf(System.currentTimeMillis(), contactId)
        )
    }

    fun latestVerification(contactId: Long): VerificationRecord? {
        val db = requireDb()
        return db.rawQuery(
            """
            SELECT id, contact_id, observed_safety_phrase, expected_safety_phrase,
                   verified, verified_at_epoch_ms, dismissed_at_epoch_ms
            FROM verification_records
            WHERE contact_id=?
            ORDER BY verified_at_epoch_ms DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(contactId.toString())
        ).useCursor {
            if (!moveToFirst()) return@useCursor null
            VerificationRecord(
                id = getLong(0),
                contactId = getLong(1),
                observedSafetyPhrase = getString(2),
                expectedSafetyPhrase = getString(3),
                verified = getInt(4) == 1,
                verifiedAtEpochMs = getLong(5),
                dismissedAtEpochMs = getLongOrNull(6)
            )
        }
    }

    private fun deleteExpiredMessages(chatId: Long) {
        val db = requireDb()
        db.delete(
            "messages",
            "chat_id=? AND expires_at_epoch_ms IS NOT NULL AND expires_at_epoch_ms <= ?",
            arrayOf(chatId.toString(), System.currentTimeMillis().toString())
        )
    }

    private fun createSchemaIfNeeded(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                nickname TEXT,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                public_key TEXT NOT NULL,
                safety_phrase TEXT NOT NULL,
                signal_registration_id INTEGER,
                signal_device_id INTEGER,
                signal_pre_key_id INTEGER,
                signal_pre_key_public_key TEXT,
                signal_signed_pre_key_id INTEGER,
                signal_signed_pre_key_public_key TEXT,
                signal_signed_pre_key_signature TEXT,
                trust_status TEXT NOT NULL,
                is_manual_peer INTEGER NOT NULL DEFAULT 0,
                last_seen_epoch_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                remote_id TEXT NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                is_custom_title INTEGER NOT NULL DEFAULT 0,
                created_by_profile_id TEXT NOT NULL,
                created_at_epoch_ms INTEGER NOT NULL,
                disappearing_seconds INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_members (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                member_profile_id TEXT NOT NULL,
                role TEXT NOT NULL,
                joined_at_epoch_ms INTEGER NOT NULL DEFAULT 0,
                UNIQUE(chat_id, member_profile_id),
                FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                sender_profile_id TEXT NOT NULL,
                encrypted_body TEXT NOT NULL,
                decrypted_preview TEXT NOT NULL,
                type TEXT NOT NULL,
                signal_cipher_type INTEGER,
                expires_at_epoch_ms INTEGER,
                sent_at_epoch_ms INTEGER NOT NULL,
                local_only INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS verification_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER NOT NULL,
                observed_safety_phrase TEXT NOT NULL,
                expected_safety_phrase TEXT NOT NULL,
                verified INTEGER NOT NULL,
                verified_at_epoch_ms INTEGER NOT NULL,
                dismissed_at_epoch_ms INTEGER,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blocked_profiles (
                profile_id TEXT PRIMARY KEY,
                blocked_at_epoch_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        ensureColumn(
            db = db,
            table = "contacts",
            column = "signal_registration_id",
            definition = "INTEGER"
        )
        ensureColumn(
            db = db,
            table = "contacts",
            column = "signal_device_id",
            definition = "INTEGER"
        )
        ensureColumn(
            db = db,
            table = "contacts",
            column = "signal_pre_key_id",
            definition = "INTEGER"
        )
        ensureColumn(
            db = db,
            table = "contacts",
            column = "signal_pre_key_public_key",
            definition = "TEXT"
        )
        ensureColumn(
            db = db,
            table = "contacts",
            column = "signal_signed_pre_key_id",
            definition = "INTEGER"
        )
        ensureColumn(
            db = db,
            table = "contacts",
            column = "signal_signed_pre_key_public_key",
            definition = "TEXT"
        )
        ensureColumn(
            db = db,
            table = "contacts",
            column = "signal_signed_pre_key_signature",
            definition = "TEXT"
        )
        ensureColumn(
            db = db,
            table = "messages",
            column = "signal_cipher_type",
            definition = "INTEGER"
        )
        ensureColumn(
            db = db,
            table = "chats",
            column = "disappearing_seconds",
            definition = "INTEGER"
        )
        val addedJoinedAt = ensureColumn(
            db = db,
            table = "group_members",
            column = "joined_at_epoch_ms",
            definition = "INTEGER NOT NULL DEFAULT 0"
        )
        if (addedJoinedAt) {
            db.execSQL(
                """
                UPDATE group_members
                SET joined_at_epoch_ms = (
                    SELECT chats.created_at_epoch_ms
                    FROM chats
                    WHERE chats.id = group_members.chat_id
                )
                WHERE joined_at_epoch_ms = 0
                """.trimIndent()
            )
        }
        ensureColumn(
            db = db,
            table = "messages",
            column = "delivery_status",
            definition = "TEXT NOT NULL DEFAULT 'SENT'"
        )
        ensureColumn(
            db = db,
            table = "messages",
            column = "read_at_epoch_ms",
            definition = "INTEGER NOT NULL DEFAULT 0"
        )
        val addedLastRead = ensureColumn(
            db = db,
            table = "chats",
            column = "last_read_epoch_ms",
            definition = "INTEGER NOT NULL DEFAULT 0"
        )
        // Backfill any chat still at 0: treat existing messages as already read so history
        // doesn't appear as unread. Runs on fresh column addition AND on first open after an
        // earlier migration that left all values at 0.
        db.execSQL(
            """
            UPDATE chats
            SET last_read_epoch_ms = COALESCE(
                (SELECT MAX(sent_at_epoch_ms) FROM messages WHERE messages.chat_id = chats.id),
                0
            )
            WHERE last_read_epoch_ms = 0
            """.trimIndent()
        )
        val addedCustomTitle = ensureColumn(
            db = db,
            table = "chats",
            column = "is_custom_title",
            definition = "INTEGER NOT NULL DEFAULT 0"
        )
        if (addedCustomTitle) {
            db.execSQL(
                """
                UPDATE chats
                SET is_custom_title = 1
                WHERE type = ?
                  AND EXISTS (
                      SELECT 1
                      FROM contacts
                      WHERE contacts.profile_id = chats.remote_id
                        AND chats.title != contacts.display_name
                        AND chats.title != COALESCE(NULLIF(contacts.nickname, ''), contacts.display_name)
                  )
                """.trimIndent(),
                arrayOf<Any>(ChatType.DIRECT.name)
            )
        }
        db.execSQL(
            """
            UPDATE chats
            SET is_custom_title = 0
            WHERE type = ?
              AND COALESCE(is_custom_title, 0) = 1
              AND EXISTS (
                  SELECT 1
                  FROM contacts
                  WHERE contacts.profile_id = chats.remote_id
                    AND NULLIF(contacts.nickname, '') IS NOT NULL
                    AND chats.title = contacts.display_name
              )
            """.trimIndent(),
            arrayOf<Any>(ChatType.DIRECT.name)
        )
        db.execSQL(
            """
            UPDATE chats
            SET title = (
                SELECT COALESCE(NULLIF(contacts.nickname, ''), contacts.display_name)
                FROM contacts
                WHERE contacts.profile_id = chats.remote_id
            )
            WHERE type = ?
              AND COALESCE(is_custom_title, 0) = 0
              AND EXISTS (
                  SELECT 1
                  FROM contacts
                  WHERE contacts.profile_id = chats.remote_id
              )
            """.trimIndent(),
            arrayOf<Any>(ChatType.DIRECT.name)
        )
    }

    private fun requireDb(): SQLiteDatabase {
        return database?.takeIf { it.isOpen }
            ?: error("Database is locked. Unlock first.")
    }

    private fun <T> Cursor.useCursor(block: Cursor.() -> T): T {
        return use { cursor -> cursor.block() }
    }

    private fun Cursor.getStringOrNull(index: Int): String? {
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLongOrNull(index: Int): Long? {
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.getIntOrNull(index: Int): Int? {
        return if (isNull(index)) null else getInt(index)
    }

    private fun ensureColumn(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ): Boolean {
        val hasColumn = db.rawQuery("PRAGMA table_info($table)", emptyArray()).useCursor {
            var found = false
            while (moveToNext()) {
                if (getString(1) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasColumn) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
            return true
        }
        return false
    }

    companion object {
        private const val DB_NAME = "transportchat_encrypted.db"
        @Volatile
        private var sqlCipherLoaded = false
    }
}
