package com.example.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import android.util.Log
import com.example.data.model.FileMeta
import com.example.data.model.SessionState
import com.example.data.model.TransferItem
import com.example.data.model.TransferProgressState
import com.example.data.model.TransferProposal
import com.example.data.model.TransferStatus
import com.example.util.NetworkUtils
import com.example.util.StorageHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

class TransferEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "TransferEngine"

    // High throughput I/O buffer: 256KB chunks for low context switching and high saturation
    private val STREAM_BUFFER_SIZE = 256 * 1024
    private val SOCKET_BUFFER_SIZE = 1024 * 1024 // 1 MB TCP send/receive buffer

    private val _progressState = MutableStateFlow(TransferProgressState())
    val progressState: StateFlow<TransferProgressState> = _progressState.asStateFlow()

    private var activeJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    @Volatile
    private var isCancelled = false

    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Start listening for incoming connections in Receiver mode.
     */
    fun startReceiver(onTransferComplete: (TransferProgressState) -> Unit) {
        cancelActiveTransfer()
        isCancelled = false

        _progressState.value = TransferProgressState(
            sessionState = SessionState.WAITING_ACCEPTANCE,
            isSender = false,
            peerName = "Waiting for sender..."
        )

        activeJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = SOCKET_BUFFER_SIZE
                    bind(InetSocketAddress(NetworkUtils.DEFAULT_TRANSFER_PORT))
                }

                Log.d(TAG, "Receiver server socket listening on ${NetworkUtils.DEFAULT_TRANSFER_PORT}")

                while (isActive && !isCancelled) {
                    val socket = serverSocket?.accept() ?: break
                    activeSocket = socket
                    configureSocket(socket)
                    handleIncomingTransfer(socket, onTransferComplete)
                    break
                }
            } catch (e: Exception) {
                if (!isCancelled) {
                    Log.e(TAG, "Receiver error: ${e.message}", e)
                    _progressState.value = _progressState.value.copy(
                        sessionState = SessionState.ERROR,
                        errorMessage = e.message ?: "Connection error"
                    )
                }
            } finally {
                closeQuietly(serverSocket)
                serverSocket = null
            }
        }
    }

    /**
     * Connect to peer and send selected files in Sender mode.
     */
    fun startSender(
        targetHost: String,
        targetPort: Int,
        peerDeviceName: String,
        selectedFiles: List<TransferItem>,
        onTransferComplete: (TransferProgressState) -> Unit
    ) {
        cancelActiveTransfer()
        isCancelled = false

        val totalBytes = selectedFiles.sumOf { it.size }
        _progressState.value = TransferProgressState(
            sessionState = SessionState.CONNECTING,
            isSender = true,
            peerName = peerDeviceName,
            items = selectedFiles,
            totalBytes = totalBytes
        )

        activeJob = scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket().apply {
                    configureSocket(this)
                }
                activeSocket = socket
                socket.connect(InetSocketAddress(targetHost, targetPort), 12000)

                executeSenderTransfer(socket, peerDeviceName, selectedFiles, totalBytes, onTransferComplete)
            } catch (e: Exception) {
                if (!isCancelled) {
                    Log.e(TAG, "Sender transfer failed: ${e.message}", e)
                    _progressState.value = _progressState.value.copy(
                        sessionState = SessionState.ERROR,
                        errorMessage = e.message ?: "Failed to connect to receiver"
                    )
                }
            } finally {
                closeQuietly(socket)
                activeSocket = null
            }
        }
    }

    private fun configureSocket(socket: Socket) {
        socket.tcpNoDelay = true
        socket.sendBufferSize = SOCKET_BUFFER_SIZE
        socket.receiveBufferSize = SOCKET_BUFFER_SIZE
        socket.keepAlive = true
    }

    /**
     * User confirmation callback for receiver.
     */
    fun respondToProposal(accepted: Boolean) {
        confirmationDeferred?.complete(accepted)
    }

    fun cancelActiveTransfer() {
        isCancelled = true
        confirmationDeferred?.complete(false)
        activeJob?.cancel()
        closeQuietly(activeSocket)
        closeQuietly(serverSocket)
        activeSocket = null
        serverSocket = null

        _progressState.value = _progressState.value.copy(
            sessionState = SessionState.CANCELLED,
            errorMessage = "Transfer cancelled"
        )
    }

    private suspend fun executeSenderTransfer(
        socket: Socket,
        peerDeviceName: String,
        files: List<TransferItem>,
        totalBytes: Long,
        onTransferComplete: (TransferProgressState) -> Unit
    ) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), STREAM_BUFFER_SIZE))

        // 1. Handshake
        out.writeUTF("FK_HANDSHAKE")
        out.writeUTF(NetworkUtils.getFriendlyDeviceName())
        out.flush()

        val ack = input.readUTF()
        if (ack != "FK_HANDSHAKE_ACK") {
            throw IllegalStateException("Invalid handshake from receiver: $ack")
        }
        val remotePeerName = input.readUTF()

        // 2. Proposal
        _progressState.value = _progressState.value.copy(
            sessionState = SessionState.WAITING_ACCEPTANCE,
            peerName = remotePeerName
        )

        out.writeUTF("FK_PROPOSAL")
        out.writeInt(files.size)
        out.writeLong(totalBytes)
        for (f in files) {
            out.writeUTF(f.name)
            out.writeLong(f.size)
            out.writeUTF(f.mimeType)
        }
        out.flush()

        // 3. Await Receiver Acceptance
        val response = input.readUTF()
        if (response != "FK_ACCEPT") {
            _progressState.value = _progressState.value.copy(
                sessionState = SessionState.CANCELLED,
                errorMessage = "Transfer request was declined by $remotePeerName"
            )
            return
        }

        // 4. Start File Streaming
        _progressState.value = _progressState.value.copy(
            sessionState = SessionState.TRANSFERRING
        )

        val updatedItems = files.toMutableList()
        var overallTransferred = 0L
        val startTime = System.nanoTime()
        var lastWindowTime = startTime
        var lastWindowBytes = 0L
        var peakSpeed = 0.0

        val speedTracker = scope.launch(Dispatchers.Default) {
            while (isActive && !isCancelled) {
                delay(300)
                val now = System.nanoTime()
                val windowDurationSec = (now - lastWindowTime) / 1_000_000_000.0
                if (windowDurationSec >= 0.25) {
                    val bytesInWindow = overallTransferred - lastWindowBytes
                    val currentSpeed = (bytesInWindow / (1024.0 * 1024.0)) / windowDurationSec
                    if (currentSpeed > peakSpeed) peakSpeed = currentSpeed

                    val totalDurationSec = (now - startTime) / 1_000_000_000.0
                    val avgSpeed = if (totalDurationSec > 0) {
                        (overallTransferred / (1024.0 * 1024.0)) / totalDurationSec
                    } else 0.0

                    val remainingBytes = (totalBytes - overallTransferred).coerceAtLeast(0L)
                    val remainingSec = if (currentSpeed > 0.05) {
                        (remainingBytes / (currentSpeed * 1024.0 * 1024.0)).toLong()
                    } else 0L

                    lastWindowTime = now
                    lastWindowBytes = overallTransferred

                    _progressState.value = _progressState.value.copy(
                        totalTransferredBytes = overallTransferred,
                        currentSpeedMBps = currentSpeed,
                        peakSpeedMBps = peakSpeed,
                        averageSpeedMBps = avgSpeed,
                        remainingSeconds = remainingSec,
                        elapsedSeconds = totalDurationSec.toLong()
                    )
                }
            }
        }

        try {
            val contentResolver = context.contentResolver
            val buffer = ByteArray(STREAM_BUFFER_SIZE)

            for (i in files.indices) {
                if (isCancelled) break
                val item = files[i]

                _progressState.value = _progressState.value.copy(
                    currentItemIndex = i
                )
                updatedItems[i] = updatedItems[i].copy(status = TransferStatus.TRANSFERRING)
                _progressState.value = _progressState.value.copy(items = updatedItems.toList())

                out.writeUTF("FK_FILE_HEADER")
                out.writeInt(i)
                out.writeUTF(item.name)
                out.writeLong(item.size)
                out.writeUTF(item.mimeType)
                out.flush()

                // Stream file & compute SHA-256 on the fly
                val digest = MessageDigest.getInstance("SHA-256")
                var fileTransferred = 0L

                val inputStream = if (item.uri != null) {
                    contentResolver.openInputStream(item.uri)
                } else null

                if (inputStream == null) {
                    throw IllegalStateException("Cannot open input stream for: ${item.name}")
                }

                inputStream.use { rawIn ->
                    val bin = BufferedInputStream(rawIn, STREAM_BUFFER_SIZE)
                    var bytesRead: Int
                    while (bin.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) break
                        out.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        fileTransferred += bytesRead
                        overallTransferred += bytesRead

                        val itemProgress = if (item.size > 0) (fileTransferred.toFloat() / item.size) else 1f
                        updatedItems[i] = updatedItems[i].copy(
                            progress = itemProgress,
                            transferredBytes = fileTransferred
                        )
                    }
                    out.flush()
                }

                if (isCancelled) break

                val checksumHex = digest.digest().joinToString("") { "%02x".format(it) }
                out.writeUTF("FK_CHECKSUM")
                out.writeUTF(checksumHex)
                out.flush()

                // Await verification ack
                updatedItems[i] = updatedItems[i].copy(status = TransferStatus.VERIFYING)
                _progressState.value = _progressState.value.copy(items = updatedItems.toList())

                val ackCode = input.readUTF()
                if (ackCode == "FK_VERIFIED") {
                    updatedItems[i] = updatedItems[i].copy(
                        status = TransferStatus.COMPLETED,
                        progress = 1f,
                        sha256 = checksumHex
                    )
                } else {
                    updatedItems[i] = updatedItems[i].copy(
                        status = TransferStatus.FAILED,
                        error = "Checksum mismatch detected on receiver"
                    )
                }
                _progressState.value = _progressState.value.copy(items = updatedItems.toList())
            }

            out.writeUTF("FK_ALL_DONE")
            out.flush()

            speedTracker.cancel()
            val finalDuration = (System.nanoTime() - startTime) / 1_000_000_000.0
            val finalAvgSpeed = if (finalDuration > 0) {
                (overallTransferred / (1024.0 * 1024.0)) / finalDuration
            } else 0.0

            val finalState = _progressState.value.copy(
                sessionState = if (isCancelled) SessionState.CANCELLED else SessionState.COMPLETED,
                totalTransferredBytes = overallTransferred,
                averageSpeedMBps = finalAvgSpeed,
                currentSpeedMBps = 0.0,
                remainingSeconds = 0L,
                items = updatedItems.toList()
            )
            _progressState.value = finalState
            onTransferComplete(finalState)

        } finally {
            speedTracker.cancel()
        }
    }

    private suspend fun handleIncomingTransfer(
        socket: Socket,
        onTransferComplete: (TransferProgressState) -> Unit
    ) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), STREAM_BUFFER_SIZE))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE))

        // 1. Handshake
        val handshake = input.readUTF()
        if (handshake != "FK_HANDSHAKE") {
            throw IllegalStateException("Invalid handshake: $handshake")
        }
        val senderDeviceName = input.readUTF()

        out.writeUTF("FK_HANDSHAKE_ACK")
        out.writeUTF(NetworkUtils.getFriendlyDeviceName())
        out.flush()

        // 2. Proposal
        val proposalCmd = input.readUTF()
        if (proposalCmd != "FK_PROPOSAL") {
            throw IllegalStateException("Expected proposal, got: $proposalCmd")
        }
        val fileCount = input.readInt()
        val totalBytes = input.readLong()
        val filesMeta = mutableListOf<FileMeta>()
        for (i in 0 until fileCount) {
            val name = StorageHelper.sanitizeFileName(input.readUTF())
            val size = input.readLong()
            val mime = input.readUTF()
            filesMeta.add(FileMeta(name, size, mime))
        }

        val proposal = TransferProposal(
            senderName = senderDeviceName,
            totalFiles = fileCount,
            totalBytes = totalBytes,
            files = filesMeta
        )

        val receiverItems = filesMeta.map { meta ->
            TransferItem(
                id = UUID.randomUUID().toString(),
                name = meta.name,
                size = meta.size,
                mimeType = meta.mimeType,
                status = TransferStatus.PENDING
            )
        }.toMutableList()

        // 3. User Confirmation Dialog trigger
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred = deferred

        _progressState.value = TransferProgressState(
            sessionState = SessionState.WAITING_ACCEPTANCE,
            isSender = false,
            peerName = senderDeviceName,
            items = receiverItems.toList(),
            totalBytes = totalBytes,
            pendingProposal = proposal
        )

        val accepted = deferred.await()
        confirmationDeferred = null

        if (!accepted || isCancelled) {
            out.writeUTF("FK_REJECT")
            out.flush()
            _progressState.value = _progressState.value.copy(
                sessionState = SessionState.CANCELLED,
                errorMessage = "Transfer declined"
            )
            return
        }

        out.writeUTF("FK_ACCEPT")
        out.flush()

        // 4. Receive File Streams
        _progressState.value = _progressState.value.copy(
            sessionState = SessionState.TRANSFERRING,
            pendingProposal = null
        )

        var overallTransferred = 0L
        val startTime = System.nanoTime()
        var lastWindowTime = startTime
        var lastWindowBytes = 0L
        var peakSpeed = 0.0

        val speedTracker = scope.launch(Dispatchers.Default) {
            while (isActive && !isCancelled) {
                delay(300)
                val now = System.nanoTime()
                val windowDurationSec = (now - lastWindowTime) / 1_000_000_000.0
                if (windowDurationSec >= 0.25) {
                    val bytesInWindow = overallTransferred - lastWindowBytes
                    val currentSpeed = (bytesInWindow / (1024.0 * 1024.0)) / windowDurationSec
                    if (currentSpeed > peakSpeed) peakSpeed = currentSpeed

                    val totalDurationSec = (now - startTime) / 1_000_000_000.0
                    val avgSpeed = if (totalDurationSec > 0) {
                        (overallTransferred / (1024.0 * 1024.0)) / totalDurationSec
                    } else 0.0

                    val remainingBytes = (totalBytes - overallTransferred).coerceAtLeast(0L)
                    val remainingSec = if (currentSpeed > 0.05) {
                        (remainingBytes / (currentSpeed * 1024.0 * 1024.0)).toLong()
                    } else 0L

                    lastWindowTime = now
                    lastWindowBytes = overallTransferred

                    _progressState.value = _progressState.value.copy(
                        totalTransferredBytes = overallTransferred,
                        currentSpeedMBps = currentSpeed,
                        peakSpeedMBps = peakSpeed,
                        averageSpeedMBps = avgSpeed,
                        remainingSeconds = remainingSec,
                        elapsedSeconds = totalDurationSec.toLong()
                    )
                }
            }
        }

        try {
            val buffer = ByteArray(STREAM_BUFFER_SIZE)

            for (i in 0 until fileCount) {
                if (isCancelled) break
                val cmd = input.readUTF()
                if (cmd != "FK_FILE_HEADER") {
                    throw IllegalStateException("Expected file header, got: $cmd")
                }

                val index = input.readInt()
                val rawFileName = input.readUTF()
                val safeFileName = StorageHelper.sanitizeFileName(rawFileName)
                val fileSize = input.readLong()
                val mimeType = input.readUTF()

                _progressState.value = _progressState.value.copy(
                    currentItemIndex = index
                )
                receiverItems[index] = receiverItems[index].copy(status = TransferStatus.TRANSFERRING)
                _progressState.value = _progressState.value.copy(items = receiverItems.toList())

                val outputFile = StorageHelper.createReceivedFile(context, safeFileName)
                val digest = MessageDigest.getInstance("SHA-256")
                var fileTransferred = 0L

                FileOutputStream(outputFile).use { fos ->
                    val bout = BufferedOutputStream(fos, STREAM_BUFFER_SIZE)
                    while (fileTransferred < fileSize) {
                        if (isCancelled) break
                        val toRead = (fileSize - fileTransferred).coerceAtMost(buffer.size.toLong()).toInt()
                        val bytesRead = input.read(buffer, 0, toRead)
                        if (bytesRead == -1) {
                            throw IllegalStateException("Premature EOF while reading $safeFileName")
                        }
                        bout.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        fileTransferred += bytesRead
                        overallTransferred += bytesRead

                        val itemProgress = if (fileSize > 0) (fileTransferred.toFloat() / fileSize) else 1f
                        receiverItems[index] = receiverItems[index].copy(
                            progress = itemProgress,
                            transferredBytes = fileTransferred
                        )
                    }
                    bout.flush()
                }

                if (isCancelled) {
                    outputFile.delete()
                    break
                }

                receiverItems[index] = receiverItems[index].copy(status = TransferStatus.VERIFYING)
                _progressState.value = _progressState.value.copy(items = receiverItems.toList())

                val checksumCmd = input.readUTF()
                if (checksumCmd != "FK_CHECKSUM") {
                    throw IllegalStateException("Expected checksum cmd, got: $checksumCmd")
                }
                val expectedChecksum = input.readUTF()
                val computedChecksum = digest.digest().joinToString("") { "%02x".format(it) }

                if (expectedChecksum.equals(computedChecksum, ignoreCase = true)) {
                    out.writeUTF("FK_VERIFIED")
                    out.flush()
                    receiverItems[index] = receiverItems[index].copy(
                        status = TransferStatus.COMPLETED,
                        progress = 1f,
                        sha256 = computedChecksum,
                        localUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
                    )
                } else {
                    out.writeUTF("FK_CORRUPTED")
                    out.flush()
                    outputFile.delete()
                    receiverItems[index] = receiverItems[index].copy(
                        status = TransferStatus.FAILED,
                        error = "Corrupted file (Checksum mismatch)"
                    )
                }
                _progressState.value = _progressState.value.copy(items = receiverItems.toList())
            }

            input.readUTF() // Read FK_ALL_DONE

            speedTracker.cancel()
            val finalDuration = (System.nanoTime() - startTime) / 1_000_000_000.0
            val finalAvgSpeed = if (finalDuration > 0) {
                (overallTransferred / (1024.0 * 1024.0)) / finalDuration
            } else 0.0

            val finalState = _progressState.value.copy(
                sessionState = if (isCancelled) SessionState.CANCELLED else SessionState.COMPLETED,
                totalTransferredBytes = overallTransferred,
                averageSpeedMBps = finalAvgSpeed,
                currentSpeedMBps = 0.0,
                remainingSeconds = 0L,
                items = receiverItems.toList()
            )
            _progressState.value = finalState
            onTransferComplete(finalState)

        } finally {
            speedTracker.cancel()
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {}
    }
}
