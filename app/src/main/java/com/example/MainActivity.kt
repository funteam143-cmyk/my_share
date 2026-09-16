package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.MainViewModel
import com.example.ui.Screen
import com.example.ui.screens.CompletedScreen
import com.example.ui.screens.DiscoveryScreen
import com.example.ui.screens.FilePickerScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.ReceiverWaitingScreen
import com.example.ui.screens.TransferScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val localDeviceName by viewModel.localDeviceName.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val recentRecords by viewModel.recentRecords.collectAsState()
    val allRecords by viewModel.allRecords.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val scannedMedia by viewModel.scannedMedia.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isLoadingMedia by viewModel.isLoadingMedia.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val progressState by viewModel.transferProgress.collectAsState()
    val lastCompleted by viewModel.lastCompletedTransfer.collectAsState()

    // Permissions check for nearby P2P Wi-Fi
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshNetworkState()
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    // Handle System Back Press
    BackHandler(enabled = currentScreen != Screen.MAIN) {
        when (currentScreen) {
            Screen.TRANSFER -> {
                viewModel.cancelActiveTransfer()
                viewModel.navigateTo(Screen.MAIN)
            }
            Screen.COMPLETED, Screen.HISTORY, Screen.DISCOVERY, Screen.RECEIVER_WAITING, Screen.FILE_PICKER -> {
                viewModel.navigateTo(Screen.MAIN)
            }
            else -> {}
        }
    }

    when (currentScreen) {
        Screen.MAIN -> {
            MainScreen(
                localDeviceName = localDeviceName,
                localIp = localIp,
                recentRecords = recentRecords,
                onNavigate = { viewModel.navigateTo(it) },
                onRefreshNetwork = { viewModel.refreshNetworkState() }
            )
        }
        Screen.FILE_PICKER -> {
            FilePickerScreen(
                selectedFiles = selectedFiles,
                scannedMedia = scannedMedia,
                selectedTab = selectedTab,
                isLoadingMedia = isLoadingMedia,
                onTabSelected = { viewModel.selectTab(it) },
                onFileToggled = { viewModel.toggleFileSelection(it) },
                onFilesAdded = { viewModel.addFilesFromUris(it) },
                onClearSelected = { viewModel.clearSelectedFiles() },
                onBack = { viewModel.navigateTo(Screen.MAIN) },
                onProceedToSend = { viewModel.navigateTo(Screen.DISCOVERY) }
            )
        }
        Screen.DISCOVERY -> {
            DiscoveryScreen(
                peers = peers,
                selectedFiles = selectedFiles,
                onConnectPeer = { peer -> viewModel.connectAndSend(peer) },
                onAddDirectPeer = { ip -> viewModel.addDirectPeer(ip) },
                onBack = { viewModel.navigateTo(Screen.FILE_PICKER) }
            )
        }
        Screen.RECEIVER_WAITING -> {
            ReceiverWaitingScreen(
                localDeviceName = localDeviceName,
                localIp = localIp,
                progressState = progressState,
                onAcceptTransfer = { viewModel.acceptIncomingTransfer() },
                onDeclineTransfer = { viewModel.declineIncomingTransfer() },
                onBack = { viewModel.navigateTo(Screen.MAIN) }
            )
        }
        Screen.TRANSFER -> {
            TransferScreen(
                progressState = progressState,
                onCancelTransfer = {
                    viewModel.cancelActiveTransfer()
                    viewModel.navigateTo(Screen.MAIN)
                }
            )
        }
        Screen.COMPLETED -> {
            CompletedScreen(
                transferState = lastCompleted,
                onDone = { viewModel.navigateTo(Screen.MAIN) },
                onSendMore = {
                    viewModel.clearSelectedFiles()
                    viewModel.navigateTo(Screen.FILE_PICKER)
                }
            )
        }
        Screen.HISTORY -> {
            HistoryScreen(
                records = allRecords,
                onClearHistory = { viewModel.clearHistory() },
                onBack = { viewModel.navigateTo(Screen.MAIN) }
            )
        }
    }
}
