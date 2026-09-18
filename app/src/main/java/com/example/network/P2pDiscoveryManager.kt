package com.example.network

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.example.data.model.ConnectionType
import com.example.data.model.PeerDevice
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.UUID

class P2pDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "P2pDiscovery"
    private val localDeviceId = UUID.randomUUID().toString().take(8)
    private val localDeviceName = NetworkUtils.getFriendlyDeviceName()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var cleanupJob: Job? = null
    private var listenSocket: DatagramSocket? = null

    // Wi-Fi Direct support
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    init {
        try {
            p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            p2pChannel = p2pManager?.initialize(context, context.mainLooper, null)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi Direct initialize failed: ${e.message}")
        }
    }

    fun startDiscovery(asReceiver: Boolean = false) {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        _peers.value = emptyList()

        startBeaconBroadcaster(asReceiver)
        startBeaconListener()
        startPruningJob()

        // Also initiate Wi-Fi Direct discovery if supported
        try {
            p2pChannel?.let { channel ->
                p2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Wi-Fi Direct peer discovery started")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Wi-Fi Direct peer discovery failed code: $reason")
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2P discover error: ${e.message}")
        }
    }

    fun stopDiscovery() {
        _isDiscovering.value = false
        broadcastJob?.cancel()
        listenJob?.cancel()
        cleanupJob?.cancel()

        try {
            listenSocket?.close()
        } catch (_: Exception) {}
        listenSocket = null

        try {
            p2pChannel?.let { channel ->
                p2pManager?.stopPeerDiscovery(channel, null)
            }
        } catch (_: Exception) {}
    }

    private fun startBeaconBroadcaster(asReceiver: Boolean) {
        broadcastJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true

                while (isActive && _isDiscovering.value) {
                    val localIp = NetworkUtils.getLocalIpAddress()
                    if (localIp != null) {
                        // Protocol: FK_BEACON|ID|NAME|IP|PORT|ROLE
                        val role = if (asReceiver) "RECEIVER" else "SENDER"
                        val payload = "FK_BEACON|$localDeviceId|$localDeviceName|$localIp|${NetworkUtils.DEFAULT_TRANSFER_PORT}|$role"
                        val data = payload.toByteArray(Charsets.UTF_8)
                        val broadcastAddr = NetworkUtils.getBroadcastAddress()
                        val packet = DatagramPacket(data, data.size, broadcastAddr, NetworkUtils.DEFAULT_BEACON_PORT)
                        try {
                            socket.send(packet)
                        } catch (e: Exception) {
                            Log.v(TAG, "Beacon broadcast send error: ${e.message}")
                        }
                    }
                    delay(1200)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Beacon broadcaster terminated: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    private fun startBeaconListener() {
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                listenSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(NetworkUtils.DEFAULT_BEACON_PORT))
                    broadcast = true
                }

                val buffer = ByteArray(2048)
                while (isActive && _isDiscovering.value) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        listenSocket?.receive(packet)
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parseBeacon(message, packet.address.hostAddress ?: "")
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.v(TAG, "Beacon receive error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Beacon listener stopped: ${e.message}")
            }
        }
    }

    private fun parseBeacon(message: String, senderHost: String) {
        val parts = message.split("|")
        if (parts.size >= 6 && parts[0] == "FK_BEACON") {
            val peerId = parts[1]
            if (peerId == localDeviceId) return // Ignore self

            val peerName = parts[2]
            val peerIp = senderHost.ifEmpty { parts[3] }
            val peerPort = parts[4].toIntOrNull() ?: NetworkUtils.DEFAULT_TRANSFER_PORT

            val updated = PeerDevice(
                id = peerId,
                name = peerName,
                hostAddress = peerIp,
                port = peerPort,
                connectionType = ConnectionType.LOCAL_WIFI,
                lastSeen = System.currentTimeMillis()
            )

            val current = _peers.value.toMutableList()
            val index = current.indexOfFirst { it.id == peerId || it.hostAddress == peerIp }
            if (index >= 0) {
                current[index] = updated
            } else {
                current.add(updated)
            }
            _peers.value = current
        }
    }

    private fun startPruningJob() {
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isDiscovering.value) {
                delay(4000)
                val now = System.currentTimeMillis()
                val active = _peers.value.filter { now - it.lastSeen < 12000 }
                if (active.size != _peers.value.size) {
                    _peers.value = active
                }
            }
        }
    }

    fun addDirectPeer(ipAddress: String, name: String = "Direct Peer") {
        val newPeer = PeerDevice(
            id = "direct_${ipAddress.replace(".", "_")}",
            name = name,
            hostAddress = ipAddress,
            port = NetworkUtils.DEFAULT_TRANSFER_PORT,
            connectionType = ConnectionType.HOTSPOT,
            lastSeen = System.currentTimeMillis()
        )
        val current = _peers.value.toMutableList()
        current.removeAll { it.hostAddress == ipAddress }
        current.add(0, newPeer)
        _peers.value = current
    }
}
