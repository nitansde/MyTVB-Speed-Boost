package com.mytvb.feature.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.btr.BtrRouteProvider
import com.mytvb.feature.player.btr.BTR_ROUTE_FLAG
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal class VideoPlayerCdnFailoverState(
    val candidates: List<Uri>
) : BtrRouteProvider {
    @Volatile
    private var preferredIndex: Int = 0
    private var diagnosticOpenCount: Int = 0
    private var rangeCount: Int = 0
    private var rangeCursor: Int = 0
    private var candidateCursor: Int = 0
    private val routeMetrics = HashMap<String, RouteMetric>()
    private val blockedUntil = HashMap<String, Long>()
    private val emptyFailures = HashMap<String, Int>()
    private val bannedNodes = HashSet<String>()
    private val bannedAddresses = HashSet<String>()

    @Synchronized
    fun preferredIndex(): Int {
        val lastIndex = candidates.lastIndex.coerceAtLeast(0)
        return preferredIndex.coerceIn(0, lastIndex)
    }

    override fun urls(): List<Uri> = synchronized(this) {
        val now = System.currentTimeMillis()
        val allowed = candidates.filter { uri ->
            val host = hostOf(uri)
            !bannedNodes.contains(host) && !bannedAddresses.contains(addressOf(uri))
        }
        val usable = allowed.filter { (blockedUntil[routeKey(it)] ?: 0L) <= now }
        (if (usable.isNotEmpty()) usable else if (allowed.isNotEmpty()) allowed else candidates).toList()
    }

    override fun startupCandidates(): List<Uri> = synchronized(this) {
        val now = System.currentTimeMillis()
        candidates.filter { (blockedUntil[routeKey(it)] ?: 0L) <= now }
            .filter { !bannedNodes.contains(hostOf(it)) && !bannedAddresses.contains(addressOf(it)) }
            .take(8)
            .ifEmpty { candidates.take(8) }
    }

    override fun rangeCandidates(): List<Uri> = synchronized(this) {
        val pool = urls().sortedWith(compareByDescending<Uri> { speed(it) }.thenBy { hostOf(it) })
        val width = min(if (rangeCount == 0) pool.size else 6, pool.size)
        if (width == 0) return@synchronized candidates
        val measured = pool.filter { speed(it) > 0L }
        val unknown = pool.filter { speed(it) <= 0L }
        val exploreCount = if (rangeCount < 4) {
            min(unknown.size, max(1, width - measured.size))
        } else {
            min(unknown.size, max(1, width - measured.count()))
        }
        val selected = buildList {
            addAll(measured.take(width - exploreCount))
            repeat(exploreCount) { index ->
                if (unknown.isNotEmpty()) add(unknown[(rangeCursor + index) % unknown.size])
            }
            pool.forEach { if (size < min(3, pool.size) && !contains(it)) add(it) }
        }.distinct()
        rangeCursor = (rangeCursor + exploreCount).coerceAtLeast(0) % max(1, pool.size)
        rangeCount += 1
        selected.ifEmpty { pool }
    }

    override fun rescueCandidates(): List<Uri> = urls().sortedByDescending { speed(it) }

    override fun ordered(pieceIndex: Int, exclude: Set<Uri>): List<Uri> = synchronized(this) {
        val pool = urls().filterNot(exclude::contains)
        if (pool.isEmpty()) return@synchronized emptyList()
        val offset = (candidateCursor + pieceIndex).mod(pool.size)
        candidateCursor = (candidateCursor + 1).mod(pool.size)
        pool.drop(offset) + pool.take(offset)
    }

    override fun allows(uri: Uri): Boolean = synchronized(this) {
        !bannedNodes.contains(hostOf(uri)) && !bannedAddresses.contains(addressOf(uri))
    }

    override fun speed(uri: Uri): Long = synchronized(this) {
        val item = routeMetrics[routeKey(uri)] ?: return@synchronized 0L
        if (System.currentTimeMillis() - item.lastMeasuredAt > MEASUREMENT_TTL_MS) 0L else item.bytesPerSecond
    }

    override fun success(uri: Uri, bytesPerSecond: Long) {
        synchronized(this) {
        val key = routeKey(uri)
        val old = routeMetrics[key]
        routeMetrics[key] = RouteMetric(
            bytesPerSecond = if (bytesPerSecond > 0L) {
                if (old?.bytesPerSecond ?: 0L > 0L) {
                    ((old!!.bytesPerSecond * 65L) + (bytesPerSecond * 35L)) / 100L
                } else bytesPerSecond
            } else old?.bytesPerSecond ?: 0L,
            lastMeasuredAt = if (bytesPerSecond > 0L) System.currentTimeMillis() else old?.lastMeasuredAt ?: 0L,
            lastSuccessAt = System.currentTimeMillis()
        )
        blockedUntil.remove(key)
        emptyFailures.remove(failureKey(uri))
        }
    }

    override fun sample(uri: Uri, bytesPerSecond: Long) = synchronized(this) {
        if (bytesPerSecond <= 0L) return@synchronized
        val key = routeKey(uri)
        val old = routeMetrics[key]
        routeMetrics[key] = RouteMetric(
            bytesPerSecond = if (old?.bytesPerSecond ?: 0L > 0L) {
                ((old!!.bytesPerSecond * 65L) + (bytesPerSecond * 35L)) / 100L
            } else bytesPerSecond,
            lastMeasuredAt = System.currentTimeMillis(),
            lastSuccessAt = old?.lastSuccessAt ?: 0L
        )
    }

    override fun failure(uri: Uri, receivedBytes: Long, statusCode: Int?) = synchronized(this) {
        if (receivedBytes > 0L) return@synchronized
        val host = hostOf(uri)
        val address = addressOf(uri)
        val key = if (statusCode in 400..499) "$host\n$address\nrefused" else "$host\n$address\n"
        val strikes = (emptyFailures[key] ?: 0) + 1
        emptyFailures[key] = strikes
        if (strikes >= 2) {
            if (statusCode in 400..499) bannedAddresses += address else bannedNodes += host
        }
        val route = routeKey(uri)
        val previousFailures = (blockedUntil[route] ?: 0L).let { if (it > System.currentTimeMillis()) 1 else 0 }
        blockedUntil[route] = System.currentTimeMillis() + min(60_000L, 3_000L shl previousFailures.coerceAtMost(4))
    }

    @Synchronized
    fun markPreferred(index: Int) {
        val lastIndex = candidates.lastIndex.coerceAtLeast(0)
        preferredIndex = index.coerceIn(0, lastIndex)
    }

    @Synchronized
    fun nextDiagnosticOpenCount(): Int = ++diagnosticOpenCount

    /**
     * 卡顿降权：把当前正在用的 CDN host 记一条惩罚性 TTFB 到延迟画像。
     * 借助 CdnLatencyProfile "每 host 保留最近 5 条" 的淘汰机制自动稀释——
     * 一次卡顿只占 1/5，后续正常 open 会把均值拉回，不会判死刑。
     */
    @Synchronized
    fun penalizeCurrentHost() {
        val index = preferredIndex()
        val url = candidates.getOrNull(index)?.toString() ?: return
        CdnLatencyProfile.recordTtfb(url, PENALTY_TTFB_MS)
    }

    companion object {
        // 远大于正常 TTFB(~200ms)，足以把卡顿过的 host 排到 candidates 末尾。
        private const val PENALTY_TTFB_MS = 10_000L
        private const val MEASUREMENT_TTL_MS = 90_000L

        private fun hostOf(uri: Uri): String = uri.host.orEmpty().lowercase(Locale.US)
        private fun addressOf(uri: Uri): String = uri.path.orEmpty() + "?" + uri.query.orEmpty()
        private fun routeKey(uri: Uri): String = hostOf(uri) + uri.path.orEmpty()
        private fun failureKey(uri: Uri): String = hostOf(uri) + "\n" + addressOf(uri)
    }

    private data class RouteMetric(
        val bytesPerSecond: Long,
        val lastMeasuredAt: Long,
        val lastSuccessAt: Long
    )
}

internal class VideoPlayerCdnFailoverDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val state: VideoPlayerCdnFailoverState
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return VideoPlayerCdnFailoverDataSource(
            upstreamFactory = upstreamFactory,
            state = state
        )
    }
}

internal class VideoPlayerCdnFailoverDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val state: VideoPlayerCdnFailoverState
) : DataSource {

    private var upstream: DataSource? = null
    private val transferListeners = ArrayList<TransferListener>(2)

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        val candidates = state.candidates
        if (candidates.isEmpty()) {
            throw IOException("No CDN candidates available")
        }

        // The BTR scheduler puts the selected CDN host in the DataSpec URI.  Honour that
        // choice instead of collapsing every parallel range back onto the shared preferred
        // host; ordinary Media3 requests still use the stateful preferred index.
        val requestedHost = dataSpec.uri.host?.lowercase(Locale.US)
        val requestedIndex = requestedHost?.let { host ->
            candidates.indexOfFirst { it.host?.lowercase(Locale.US) == host }
        } ?: -1
        val strictRequested = (dataSpec.flags and BTR_ROUTE_FLAG) != 0
        val startIndex = requestedIndex.takeIf { it >= 0 } ?: state.preferredIndex()
        val openCount = state.nextDiagnosticOpenCount()
        val logOpen = openCount <= 2
        val openStartedAtMs = SystemClock.elapsedRealtime()
        if (logOpen) {
            AppLog.i(
                "PlaybackCdn",
                "playback_diag cdn_open started sequence=$openCount candidates=${candidates.size} " +
                    "startIndex=$startIndex position=${dataSpec.position}"
            )
        }
        var lastException: IOException? = null
        val attempts = if (strictRequested && requestedIndex >= 0) listOf(requestedIndex) else candidates.indices.toList()
        for (attempt in attempts.indices) {
            val index = if (strictRequested && requestedIndex >= 0) requestedIndex else (startIndex + attempt) % candidates.size
            val candidateUri = candidates[index]
            val upstreamSource = upstreamFactory.createDataSource()
            transferListeners.forEach(upstreamSource::addTransferListener)
            val candidateSpec = dataSpec.buildUpon()
                .setUri(candidateUri)
                .build()
            // 单候选计时：TTFB 只计本次尝试的 open 耗时，不含前面失败候选的累计时间。
            val candidateStartedAtMs = SystemClock.elapsedRealtime()
            try {
                val openedLength = upstreamSource.open(candidateSpec)
                upstream = upstreamSource
                state.markPreferred(index)
                // 采集 TTFB 到延迟画像：open 成功 = 该 CDN 在当前网络下确实可达。
                // 数据沉淀进 CdnLatencyProfile（进程级单例），跨视频复用，让后续播放的
                // sortUrlsByLatency 能把实测快的 host 排到 candidates 前面。
                CdnLatencyProfile.recordTtfb(
                    candidateUri.toString(),
                    SystemClock.elapsedRealtime() - candidateStartedAtMs
                )
                if (logOpen) {
                    AppLog.i(
                        "PlaybackCdn",
                        "playback_diag cdn_open ready sequence=$openCount attempt=${attempt + 1} host=${candidateUri.host.orEmpty()} " +
                            "durationMs=${SystemClock.elapsedRealtime() - openStartedAtMs}"
                    )
                }
                return openedLength
            } catch (error: IOException) {
                runCatching { upstreamSource.close() }
                lastException = error
                com.mytvb.feature.player.btr.BtrRuntimeDiagnostics.counters.error("CDN ${error.javaClass.simpleName}")
                if (attempt + 1 < attempts.size) com.mytvb.feature.player.btr.BtrRuntimeDiagnostics.counters.retry()
                AppLog.w(
                    "PlaybackCdn",
                    "playback_diag cdn_open failed sequence=$openCount attempt=${attempt + 1} host=${candidateUri.host.orEmpty()} " +
                        "durationMs=${SystemClock.elapsedRealtime() - openStartedAtMs} error=${error.javaClass.simpleName}:${error.message}"
                )
            }
        }

        throw lastException ?: IOException("Failed to open any CDN candidate")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val activeUpstream = upstream ?: throw IllegalStateException("read() before open()")
        return activeUpstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { upstream?.close() }
        upstream = null
    }
}
