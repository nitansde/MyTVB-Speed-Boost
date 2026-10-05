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
    candidates: List<Uri>,
    private val mode: () -> com.mytvb.feature.player.btr.BtrCdnMode = { com.mytvb.feature.player.btr.BtrCdnMode.MAINLAND },
    private val candidateProvider: (() -> List<Uri>)? = null
) : BtrRouteProvider {
    private val originalCandidates = candidates.toList()
    val candidates: List<Uri> get() = candidateProvider?.invoke()?.ifEmpty { originalCandidates } ?: originalCandidates
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
    private val bannedPairs = HashSet<String>()
    private val goodNodes = HashSet<String>()
    private val goodAddresses = HashSet<String>()
    private val failures = HashMap<String, Int>()

    @Synchronized
    fun preferredIndex(): Int {
        val lastIndex = candidates.lastIndex.coerceAtLeast(0)
        return preferredIndex.coerceIn(0, lastIndex)
    }

    override fun urls(): List<Uri> = synchronized(this) {
        candidates.filter(::allows).ifEmpty { candidates }
    }
    private fun available(): List<Uri> = urls().filter { (blockedUntil[it.toString()] ?: 0L) <= System.currentTimeMillis() }
    private fun sorted(list: List<Uri>): List<Uri> = list.sortedWith(compareByDescending<Uri> {
        (routeMetrics[routeKey(it)]?.lastSuccessAt ?: 0) > 0
    }.thenByDescending { routeMetrics[routeKey(it)]?.bytesPerSecond ?: 0 })
    override fun startupCandidates(): List<Uri> = synchronized(this) {
        val originals = if (mode() == com.mytvb.feature.player.btr.BtrCdnMode.CUSTOM) emptyList() else originalCandidates
        val pool = (originals + candidates).distinct().filter(::allows).ifEmpty { candidates }
        pool.filter { (blockedUntil[it.toString()] ?: 0) <= System.currentTimeMillis() }.take(8)
    }
    override fun rangeCandidates(): List<Uri> = synchronized(this) {
        val pool = sorted(available())
        if (pool.isEmpty()) return@synchronized urls()
        val width = min(if (rangeCount == 0) pool.size else 6, pool.size)
        val warmup = if (mode() == com.mytvb.feature.player.btr.BtrCdnMode.MAINLAND) 1 else 4
        val selected = if (rangeCount < warmup) {
            rangeCursor = width % pool.size
            pool.take(width)
        } else {
            val measured = pool.filter { speed(it) > 0 }
            val rest = pool.filter { speed(it) == 0L }
            val places = min(rest.size, max(1, width - measured.size))
            val explore = List(places) { rest[(rangeCursor + it) % rest.size] }
            rangeCursor = (rangeCursor + places) % max(1, pool.size)
            (measured.take(width - places) + explore).toMutableList().apply {
                for (url in pool) if (size < min(3, pool.size) && url !in this) add(url)
            }
        }
        rangeCount++
        selected
    }
    override fun rescueCandidates(): List<Uri> = synchronized(this) { sorted(available()) }
    override fun ordered(pieceIndex: Int, exclude: Set<Uri>): List<Uri> = synchronized(this) {
        val candidates = urls().filterNot(exclude::contains)
        val pool = candidates.filter { (blockedUntil[it.toString()] ?: 0L) <= System.currentTimeMillis() }.ifEmpty { candidates }
        if (pool.isEmpty()) return@synchronized emptyList()
        val offset = (candidateCursor + pieceIndex) % pool.size
        candidateCursor = (candidateCursor + 1) % pool.size
        pool.drop(offset) + pool.take(offset)
    }
    override fun allows(uri: Uri): Boolean = synchronized(this) {
        hostOf(uri) !in bannedNodes && addressOf(uri) !in bannedAddresses && (hostOf(uri) + " " + addressOf(uri)) !in bannedPairs
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
        blockedUntil.remove(uri.toString())
        failures.remove(uri.toString())
        goodNodes += hostOf(uri)
        goodAddresses += addressOf(uri)
        judgeBans()
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
        if (receivedBytes == 0L) {
            val key = hostOf(uri) + "\n" + addressOf(uri) + "\n" + if (statusCode in 400..499) "refused" else ""
            emptyFailures[key] = (emptyFailures[key] ?: 0) + 1
            judgeBans()
        }
        val key = uri.toString()
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        blockedUntil[key] = System.currentTimeMillis() + min(60000L, 3000L * (1L shl min(count, 4)))
    }
    private fun judgeBans() {
        val strikes = mutableMapOf<String, Int>()
        for ((key, count) in emptyFailures) {
            val fields = key.split('\n')
            val node = fields[0]; val address = fields[1]; val refused = fields[2].isNotEmpty()
            val blamed = if (!refused) "node:$node" else if (node in goodNodes) {
                if (address in goodAddresses) "pair:$node $address" else "address:$address"
            } else if (address in goodAddresses) "node:$node" else ""
            if (blamed.isNotEmpty()) strikes[blamed] = (strikes[blamed] ?: 0) + count
        }
        bannedNodes.clear(); bannedAddresses.clear(); bannedPairs.clear()
        for ((key, count) in strikes) if (count >= 2) when {
            key.startsWith("node:") -> bannedNodes.add(key.removePrefix("node:"))
            key.startsWith("address:") -> bannedAddresses.add(key.removePrefix("address:"))
            else -> bannedPairs.add(key.removePrefix("pair:"))
        }
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
) : DataSource, com.mytvb.feature.player.btr.BtrHttpResponse {

    @Volatile private var upstream: DataSource? = null
    private val transferListeners = ArrayList<TransferListener>(2)

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        val strictRequested = (dataSpec.flags and BTR_ROUTE_FLAG) != 0
        val candidates = if (strictRequested) listOf(dataSpec.uri) else state.candidates
        if (candidates.isEmpty()) {
            throw IOException("No CDN candidates available")
        }

        // The BTR scheduler puts the selected CDN host in the DataSpec URI.  Honour that
        // choice instead of collapsing every parallel range back onto the shared preferred
        // host; ordinary Media3 requests still use the stateful preferred index.
        val requestedHost = dataSpec.uri.host?.lowercase(Locale.US)
        val exactIndex = candidates.indexOf(dataSpec.uri)
        val requestedIndex = if (exactIndex >= 0) exactIndex else requestedHost?.let { host ->
            candidates.indexOfFirst { it.host?.lowercase(Locale.US) == host }
        } ?: -1
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
                upstream = upstreamSource
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
                // The BTR transport classifies cancellation separately from failure.
                if (!strictRequested) {
                    com.mytvb.feature.player.btr.BtrRuntimeDiagnostics.counters.error("CDN ${error.javaClass.simpleName}")
                    if (attempt + 1 < attempts.size) com.mytvb.feature.player.btr.BtrRuntimeDiagnostics.counters.retry()
                }
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

    override val responseCode: Int? get() = (upstream as? androidx.media3.datasource.HttpDataSource)?.responseCode

    override fun getUri(): Uri? = upstream?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { upstream?.close() }
        upstream = null
    }
}
