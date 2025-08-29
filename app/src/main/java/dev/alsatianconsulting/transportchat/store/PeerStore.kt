package dev.alsatianconsulting.transportchat.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.alsatianconsulting.transportchat.model.Peer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("lan_chat_prefs")

class PeerStore(private val context: Context) {
    private val gson = Gson()
    private val KEY_PEERS = stringPreferencesKey("peers_json")
    private val KEY_BLOCKED = stringPreferencesKey("blocked_json")
    private val KEY_NICKS = stringPreferencesKey("nicknames_json")

    // existing peers list (kept for compatibility if you already store these)
    fun peersFlow(): Flow<List<Peer>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_PEERS]
        if (json.isNullOrBlank()) emptyList() else runCatching {
            val type = object : TypeToken<List<Peer>>() {}.type
            gson.fromJson<List<Peer>>(json, type) ?: emptyList()
        }.getOrElse { emptyList() }
    }
    suspend fun savePeers(list: List<Peer>) { context.dataStore.edit { it[KEY_PEERS] = gson.toJson(list) } }
    suspend fun getPeersOnce(): List<Peer> = peersFlow().first()

    // blocklist
    fun blockedFlow(): Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_BLOCKED]
        if (json.isNullOrBlank()) emptySet() else runCatching {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(json, type) ?: emptySet()
        }.getOrElse { emptySet() }
    }
    suspend fun isBlocked(host: String, port: Int): Boolean =
        blockedFlow().first().contains("$host:$port")
    suspend fun setBlocked(host: String, port: Int, blocked: Boolean) {
        val id = "$host:$port"
        val cur = blockedFlow().first().toMutableSet()
        if (blocked) cur += id else cur -= id
        context.dataStore.edit { it[KEY_BLOCKED] = gson.toJson(cur) }
    }

    // nicknames
    fun nicknamesFlow(): Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_NICKS]
        if (json.isNullOrBlank()) emptyMap() else runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        }.getOrElse { emptyMap() }
    }
    suspend fun getNickname(host: String, port: Int): String? =
        nicknamesFlow().first()["$host:$port"]
    suspend fun setNickname(host: String, port: Int, label: String?) {
        val id = "$host:$port"
        val map = nicknamesFlow().first().toMutableMap()
        if (label.isNullOrBlank()) map.remove(id) else map[id] = label.trim()
        context.dataStore.edit { it[KEY_NICKS] = gson.toJson(map) }
    }
}
