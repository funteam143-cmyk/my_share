package com.example.ui

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.TransferRecord
import com.example.data.db.TransferRepository
import com.example.data.model.PeerDevice
import com.example.data.model.SessionState
import com.example.data.model.TransferItem
import com.example.data.model.TransferProgressState
import com.example.data.model.TransferStatus
import com.example.network.NearbyTransferManager
import com.example.util.NetworkUtils
import com.example.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Screen {
    MAIN,
    FILE_PICKER,
    DISCOVERY,
    RECEIVER_WAITING,
    TRANSFER,
    COMPLETED,
    HISTORY
}

enum class MediaTab {
    ALL,
    PHOTOS,
    VIDEOS,
    DOCUMENTS,
    AUDIO,
    APPS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TransferRepository(AppDatabase.getInstance(application).transferRecordDao())
    val recentRecords: StateFlow<List<TransferRecord>> = repository.recentRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allRecords: StateFlow<List<TransferRecord>> = repository.allRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nearbyManager = NearbyTransferManager(application, viewModelScope)

    val peers: StateFlow<List<PeerDevice>> = nearbyManager.peers
    val transferProgress: StateFlow<TransferProgressState> = nearbyManager.progressState

    private val _currentScreen = MutableStateFlow(Screen.MAIN)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<TransferItem>>(emptyList())
    val selectedFiles: StateFlow<List<TransferItem>> = _selectedFiles.asStateFlow()

    private val _localDeviceName = MutableStateFlow(NetworkUtils.getFriendlyDeviceName())
    val localDeviceName: StateFlow<String> = _localDeviceName.asStateFlow()

    private val _localIp = MutableStateFlow(NetworkUtils.getLocalIpAddress() ?: "Offline")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _scannedMedia = MutableStateFlow<List<TransferItem>>(emptyList())
    val scannedMedia: StateFlow<List<TransferItem>> = _scannedMedia.asStateFlow()

    private val _selectedTab = MutableStateFlow(MediaTab.PHOTOS)
    val selectedTab: StateFlow<MediaTab> = _selectedTab.asStateFlow()

    private val _isLoadingMedia = MutableStateFlow(false)
    val isLoadingMedia: StateFlow<Boolean> = _isLoadingMedia.asStateFlow()

    private val _lastCompletedTransfer = MutableStateFlow<TransferProgressState?>(null)
    val lastCompletedTransfer: StateFlow<TransferProgressState?> = _lastCompletedTransfer.asStateFlow()

    init {
        refreshNetworkState()
        loadLocalMedia(MediaTab.PHOTOS)
    }

    fun refreshNetworkState() {
        _localIp.value = NetworkUtils.getLocalIpAddress() ?: "Offline"
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
        if (screen == Screen.DISCOVERY) {
            nearbyManager.startDiscovery(asReceiver = false)
        } else if (screen == Screen.RECEIVER_WAITING) {
            startReceiverServer()
        } else {
            if (_currentScreen.value != Screen.TRANSFER) {
                nearbyManager.stopDiscovery()
            }
        }
    }

    fun selectTab(tab: MediaTab) {
        _selectedTab.value = tab
        loadLocalMedia(tab)
    }

    fun addFilesFromUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val newItems = uris.map { uri ->
                val (name, size) = StorageHelper.resolveUriMetadata(contentResolver, uri)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                TransferItem(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    size = size,
                    mimeType = mime,
                    uri = uri,
                    status = TransferStatus.PENDING
                )
            }
            val current = _selectedFiles.value.toMutableList()
            current.addAll(newItems)
            _selectedFiles.value = current
        }
    }

    fun toggleFileSelection(item: TransferItem) {
        val current = _selectedFiles.value.toMutableList()
        val existing = current.indexOfFirst { it.id == item.id || (it.uri != null && it.uri == item.uri) }
        if (existing >= 0) {
            current.removeAt(existing)
        } else {
            current.add(item)
        }
        _selectedFiles.value = current
    }

    fun removeSelectedFile(item: TransferItem) {
        val current = _selectedFiles.value.toMutableList()
        current.removeAll { it.id == item.id }
        _selectedFiles.value = current
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
    }

    fun connectAndSend(peer: PeerDevice) {
        val filesToSend = _selectedFiles.value
        if (filesToSend.isEmpty()) return

        discoveryManager.stopDiscovery()
        _currentScreen.value = Screen.TRANSFER

        nearbyManager.startSender(
            targetHost = peer.hostAddress,
            targetPort = peer.port,
            peerDeviceName = peer.name,
            selectedFiles = filesToSend,
            onTransferComplete = { finalState ->
                onTransferFinished(finalState)
            }
        )
    }

    private fun startReceiverServer() {
        nearbyManager.startReceiver { finalState ->
            onTransferFinished(finalState)
        }
    }

    fun acceptIncomingTransfer() {
        nearbyManager.respondToProposal(true)
        _currentScreen.value = Screen.TRANSFER
    }

    fun declineIncomingTransfer() {
        nearbyManager.respondToProposal(false)
        _currentScreen.value = Screen.MAIN
    }

    fun cancelActiveTransfer() {
        nearbyManager.cancelActiveTransfer()
    }

    private fun onTransferFinished(state: TransferProgressState) {
        _lastCompletedTransfer.value = state
        if (state.sessionState == SessionState.COMPLETED) {
            _currentScreen.value = Screen.COMPLETED
            // Persist to Room
            viewModelScope.launch(Dispatchers.IO) {
                val record = TransferRecord(
                    peerName = state.peerName.ifEmpty { "Unknown Peer" },
                    isOutgoing = state.isSender,
                    fileCount = state.items.size,
                    totalBytes = state.totalBytes,
                    averageSpeedMBps = state.averageSpeedMBps,
                    peakSpeedMBps = state.peakSpeedMBps,
                    durationSeconds = state.elapsedSeconds,
                    status = "COMPLETED",
                    fileNamesSummary = state.items.joinToString(", ") { it.name }.take(250)
                )
                repository.saveRecord(record)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
        }
    }

    fun addDirectPeer(ip: String) {
        peers.value.firstOrNull { it.hostAddress == ip }?.let { nearbyManager.connectToEndpoint(it.id) }
    }

    fun connectFromQr(payload: String) {
        val filesToSend = _selectedFiles.value
        if (filesToSend.isEmpty()) return

        nearbyManager.connectFromQr(payload)
    }

    private fun loadLocalMedia(tab: MediaTab) {
        if (tab == MediaTab.ALL) {
            _scannedMedia.value = emptyList()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMedia.value = true
            val items = mutableListOf<TransferItem>()
            val context = getApplication<Application>()
            val contentResolver = context.contentResolver

            try {
                when (tab) {
                    MediaTab.PHOTOS -> {
                        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.MIME_TYPE)
                        contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            "${MediaStore.Images.Media.DATE_ADDED} DESC"
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                            val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                            var count = 0
                            if (cursor.moveToFirst()) do {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "Image_$id"
                                val size = cursor.getLong(sizeCol)
                                val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg"
                                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                items.add(TransferItem(id = id.toString(), name = name, size = size, mimeType = mime, uri = uri))
                                count++
                            } while (cursor.moveToNext() && count < 60)
                        }
                    }
                    MediaTab.VIDEOS -> {
                        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE)
                        contentResolver.query(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            "${MediaStore.Video.Media.DATE_ADDED} DESC"
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                            val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                            var count = 0
                            if (cursor.moveToFirst()) do {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "Video_$id"
                                val size = cursor.getLong(sizeCol)
                                val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                                items.add(TransferItem(id = id.toString(), name = name, size = size, mimeType = mime, uri = uri))
                                count++
                            } while (cursor.moveToNext() && count < 40)
                        }
                    }
                    MediaTab.AUDIO -> {
                        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE)
                        contentResolver.query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                            val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                            var count = 0
                            if (cursor.moveToFirst()) do {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "Audio_$id"
                                val size = cursor.getLong(sizeCol)
                                val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "audio/mpeg" else "audio/mpeg"
                                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                items.add(TransferItem(id = id.toString(), name = name, size = size, mimeType = mime, uri = uri))
                                count++
                            } while (cursor.moveToNext() && count < 40)
                        }
                    }
                    MediaTab.DOCUMENTS -> {
                        // Documents are selected through SAF.
                    }
                    MediaTab.APPS -> {
                        val pm = context.packageManager
                        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                            .distinctBy { it.activityInfo.packageName }
                            .sortedBy { it.loadLabel(pm).toString().lowercase() }
                        for (resolveInfo in apps.take(200)) {
                            val appInfo = resolveInfo.activityInfo.applicationInfo
                            val apk = appInfo.sourceDir?.let(::java.io.File) ?: continue
                            if (!apk.exists() || apk.length() <= 0L) continue
                            val label = resolveInfo.loadLabel(pm).toString().ifBlank { appInfo.packageName }
                            items.add(
                                TransferItem(
                                    id = "app:${appInfo.packageName}",
                                    name = "$label.apk",
                                    size = apk.length(),
                                    mimeType = "application/vnd.android.package-archive",
                                    uri = Uri.fromFile(apk),
                                    status = TransferStatus.PENDING
                                )
                            )
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "Media query failed: ${e.message}")
            } finally {
                _scannedMedia.value = items
                _isLoadingMedia.value = false
            }
        }
    }
}
