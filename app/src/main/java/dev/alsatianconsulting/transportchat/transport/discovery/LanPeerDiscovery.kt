package dev.alsatianconsulting.transportchat.transport.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dev.alsatianconsulting.transportchat.data.model.DiscoveredPeer
import dev.alsatianconsulting.transportchat.data.model.SignalPreKeyBundleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

class LanPeerDiscovery(
    private val context: Context,
    private val localAnnouncementProvider: () -> PeerAnnouncement,
    private val onPeerDiscovered: (DiscoveredPeer) -> Unit,
    private val knownPeerHostsProvider: () -> List<String> = { emptyList() }
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var senderJob: Job? = null
    private var receiverJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (senderJob?.isActive == true || receiverJob?.isActive == true) return
        ensureMulticastLock()

        senderJob = scope.launch {
            val sender = DatagramSocket().apply { broadcast = true }
            while (isActive) {
                runCatching { broadcastAnnouncement(sender) }
                    .onFailure { Log.w(TAG, "Discovery send failure", it) }
                delay(BROADCAST_INTERVAL_MS)
            }
            sender.close()
        }

        receiverJob = scope.launch {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
            }

            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val payload = packet.data.copyOf(packet.length).toString(Charsets.UTF_8)
                    val announcement = parseAnnouncement(payload) ?: continue
                    val local = localAnnouncementProvider()
                    if (announcement.profileId == local.profileId) continue

                    onPeerDiscovered(
                        DiscoveredPeer(
                            profileId = announcement.profileId,
                            displayName = announcement.displayName,
                            publicKey = announcement.publicKey,
                            safetyPhrase = announcement.safetyPhrase,
                            signalBundle = SignalPreKeyBundleData(
                                registrationId = announcement.signalRegistrationId,
                                deviceId = announcement.signalDeviceId,
                                preKeyId = announcement.signalPreKeyId,
                                preKeyPublicKey = announcement.signalPreKeyPublicKey,
                                signedPreKeyId = announcement.signalSignedPreKeyId,
                                signedPreKeyPublicKey = announcement.signalSignedPreKeyPublicKey,
                                signedPreKeySignature = announcement.signalSignedPreKeySignature
                            ),
                            host = packet.address.hostAddress ?: continue,
                            port = announcement.messagePort,
                            viaInterface = packet.address.hostAddress ?: "unknown",
                            discoveredAtEpochMs = System.currentTimeMillis()
                        )
                    )
                } catch (_: Exception) {
                    // Timeout or parse issue; continue listening loop.
                }
            }
            socket.close()
        }
    }

    fun stop() {
        senderJob?.cancel()
        receiverJob?.cancel()
        senderJob = null
        receiverJob = null
        releaseMulticastLock()
    }

    private fun broadcastAnnouncement(sender: DatagramSocket) {
        val announcement = localAnnouncementProvider()
        val json = JSONObject()
            .put("profileId", announcement.profileId)
            .put("displayName", announcement.displayName)
            .put("publicKey", announcement.publicKey)
            .put("safetyPhrase", announcement.safetyPhrase)
            .put("signalRegistrationId", announcement.signalRegistrationId)
            .put("signalDeviceId", announcement.signalDeviceId)
            .put("signalPreKeyId", announcement.signalPreKeyId)
            .put("signalPreKeyPublicKey", announcement.signalPreKeyPublicKey)
            .put("signalSignedPreKeyId", announcement.signalSignedPreKeyId)
            .put("signalSignedPreKeyPublicKey", announcement.signalSignedPreKeyPublicKey)
            .put("signalSignedPreKeySignature", announcement.signalSignedPreKeySignature)
            .put("messagePort", announcement.messagePort)
            .toString()
        val payload = json.toByteArray(Charsets.UTF_8)

        val multicast = DatagramPacket(
            payload,
            payload.size,
            InetAddress.getByName(MULTICAST_ADDRESS),
            DISCOVERY_PORT
        )
        sender.send(multicast)

        interfacesWithIpv4().forEach { networkInterface ->
            networkInterface.interfaceAddresses
                .mapNotNull { it.broadcast }
                .forEach { broadcastAddress ->
                    runCatching {
                        sender.send(DatagramPacket(payload, payload.size, broadcastAddress, DISCOVERY_PORT))
                    }.onFailure {
                        Log.d(TAG, "Broadcast failed for ${networkInterface.displayName}", it)
                    }
                }

            runCatching {
                MulticastSocket().use { multicastSocket ->
                    multicastSocket.networkInterface = networkInterface
                    multicastSocket.send(
                        DatagramPacket(
                            payload,
                            payload.size,
                            InetAddress.getByName(MULTICAST_ADDRESS),
                            DISCOVERY_PORT
                        )
                    )
                }
            }
        }

        knownPeerHostsProvider().forEach { host ->
            runCatching {
                sender.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), DISCOVERY_PORT))
            }.onFailure {
                Log.d(TAG, "Unicast announcement failed for $host", it)
            }
        }
    }

    private fun parseAnnouncement(payload: String): PeerAnnouncement? {
        return runCatching {
            val json = JSONObject(payload)
            PeerAnnouncement(
                profileId = json.getString("profileId"),
                displayName = json.getString("displayName"),
                publicKey = json.getString("publicKey"),
                safetyPhrase = json.getString("safetyPhrase"),
                signalRegistrationId = json.getInt("signalRegistrationId"),
                signalDeviceId = json.optInt("signalDeviceId", 1),
                signalPreKeyId = json.getInt("signalPreKeyId"),
                signalPreKeyPublicKey = json.getString("signalPreKeyPublicKey"),
                signalSignedPreKeyId = json.getInt("signalSignedPreKeyId"),
                signalSignedPreKeyPublicKey = json.getString("signalSignedPreKeyPublicKey"),
                signalSignedPreKeySignature = json.getString("signalSignedPreKeySignature"),
                messagePort = json.optInt("messagePort", DEFAULT_MESSAGE_PORT)
            )
        }.getOrNull()
    }

    private fun interfacesWithIpv4(): List<NetworkInterface> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        return interfaces.filter { networkInterface ->
            runCatching {
                networkInterface.isUp &&
                    !networkInterface.isLoopback &&
                    networkInterface.inetAddresses.toList().any { address ->
                        address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress
                    }
            }.getOrDefault(false)
        }
    }

    private fun ensureMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val manager = context.getSystemService(WifiManager::class.java) ?: return
        multicastLock = manager.createMulticastLock("transportchat:discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    companion object {
        private const val TAG = "LanPeerDiscovery"
        private const val MULTICAST_ADDRESS = "239.255.22.5"
        private const val DISCOVERY_PORT = 51918
        private const val DEFAULT_MESSAGE_PORT = 53990
        private const val BROADCAST_INTERVAL_MS = 8_000L
        private const val SOCKET_TIMEOUT_MS = 1_500
        private const val MAX_PACKET_BYTES = 4 * 1024
    }
}
