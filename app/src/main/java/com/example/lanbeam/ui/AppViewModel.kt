package com.example.lanbeam.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lanbeam.DeviceName
import com.example.lanbeam.LanBeamService
import com.example.lanbeam.LanBeamState
import com.example.lanbeam.Network
import com.example.lanbeam.StorageDirs
import com.example.lanbeam.server.FileTypes
import com.example.lanbeam.server.TransferTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FileItem(val file: File, val name: String, val size: Long, val modified: Long, val type: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val status = LanBeamState.status
    val transfers = TransferTracker.transfers

    private val _shared = MutableStateFlow<List<FileItem>>(emptyList())
    val shared: StateFlow<List<FileItem>> = _shared.asStateFlow()

    private val _received = MutableStateFlow<List<FileItem>>(emptyList())
    val received: StateFlow<List<FileItem>> = _received.asStateFlow()

    private val _deviceName = MutableStateFlow(DeviceName.get(app))
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _needsLegacyStorage = MutableStateFlow(StorageDirs.needsLegacyPermission(app))
    val needsLegacyStorage: StateFlow<Boolean> = _needsLegacyStorage.asStateFlow()

    private val _receivedLocation = MutableStateFlow(StorageDirs.describeReceived(app))
    val receivedLocation: StateFlow<String> = _receivedLocation.asStateFlow()

    private val prefs = app.getSharedPreferences("lanbeam_prefs", android.content.Context.MODE_PRIVATE)
    private val _onboarded = MutableStateFlow(prefs.getBoolean("onboarded_v1", false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun finishOnboarding() {
        prefs.edit().putBoolean("onboarded_v1", true).apply()
        _onboarded.value = true
    }

    init {
        viewModelScope.launch { LanBeamState.filesVersion.collect { refreshFiles() } }
    }

    private val ctx get() = getApplication<Application>()

    fun onResume() {
        StorageDirs.resetProbe()
        _needsLegacyStorage.value = StorageDirs.needsLegacyPermission(ctx)
        _receivedLocation.value = StorageDirs.describeReceived(ctx)
        refreshAddresses()
        refreshFiles()
    }

    fun refreshAddresses() {
        viewModelScope.launch {
            val addrs = withContext(Dispatchers.IO) { Network.addresses(ctx) }
            LanBeamState.update { it.copy(addresses = addrs) }
        }
    }

    fun refreshFiles() {
        viewModelScope.launch {
            val (s, r) = withContext(Dispatchers.IO) { list(StorageDirs.shared(ctx)) to list(StorageDirs.uploads(ctx)) }
            _shared.value = s
            _received.value = r
        }
    }

    private fun list(dir: File): List<FileItem> =
        dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            .map { FileItem(it, it.name, if (it.isFile) it.length() else 0, it.lastModified(), if (it.isDirectory) "folder" else FileTypes.typeOf(it.name)) }
            .sortedByDescending { it.modified }

    fun start() = LanBeamService.start(ctx)
    fun stop() = LanBeamService.stop(ctx)

    fun addFiles(uris: List<Uri>) = LanBeamService.import(ctx, uris)

    fun delete(item: FileItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (item.file.isDirectory) item.file.deleteRecursively() else item.file.delete() }
            LanBeamState.filesChanged()
        }
    }

    fun clearShared() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { StorageDirs.shared(ctx).listFiles()?.forEach { it.deleteRecursively() } }
            LanBeamState.filesChanged()
        }
    }

    fun setDeviceName(name: String) {
        if (name.isBlank()) return
        DeviceName.set(ctx, name)
        _deviceName.value = name.trim()
    }
}
