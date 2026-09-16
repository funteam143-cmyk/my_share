package com.example.data.model

import android.net.Uri

enum class TransferStatus {
    PENDING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ConnectionType {
    WIFI_DIRECT,
    LOCAL_WIFI,
    HOTSPOT
}

data class PeerDevice(
    val id: String,
    val name: String,
    val hostAddress: String,
    val port: Int = 52346,
    val connectionType: ConnectionType = ConnectionType.LOCAL_WIFI,
    val isGroupOwner: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

data class TransferItem(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val uri: Uri? = null,
    val progress: Float = 0f,
    val transferredBytes: Long = 0L,
    val sha256: String? = null,
    val status: TransferStatus = TransferStatus.PENDING,
    val localUri: Uri? = null,
    val error: String? = null
)

data class TransferProposal(
    val senderName: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val files: List<FileMeta>
)

data class FileMeta(
    val name: String,
    val size: Long,
    val mimeType: String,
    val sha256: String? = null
)

enum class SessionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    WAITING_ACCEPTANCE,
    TRANSFERRING,
    COMPLETED,
    CANCELLED,
    ERROR
}

data class TransferProgressState(
    val sessionState: SessionState = SessionState.IDLE,
    val isSender: Boolean = true,
    val peerName: String = "",
    val items: List<TransferItem> = emptyList(),
    val currentItemIndex: Int = 0,
    val totalBytes: Long = 0L,
    val totalTransferredBytes: Long = 0L,
    val currentSpeedMBps: Double = 0.0,
    val peakSpeedMBps: Double = 0.0,
    val averageSpeedMBps: Double = 0.0,
    val remainingSeconds: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val errorMessage: String? = null,
    val pendingProposal: TransferProposal? = null
)
