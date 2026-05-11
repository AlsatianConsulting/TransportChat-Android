package dev.alsatianconsulting.transportchat.data.repo

import dev.alsatianconsulting.transportchat.data.model.DiscoveredPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PeerRepository {
    private val peersFlow = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    fun peers(): StateFlow<List<DiscoveredPeer>> = peersFlow.asStateFlow()

    fun upsert(peer: DiscoveredPeer) {
        val current = peersFlow.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.profileId == peer.profileId }
        if (existingIndex >= 0) {
            current[existingIndex] = peer
        } else {
            current += peer
        }
        peersFlow.value = current.sortedBy { it.displayName.lowercase() }
    }

    fun freshestByEndpoint(host: String, port: Int): DiscoveredPeer? {
        val normalizedHost = host.trim().lowercase()
        return peersFlow.value
            .asSequence()
            .filter { it.port == port && it.host.trim().lowercase() == normalizedHost }
            .maxByOrNull { it.discoveredAtEpochMs }
    }

    fun removeByProfileId(profileId: String) {
        peersFlow.value = peersFlow.value.filterNot { it.profileId == profileId }
    }

    fun clear() {
        peersFlow.value = emptyList()
    }
}
