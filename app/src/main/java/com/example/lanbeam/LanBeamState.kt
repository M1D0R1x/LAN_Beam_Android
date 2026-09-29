package com.example.lanbeam

import com.example.lanbeam.server.LanAddresses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide server state shared by the service (writer) and the UI (reader). No binding needed. */
object LanBeamState {

    data class Status(
        val running: Boolean = false,
        val error: String? = null,
        val addresses: List<LanAddresses.LanAddress> = emptyList(),
        val port: Int = LanBeamService.HTTP_PORT,
        /** Files being copied in from the share sheet / picker. */
        val importing: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Bumped whenever files are added/removed so lists refresh. */
    private val _filesVersion = MutableStateFlow(0L)
    val filesVersion: StateFlow<Long> = _filesVersion.asStateFlow()

    fun update(block: (Status) -> Status) = _status.update(block)
    fun filesChanged() = _filesVersion.update { it + 1 }
}
