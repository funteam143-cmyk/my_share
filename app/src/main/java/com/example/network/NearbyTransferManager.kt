package com.example.network

import android.content.Context
import android.util.Log
import com.example.data.model.ConnectionType
import com.example.data.model.FileMeta
import com.example.data.model.PeerDevice
import com.example.data.model.SessionState
import com.example.data.model.TransferItem
import com.example.data.model.TransferProgressState
import com.example.data.model.TransferProposal
import com.example.data.model.TransferStatus
import com.example.util.NetworkUtils
import com.example.util.StorageHelper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NearbyTransferManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "FKNearby"
    private val client = Nearby.getConnectionsClient(context)
    private val serviceId = "com.aistudio.fkshare.offline.nearby"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val localName = NetworkUtils.getFriendlyDeviceName()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()
    private val _progress = MutableStateFlow(TransferProgressState())
    val progressState: StateFlow<TransferProgressState> = _progress.asStateFlow()

    private val names = ConcurrentHashMap<String, String>()
    private var receiverMode = false
    private var activeEndpoint: String? = null
    private var senderFiles: List<TransferItem> = emptyList()
    private var senderCompletion: ((TransferProgressState) -> Unit)? = null
    private var receiverCompletion: ((TransferProgressState) -> Unit)? = null
    @Volatile private var cancelled = false

    private var metricsStartedNanos = 0L
    private var metricsLastNanos = 0L
    private var metricsLastBytes = 0L
    private var metricsLastUiNanos = 0L
    private var metricsPeakMBps = 0.0

    private fun beginTransferMetrics() {
        val now = System.nanoTime()
        metricsStartedNanos = now
        metricsLastNanos = now
        metricsLastBytes = 0L
        metricsLastUiNanos = 0L
        metricsPeakMBps = 0.0
    }

    private fun updateTransferMetrics(transferred: Long, items: List<TransferItem>, force: Boolean = false) {
        val now = System.nanoTime()
        if (metricsStartedNanos == 0L) beginTransferMetrics()
        val elapsed = ((now - metricsStartedNanos).coerceAtLeast(1L)).toDouble() / 1_000_000_000.0
        val deltaSeconds = ((now - metricsLastNanos).coerceAtLeast(1L)).toDouble() / 1_000_000_000.0
        val deltaBytes = (transferred - metricsLastBytes).coerceAtLeast(0L)
        val instantMBps = deltaBytes.toDouble() / deltaSeconds / 1_000_000.0
        if (instantMBps > metricsPeakMBps) metricsPeakMBps = instantMBps
        metricsLastNanos = now
        metricsLastBytes = transferred
        if (!force && now - metricsLastUiNanos < 200_000_000L) return
        metricsLastUiNanos = now
        val averageMBps = transferred.toDouble() / elapsed / 1_000_000.0
        val total = _progress.value.totalBytes
        val remainingBytes = (total - transferred).coerceAtLeast(0L)
        val remainingSeconds = if (averageMBps > 0.001) (remainingBytes / (averageMBps * 1_000_000.0)).toLong() else 0L
        _progress.value = _progress.value.copy(
            totalTransferredBytes = transferred,
            currentSpeedMBps = instantMBps.coerceAtLeast(0.0),
            peakSpeedMBps = metricsPeakMBps,
            averageSpeedMBps = averageMBps,
            remainingSeconds = remainingSeconds,
            elapsedSeconds = elapsed.toLong(),
            items = items
        )
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            names[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { Log.e(tag, "accept failed: ${it.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                _progress.value = _progress.value.copy(
                    sessionState = SessionState.ERROR,
                    errorMessage = "Connection failed"
                )
                return
            }
            activeEndpoint = endpointId
            upsertPeer(endpointId, names[endpointId] ?: "Nearby Device")
            if (!receiverMode && senderFiles.isNotEmpty()) {
                scope.launch { sendProposal(endpointId) }
            }
        }

        override fun onDisconnected(endpointId: String) {
            removePeer(endpointId)
            if (activeEndpoint == endpointId) activeEndpoint = null
            if (!cancelled) {
                _progress.value = _progress.value.copy(
                    sessionState = SessionState.ERROR,
                    errorMessage = "Nearby device disconnected"
                )
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != serviceId) return
            names[endpointId] = info.endpointName
            upsertPeer(endpointId, info.endpointName)
        }
        override fun onEndpointLost(endpointId: String) = removePeer(endpointId)
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { handleControl(endpointId, it) }
                Payload.Type.STREAM -> payload.asStream()?.asInputStream()?.let {
                    scope.launch(Dispatchers.IO) { receiveStream(endpointId, it) }
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE && !cancelled) {
                _progress.value = _progress.value.copy(
                    sessionState = SessionState.ERROR,
                    errorMessage = "Nearby transfer failed"
                )
            }
        }
    }

    fun startDiscovery(asReceiver: Boolean) {
        stopDiscovery()
        cancelled = false
        receiverMode = asReceiver
        _peers.value = emptyList()
        if (asReceiver) {
            val options = AdvertisingOptions.Builder().setStrategy(strategy).setLowPower(false).build()
            client.startAdvertising(localName, serviceId, connectionCallback, options)
                .addOnFailureListener {
                    _progress.value = _progress.value.copy(
                        sessionState = SessionState.ERROR,
                        errorMessage = "Nearby advertising failed"
                    )
                }
        } else {
            val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
            client.startDiscovery(serviceId, discoveryCallback, options)
                .addOnFailureListener {
                    _progress.value = _progress.value.copy(
                        sessionState = SessionState.ERROR,
                        errorMessage = "Nearby discovery failed"
                    )
                }
        }
    }

    fun stopDiscovery() {
        client.stopAdvertising()
        client.stopDiscovery()
    }

    fun startReceiver(onTransferComplete: (TransferProgressState) -> Unit) {
        receiverCompletion = onTransferComplete
        cancelled = false
        receiverMode = true
        _progress.value = TransferProgressState(
            sessionState = SessionState.WAITING_ACCEPTANCE,
            isSender = false,
            peerName = "Waiting for sender..."
        )
        startDiscovery(true)
    }

    fun startSender(
        peerId: String,
        peerDeviceName: String,
        selectedFiles: List<TransferItem>,
        onTransferComplete: (TransferProgressState) -> Unit
    ) {
        senderFiles = selectedFiles
        senderCompletion = onTransferComplete
        cancelled = false
        receiverMode = false
        _progress.value = TransferProgressState(
            sessionState = SessionState.CONNECTING,
            isSender = true,
            peerName = peerDeviceName,
            items = selectedFiles,
            totalBytes = selectedFiles.sumOf { it.size }
        )
        val endpoint = peerId.ifBlank { _peers.value.firstOrNull { it.name == peerDeviceName }?.id ?: peerDeviceName }
        activeEndpoint = endpoint
        client.requestConnection(localName, endpoint, connectionCallback)
            .addOnFailureListener {
                _progress.value = _progress.value.copy(
                    sessionState = SessionState.ERROR,
                    errorMessage = "Connection request failed"
                )
            }
    }

    fun connectToEndpoint(endpointId: String) {
        activeEndpoint = endpointId
        client.requestConnection(localName, endpointId, connectionCallback)
            .addOnFailureListener {
                _progress.value = _progress.value.copy(
                    sessionState = SessionState.ERROR,
                    errorMessage = "Connection request failed"
                )
            }
    }

    fun connectFromQr(payload: String) {
        val p = payload.split("|")
        if (p.size < 2 || p[0] != "FK_SHARE_NEARBY") return
        _peers.value.firstOrNull { it.name == p[1].trim() }?.let { connectToEndpoint(it.id) }
    }

    private suspend fun sendProposal(endpointId: String) {
        val text = buildString {
            append("FK_PROPOSAL\n")
            append(senderFiles.size).append('\n')
            append(senderFiles.sumOf { it.size }).append('\n')
            senderFiles.forEach {
                append(it.name.replace("\n", " ")).append('\t')
                append(it.size).append('\t')
                append(it.mimeType.replace("\n", " ")).append('\n')
            }
        }
        _progress.value = _progress.value.copy(sessionState = SessionState.WAITING_ACCEPTANCE)
        client.sendPayload(endpointId, Payload.fromBytes(text.toByteArray()))
    }

    private fun handleControl(endpointId: String, bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        when {
            text == "FK_ACCEPT" -> scope.launch(Dispatchers.IO) { sendStream(endpointId) }
            text == "FK_REJECT" -> {
                _progress.value = _progress.value.copy(
                    sessionState = SessionState.CANCELLED,
                    errorMessage = "Transfer declined"
                )
            }
            text == "FK_DONE" -> {
                _progress.value = _progress.value.copy(sessionState = SessionState.COMPLETED)
                senderCompletion?.invoke(_progress.value)
            }
            text.startsWith("FK_PROPOSAL\n") -> {
                val lines = text.split('\n')
                if (lines.size < 3) return
                val count = lines[1].toIntOrNull() ?: return
                val total = lines[2].toLongOrNull() ?: 0L
                val metas = lines.drop(3).take(count).mapNotNull {
                    val p = it.split('\t')
                    if (p.size >= 3) FileMeta(StorageHelper.sanitizeFileName(p[0]), p[1].toLongOrNull() ?: 0L, p[2]) else null
                }
                val name = names[endpointId] ?: "Nearby Sender"
                val items = metas.map { TransferItem(UUID.randomUUID().toString(), it.name, it.size, it.mimeType) }
                activeEndpoint = endpointId
                _progress.value = TransferProgressState(
                    sessionState = SessionState.WAITING_ACCEPTANCE,
                    isSender = false,
                    peerName = name,
                    items = items,
                    totalBytes = total,
                    pendingProposal = TransferProposal(name, count, total, metas)
                )
            }
        }
    }

    fun respondToProposal(accepted: Boolean) {
        val endpoint = activeEndpoint ?: return
        if (!accepted) {
            client.sendPayload(endpoint, Payload.fromBytes("FK_REJECT".toByteArray()))
            _progress.value = _progress.value.copy(
                sessionState = SessionState.CANCELLED,
                errorMessage = "Transfer declined"
            )
        } else {
            _progress.value = _progress.value.copy(
                sessionState = SessionState.TRANSFERRING,
                pendingProposal = null
            )
            client.sendPayload(endpoint, Payload.fromBytes("FK_ACCEPT".toByteArray()))
        }
    }

    private fun sendStream(endpointId: String) {
        beginTransferMetrics()
        _progress.value = _progress.value.copy(sessionState = SessionState.TRANSFERRING)
        val input = TransferInputStream(context, senderFiles) { transferred ->
            updateTransferMetrics(transferred, progressItems(senderFiles, transferred))
        }
        client.sendPayload(endpointId, Payload.fromStream(input))
            .addOnFailureListener {
                input.close()
                _progress.value = _progress.value.copy(
                    sessionState = SessionState.ERROR,
                    errorMessage = "File transfer failed"
                )
            }
    }

    private fun progressItems(items: List<TransferItem>, transferred: Long): List<TransferItem> {
        var left = transferred
        return items.map {
            val done = left.coerceIn(0L, it.size)
            left -= done
            it.copy(
                progress = if (it.size > 0) done.toFloat() / it.size else 1f,
                transferredBytes = done,
                status = if (done >= it.size) TransferStatus.COMPLETED else TransferStatus.TRANSFERRING
            )
        }
    }

    private suspend fun receiveStream(endpointId: String, input: InputStream) {
        try {
            beginTransferMetrics()
            val data = DataInputStream(BufferedInputStream(input, 256 * 1024))
            if (data.readUTF() != "FK_STREAM") throw IllegalStateException("Invalid transfer stream")
            val count = data.readInt()
            val metas = List(count) {
                FileMeta(StorageHelper.sanitizeFileName(data.readUTF()), data.readLong(), data.readUTF())
            }
            val items = metas.mapIndexed { i, m ->
                TransferItem("rx_$i", m.name, m.size, m.mimeType, status = TransferStatus.PENDING)
            }.toMutableList()
            _progress.value = _progress.value.copy(
                sessionState = SessionState.TRANSFERRING,
                items = items,
                totalBytes = metas.sumOf { it.size },
                pendingProposal = null
            )
            var overall = 0L
            val buffer = ByteArray(256 * 1024)
            for (i in metas.indices) {
                val meta = metas[i]
                val uri = StorageHelper.createReceivedMediaStore(context, meta.name, meta.mimeType)
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                try {
                    StorageHelper.openReceivedOutputStream(context, uri).use { raw ->
                        val out = BufferedOutputStream(raw, 256 * 1024)
                        while (done < meta.size) {
                            val want = minOf(buffer.size.toLong(), meta.size - done).toInt()
                            val n = data.read(buffer, 0, want)
                            if (n < 0) throw IllegalStateException("Transfer ended early")
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            overall += n
                            items[i] = items[i].copy(
                                status = TransferStatus.TRANSFERRING,
                                progress = done.toFloat() / meta.size,
                                transferredBytes = done
                            )
                            updateTransferMetrics(overall, items.toList())
                            _progress.value = _progress.value.copy(currentItemIndex = i)
                        }
                        out.flush()
                    }
                    val expected = data.readUTF()
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!expected.equals(actual, true)) throw IllegalStateException("Checksum mismatch")
                    StorageHelper.finalizeReceived(context, uri)
                    items[i] = items[i].copy(
                        status = TransferStatus.COMPLETED,
                        progress = 1f,
                        transferredBytes = meta.size,
                        sha256 = actual,
                        localUri = uri
                    )
                    _progress.value = _progress.value.copy(items = items.toList())
                } catch (e: Exception) {
                    StorageHelper.deleteReceived(context, uri)
                    _progress.value = _progress.value.copy(
                        sessionState = SessionState.ERROR,
                        errorMessage = e.message ?: "Receive failed"
                    )
                    return
                }
            }
            updateTransferMetrics(overall, items.toList(), force = true)
            _progress.value = _progress.value.copy(
                sessionState = SessionState.COMPLETED,
                totalTransferredBytes = overall,
                items = items.toList()
            )
            client.sendPayload(endpointId, Payload.fromBytes("FK_DONE".toByteArray()))
            receiverCompletion?.invoke(_progress.value)
        } catch (e: Exception) {
            _progress.value = _progress.value.copy(
                sessionState = SessionState.ERROR,
                errorMessage = e.message ?: "Receive failed"
            )
        }
    }

    fun cancelActiveTransfer() {
        cancelled = true
        activeEndpoint?.let { client.disconnectFromEndpoint(it) }
        _progress.value = _progress.value.copy(
            sessionState = SessionState.CANCELLED,
            errorMessage = "Transfer cancelled"
        )
        senderFiles = emptyList()
        activeEndpoint = null
    }

    private fun upsertPeer(id: String, name: String) {
        val list = _peers.value.toMutableList()
        val peer = PeerDevice(
            id = id,
            name = name,
            hostAddress = "Nearby",
            port = NetworkUtils.DEFAULT_TRANSFER_PORT,
            connectionType = ConnectionType.WIFI_DIRECT,
            lastSeen = System.currentTimeMillis()
        )
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = peer else list.add(peer)
        _peers.value = list
    }

    private fun removePeer(id: String) {
        _peers.value = _peers.value.filterNot { it.id == id }
    }

    private class TransferInputStream(
        private val context: Context,
        private val files: List<TransferItem>,
        private val onProgress: (Long) -> Unit
    ) : InputStream() {
        private var current: InputStream? = null
        private var digest: MessageDigest? = null
        private var checksum: ByteArray? = null
        private var checksumPos = 0
        private var index = -1
        private var header: ByteArray? = null
        private var headerPos = 0
        private var total = 0L
        private var lastReportedTotal = 0L
        private var lastReportedNanos = 0L
        private var closed = false

        private fun ensureHeader() {
            if (header != null) return
            val baos = java.io.ByteArrayOutputStream()
            DataOutputStream(baos).use { out ->
                out.writeUTF("FK_STREAM")
                out.writeInt(files.size)
                files.forEach {
                    out.writeUTF(it.name)
                    out.writeLong(it.size)
                    out.writeUTF(it.mimeType)
                }
            }
            header = baos.toByteArray()
        }

        private fun nextFile() {
            index++
            if (index >= files.size) return
            val item = files[index]
            current = item.uri?.let {
                if (it.scheme == "file") {
                    java.io.FileInputStream(it.path ?: throw IllegalStateException("Cannot open ${item.name}"))
                } else {
                    context.contentResolver.openInputStream(it)
                }
            } ?: throw IllegalStateException("Cannot open ${item.name}")
            digest = MessageDigest.getInstance("SHA-256")
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len == 0) return 0
            ensureHeader()
            if (headerPos < header!!.size) {
                val n = minOf(len, header!!.size - headerPos)
                System.arraycopy(header!!, headerPos, b, off, n)
                headerPos += n
                return n
            }
            while (true) {
                if (current == null && checksum == null) {
                    if (index + 1 >= files.size) return -1
                    nextFile()
                }
                if (current != null) {
                    val n = current!!.read(b, off, len)
                    if (n >= 0) {
                        digest!!.update(b, off, n)
                        total += n
                        // Throttle progress callbacks; updating Compose on every read can throttle the actual transfer.
                        val now = System.nanoTime()
                        if (total - lastReportedTotal >= 1_048_576L || now - lastReportedNanos >= 250_000_000L) {
                            lastReportedTotal = total
                            lastReportedNanos = now
                            onProgress(total)
                        }
                        return n
                    }
                    current!!.close()
                    current = null
                    val hex = digest!!.digest().joinToString("") { "%02x".format(it) }
                    val baos = java.io.ByteArrayOutputStream()
                    DataOutputStream(baos).use { it.writeUTF(hex) }
                    checksum = baos.toByteArray()
                    checksumPos = 0
                }
                if (checksum != null) {
                    val c = checksum!!
                    if (checksumPos < c.size) {
                        val n = minOf(len, c.size - checksumPos)
                        System.arraycopy(c, checksumPos, b, off, n)
                        checksumPos += n
                        if (checksumPos == c.size) {
                            checksum = null
                            checksumPos = 0
                        }
                        return n
                    }
                    checksum = null
                }
            }
        }

        override fun close() {
            closed = true
            current?.close()
            current = null
        }
    }
}
