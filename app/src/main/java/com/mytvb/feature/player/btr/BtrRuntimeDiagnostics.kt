package com.mytvb.feature.player.btr

/** Process-wide, payload-free counters; audio/video requests must never reset each other. */
internal class BtrDiagnostics {
    data class Snapshot(
        val transport: String,
        val hosts: String,
        val connections: Int,
        val activeRanges: Int,
        val completedRanges: Long,
        val retries: Long,
        val bytes: Long,
        val lastError: String,
    )

    private val connections = java.util.IdentityHashMap<Any, String>()
    private var transport = "等待媒体请求（缓存命中或直播可能不走 BTR）"
    private var lastHost = "-"
    private var activeRanges = 0
    private var completedRanges = 0L
    private var retries = 0L
    private var bytes = 0L
    private var lastError = ""

    @Synchronized fun transport(value: String) { transport = value }
    @Synchronized fun connected(source: Any, host: String) {
        connections[source] = host
        lastHost = host
    }
    @Synchronized fun disconnected(source: Any) { connections.remove(source) }
    @Synchronized fun received(count: Int) { bytes += count.coerceAtLeast(0) }
    @Synchronized fun rangeStarted() { activeRanges++ }
    @Synchronized fun rangeFinished(completed: Boolean) {
        activeRanges = (activeRanges - 1).coerceAtLeast(0)
        if (completed) completedRanges++
    }
    @Synchronized fun retry() { retries++ }
    // Accept only an error category/status code, never a signed media URL or exception message.
    @Synchronized fun error(category: String) { lastError = category.take(80) }
    @Synchronized fun snapshot() = Snapshot(
        transport, connections.values.toSet().take(3).joinToString(", ").ifEmpty { lastHost },
        connections.size, activeRanges, completedRanges, retries, bytes, lastError,
    )
}

internal object BtrRuntimeDiagnostics {
    val counters = BtrDiagnostics()
}
