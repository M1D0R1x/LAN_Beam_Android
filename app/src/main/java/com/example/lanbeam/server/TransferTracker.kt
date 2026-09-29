package com.example.lanbeam.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide view of transfers in flight, so the app UI, the notification and the
 * wake/Wi-Fi locks all see the same thing. Updates are throttled to ~4/s per transfer.
 */
object TransferTracker {

    enum class Direction { DOWNLOAD, UPLOAD, IMPORT }

    data class Transfer(
        val id: Long,
        val name: String,
        val direction: Direction,
        val peer: String,
        val total: Long,
        val bytes: Long,
        val startedAt: Long,
        val bytesPerSec: Double,
        val finishedAt: Long? = null,
        val failed: Boolean = false,
    ) {
        val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val ids = AtomicLong()
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val active = ConcurrentHashMap<Long, Handle>()

    /** Fires with the number of active transfers whenever it changes (drives wake locks). */
    @Volatile var onActiveCountChanged: ((Int) -> Unit)? = null

    fun activeCount(): Int = active.size

    fun begin(name: String, direction: Direction, peer: String, total: Long, alreadyDone: Long = 0): Handle {
        val h = Handle(ids.incrementAndGet(), name, direction, peer, total, alreadyDone)
        active[h.id] = h
        publish(h, force = true)
        onActiveCountChanged?.invoke(active.size)
        return h
    }

    /**
     * One logical transfer often spans many HTTP requests: a resumable upload is a series of
     * chunks, and download managers fetch one file as several parallel ranges. Requests that share
     * a [key] feed one row, which ends when the last of them releases it.
     */
    private class Shared(val handle: Handle, var refs: Int)
    private val shared = HashMap<String, Shared>()

    fun acquire(key: String, name: String, direction: Direction, peer: String, total: Long, startBytes: Long): Handle =
        synchronized(shared) {
            val s = shared[key]
            if (s != null && !s.handle.ended) {
                s.refs++
                s.handle
            } else {
                begin(name, direction, peer, total, startBytes).also { shared[key] = Shared(it, 1) }
            }
        }

    /**
     * Called when one request of a keyed transfer ends. The row completes once all its bytes
     * arrived; an incomplete row is left for a retry / the next range and reaped when idle.
     */
    fun release(key: String) {
        val toEnd = synchronized(shared) {
            val s = shared[key] ?: return
            s.refs = (s.refs - 1).coerceAtLeast(0)
            if (s.handle.isComplete()) { shared.remove(key); s.handle } else null
        }
        toEnd?.end(ok = true)
    }

    /** Ends keyed rows that saw no request for [idleMs] (a client that walked away mid-upload). */
    fun reapIdle(idleMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        val stale = synchronized(shared) {
            shared.entries.filter { it.value.refs <= 0 && now - it.value.handle.lastActivity > idleMs }
                .map { e -> shared.remove(e.key); e.value.handle }
        }
        stale.forEach { it.end(ok = false) }
    }

    /** Drops finished rows older than [keepMs]. */
    fun prune(keepMs: Long = 10 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        _transfers.update { list -> list.filter { it.finishedAt == null || now - it.finishedAt < keepMs } }
    }

    fun clearFinished() = _transfers.update { list -> list.filter { it.finishedAt == null } }

    private fun publish(h: Handle, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - h.lastPublish < 250) return
        h.lastPublish = now
        val snapshot = h.snapshot(now)
        _transfers.update { list ->
            val idx = list.indexOfFirst { it.id == h.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = snapshot }
            else (listOf(snapshot) + list).take(50)
        }
    }

    class Handle internal constructor(
        val id: Long,
        val name: String,
        val direction: Direction,
        val peer: String,
        val total: Long,
        startBytes: Long,
    ) {
        private val bytes = AtomicLong(startBytes)
        private val baseBytes = startBytes
        private val startedAt = System.currentTimeMillis()
        internal var lastPublish = 0L
        @Volatile internal var ended = false
        private var finishedAt: Long? = null
        private var failed = false

        @Volatile internal var lastActivity = System.currentTimeMillis()

        fun bytes(): Long = bytes.get()
        fun isComplete(): Boolean = bytes.get() >= total

        fun add(n: Long) {
            if (n <= 0) return
            bytes.addAndGet(n)
            lastActivity = System.currentTimeMillis()
            publish(this)
        }

        fun end(ok: Boolean) {
            if (ended) return
            ended = true
            failed = !ok
            finishedAt = System.currentTimeMillis()
            active.remove(id)
            publish(this, force = true)
            onActiveCountChanged?.invoke(active.size)
        }

        internal fun snapshot(now: Long): Transfer {
            val b = bytes.get()
            val secs = ((finishedAt ?: now) - startedAt) / 1000.0
            val rate = if (secs > 0.05) (b - baseBytes) / secs else 0.0
            return Transfer(id, name, direction, peer, total, b, startedAt, rate, finishedAt, failed)
        }
    }
}

/** Counts bytes read through it into a transfer handle and runs [onClose] once. */
class TrackedInputStream(
    inner: InputStream,
    private val handle: TransferTracker.Handle,
    private val onClose: () -> Unit,
) : FilterInputStream(inner) {
    private var closed = false

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) handle.add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) handle.add(n.toLong())
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        try { super.close() } finally { onClose() }
    }
}
