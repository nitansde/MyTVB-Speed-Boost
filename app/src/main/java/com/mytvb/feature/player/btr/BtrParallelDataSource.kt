package com.mytvb.feature.player.btr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * BTR's playback downloader adapted to Media3's blocking DataSource contract.
 *
 * A Media3 load is presented as an ordered stream, but its network work is scheduled as
 * bounded pieces. The scheduler starts with a small probe, assigns later pieces by measured
 * CDN speed, keeps only a limited look-ahead in memory, resumes failed pieces from their
 * received prefix, and starts a rescue copy only when the first copy has stalled.
 */
internal class BtrParallelDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val routeProvider: BtrRouteProvider? = null
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BtrParallelDataSource(upstreamFactory, routeProvider)
}

private class BtrParallelDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val routeProvider: BtrRouteProvider?
) : DataSource {
    private val listeners = ArrayList<TransferListener>(2)
    private val activeSources = ConcurrentHashMap.newKeySet<DataSource>()
    private var networkExecutor: ExecutorService? = null
    private var schedulerExecutor: ExecutorService? = null
    private var pieceFutures = emptyList<Future<PieceResult>?>()
    private var currentPiece: ByteArray? = null
    private var currentOffset = 0
    private var nextPiece = 0
    private var nextScheduledPiece = 0
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var openedUri: Uri? = null
    private var fallback: DataSource? = null
    private var closed = false
    private var generation = 0L
    private var pieces: List<RangePiece> = emptyList()
    private var openSpec: DataSpec? = null
    private var activeWindow = 1

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        closed = false
        generation += 1
        val settings = BtrSettingsStore.load()
        if (!settings.enabled || settings.takeover == BtrTakeoverMode.COMPAT) {
            val length = openFallback(dataSpec)
            BtrRuntimeDiagnostics.counters.transport(
                if (!settings.enabled) "BTR 关闭：原有单连接" else "兼容模式：客户端单连接"
            )
            return length
        }

        val length = resolveLength(dataSpec)
        if (length == C.LENGTH_UNSET.toLong()) {
            val fallbackLength = openFallback(dataSpec)
            BtrRuntimeDiagnostics.counters.transport("单连接：服务器未报告长度")
            return fallbackLength
        }
        totalLength = length
        openedUri = dataSpec.uri
        if (length <= MIN_PARALLEL_BYTES) {
            val fallbackLength = openFallback(dataSpec)
            BtrRuntimeDiagnostics.counters.transport("单连接：请求不超过 256 KiB")
            return fallbackLength
        }

        val auto = settings.autoConcurrency
        if (auto) BtrAutoConcurrency.newSession()
        val concurrency = if (auto) BtrAutoConcurrency.threads() else settings.concurrency
        BtrConnectionLimiter.setLimit(concurrency)
        val candidates = routeProvider?.rangeCandidates().orEmpty()
        val chunkSize = adaptiveChunkSize(length, concurrency, candidates.size)
        val ranges = splitRange(dataSpec.position, dataSpec.position + length - 1L, concurrency, chunkSize)
        if (ranges.size <= 1) {
            val fallbackLength = openFallback(dataSpec)
            BtrRuntimeDiagnostics.counters.transport("单连接：没有足够的 Range 任务")
            return fallbackLength
        }

        val generationAtOpen = generation
        networkExecutor = Executors.newFixedThreadPool(max(8, min(64, concurrency + 8)))
        schedulerExecutor = Executors.newFixedThreadPool(max(2, min(8, concurrency / 4 + 1)))
        pieceFutures = MutableList(ranges.size) { null }
        pieces = ranges
        openSpec = dataSpec
        activeWindow = min(ranges.size, max(1, concurrency - rescueReserve(concurrency) + 1))
        scheduleMoreFromRead(concurrency, generationAtOpen)
        BtrRuntimeDiagnostics.counters.transport("已拆分 Range：${ranges.size} 段，调度线程 $concurrency")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        fallback?.let { return it.read(buffer, offset, length) }
        if (nextPiece >= pieceFutures.size) return C.RESULT_END_OF_INPUT
        val piece = currentPiece ?: run {
            val result = try {
                pieceFutures[nextPiece]?.get()
                    ?: throw IOException("BTR piece has not been scheduled")
            } catch (error: Exception) {
                cancelAll()
                throw unwrap(error)
            }
            currentPiece = result.bytes
            currentOffset = 0
            result.bytes
        }
        val count = min(length, piece.size - currentOffset)
        piece.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        if (currentOffset >= piece.size) {
            currentPiece = null
            currentOffset = 0
            nextPiece += 1
            scheduleMoreFromRead()
        }
        return count
    }

    override fun getUri(): Uri? = fallback?.uri ?: openedUri

    override fun close() {
        closed = true
        generation += 1
        fallback?.let { runCatching { it.close() } }
        fallback = null
        cancelAll()
        activeSources.forEach { runCatching { it.close() } }
        activeSources.clear()
        schedulerExecutor?.shutdownNow()
        networkExecutor?.shutdownNow()
        schedulerExecutor = null
        networkExecutor = null
        pieceFutures = emptyList()
        currentPiece = null
        currentOffset = 0
        nextPiece = 0
        nextScheduledPiece = 0
        totalLength = C.LENGTH_UNSET.toLong()
        openedUri = null
        pieces = emptyList()
        openSpec = null
    }

    private fun resolveLength(dataSpec: DataSpec): Long {
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec.length
        val probe = upstreamFactory.createDataSource()
        listeners.forEach(probe::addTransferListener)
        probe.addTransferListener(BtrDiagnosticTransferListener())
        activeSources += probe
        return try {
            val reported = probe.open(dataSpec)
            openedUri = probe.uri ?: dataSpec.uri
            runCatching { probe.close() }
            activeSources.remove(probe)
            reported
        } catch (error: IOException) {
            BtrRuntimeDiagnostics.counters.error(error.javaClass.simpleName)
            runCatching { probe.close() }
            activeSources.remove(probe)
            throw error
        }
    }

    private fun openFallback(dataSpec: DataSpec): Long {
        val source = upstreamFactory.createDataSource()
        listeners.forEach(source::addTransferListener)
        source.addTransferListener(BtrDiagnosticTransferListener())
        activeSources += source
        fallback = source
        openedUri = dataSpec.uri
        return source.open(dataSpec)
    }

    private fun scheduleMoreFromRead(
        concurrency: Int = currentConcurrency(),
        generationAtOpen: Long = generation
    ) {
        val executor = schedulerExecutor ?: return
        val spec = openSpec ?: return
        BtrConnectionLimiter.setLimit(concurrency)
        val target = min(pieces.size, nextPiece + activeWindow.coerceAtLeast(concurrency / 2))
        while (nextScheduledPiece < target) {
            val index = nextScheduledPiece++
            val piece = pieces[index]
            val future = executor.submit<PieceResult> {
                if (closed || generation != generationAtOpen) throw InterruptedIOException("播放任务已取消")
                downloadPiece(piece, spec, generationAtOpen)
            }
            (pieceFutures as MutableList<Future<PieceResult>?>)[index] = future
        }
        BtrRuntimeDiagnostics.counters.scheduler(
            queued = (nextScheduledPiece - nextPiece).coerceAtLeast(0),
            threads = concurrency
        )
    }

    private fun currentConcurrency(): Int {
        val settings = BtrSettingsStore.load()
        return if (settings.autoConcurrency) BtrAutoConcurrency.threads() else settings.concurrency
    }

    private fun downloadPiece(piece: RangePiece, template: DataSpec, generationAtOpen: Long): PieceResult {
        BtrRuntimeDiagnostics.counters.rangeStarted()
        var completed = false
        try {
        val provider = routeProvider
        val candidates = (provider?.rangeCandidates().orEmpty().ifEmpty { listOf(template.uri) })
            .filter { provider?.allows(it) != false }
        val preferred = assignPrimary(candidates, piece.index)
        val ordered = buildList {
            preferred?.let(::add)
            addAll((provider?.rescueCandidates().orEmpty() + candidates + provider?.ordered(piece.index).orEmpty()).distinct())
        }
        val startedAt = System.nanoTime()
        var prefix = ByteArray(0)
        var lastError: IOException? = null
        repeat(MAX_ROUNDS) { round ->
            if (round > 0) Thread.sleep(min(2000L, 500L shl (round - 1)))
            val tried = LinkedHashSet<Uri>()
            val first = ordered.getOrNull(round % ordered.size)?.takeIf { tried.add(it) }
            if (first == null) return@repeat
            val primary = AttemptTask(piece, template, first, prefix, generationAtOpen)
            val firstFuture = networkExecutor!!.submit<AttemptResult> { primary.call() }
            var rescueFuture: Future<AttemptResult>? = null
            var rescueTask: AttemptTask? = null
            var winner: AttemptResult? = null
            val hedgeAt = System.nanoTime() + if (piece.index == 0) 120L * 1_000_000L else hedgeDelayNanos()
            val attemptDeadline = System.nanoTime() + ATTEMPT_TIMEOUT_MS * 1_000_000L
            while (winner == null) {
                try {
                    winner = firstFuture.get(50L, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    val primaryStalled = primary.progress.lastProgressAgeMs() >= STALL_TIMEOUT_MS
                    val primaryTimedOut = System.nanoTime() >= attemptDeadline ||
                        (primary.progress.size == 0 && primary.progress.elapsedMs() >= FIRST_BYTE_TIMEOUT_MS)
                    if (primaryTimedOut || primaryStalled) {
                        primary.cancel()
                        if (primaryTimedOut && primary.progress.size == 0) BtrAutoConcurrency.slow()
                        if (rescueTask == null && ordered.size > 1) {
                            val rescue = ordered.firstOrNull { tried.add(it) }
                            if (rescue != null) {
                                val base = primary.progress.snapshot()
                                rescueTask = AttemptTask(piece, template, rescue, base, generationAtOpen)
                                rescueFuture = networkExecutor!!.submit<AttemptResult> { rescueTask!!.call() }
                            }
                        }
                    }
                    if (rescueFuture == null && System.nanoTime() >= hedgeAt && ordered.size > 1) {
                        val rescue = ordered.firstOrNull { tried.add(it) }
                        if (rescue != null) {
                            val base = primary.progress.snapshot()
                            rescueTask = AttemptTask(piece, template, rescue, base, generationAtOpen)
                            rescueFuture = networkExecutor!!.submit<AttemptResult> { rescueTask!!.call() }
                        }
                    }
                    if (rescueFuture != null) {
                        try {
                            winner = rescueFuture.get(1L, TimeUnit.MILLISECONDS)
                        } catch (_: TimeoutException) {
                            // Both copies are still making progress; keep waiting.
                        } catch (error: Exception) {
                            lastError = unwrap(error)
                            rescueFuture = null
                        }
                    }
                } catch (error: Exception) {
                    lastError = unwrap(error)
                    if (rescueFuture != null) {
                        try {
                            winner = rescueFuture.get(100L, TimeUnit.MILLISECONDS)
                        } catch (rescueError: Exception) {
                            lastError = unwrap(rescueError)
                        }
                    }
                    break
                }
            }
            if (winner != null) {
                primary.cancel()
                firstFuture.cancel(true)
                rescueTask?.cancel()
                rescueFuture?.cancel(true)
                val elapsed = max(1L, (System.nanoTime() - startedAt) / 1_000_000L)
                val measuredBps = winner!!.bytes.size * 1000L / elapsed
                routeProvider?.success(winner!!.uri, measuredBps)
                BtrRuntimeDiagnostics.counters.updateConnectionSpeed(measuredBps)
                BtrRuntimeDiagnostics.counters.delivered(winner!!.bytes.size.toLong())
                BtrAutoConcurrency.delivered(winner!!.bytes.size.toLong())
                completed = true
                return winner!!
            }
            prefix = primary.progress.snapshot()
            val rescuePrefix = rescueTask?.progress?.snapshot()
            if (rescuePrefix != null && rescuePrefix.size > prefix.size) prefix = rescuePrefix
            if (prefix.size >= piece.length) return PieceResult(prefix, primary.uri)
            if (prefix.size >= SPEED_SAMPLE_MIN_BYTES) {
                routeProvider?.sample(first, prefix.size * 1000L / max(1L, primary.progress.elapsedMs()))
            }
            routeProvider?.failure(first, prefix.size.toLong())
            BtrRuntimeDiagnostics.counters.retry()
        }
        throw lastError ?: IOException("BTR Range 下载失败")
        } finally {
            BtrRuntimeDiagnostics.counters.rangeFinished(completed)
        }
    }

    private fun assignPrimary(candidates: List<Uri>, pieceIndex: Int): Uri? {
        if (candidates.isEmpty()) return null
        val provider = routeProvider ?: return candidates[pieceIndex % candidates.size]
        val weighted = candidates.map { it to provider.speed(it).coerceAtLeast(1L) }
        val total = weighted.sumOf { it.second }
        val position = pieceIndex.toLong() % total
        var cursor = 0L
        weighted.forEach { (uri, weight) ->
            cursor += weight
            if (position < cursor) return uri
        }
        return weighted.last().first
    }

    private fun adaptiveChunkSize(length: Long, concurrency: Int, hostCount: Int): Long {
        val meter = BtrRuntimeDiagnostics.counters.snapshot().connectionBps
        if (meter <= 0L) return MIN_CHUNK_BYTES
        val target = (meter * 600L / 1000L).coerceIn(MIN_CHUNK_BYTES, 1024L * 1024L)
        val spread = (length / max(1, min(max(4, hostCount), concurrency))).coerceAtLeast(MIN_CHUNK_BYTES)
        return min(target, spread).coerceAtLeast(MIN_CHUNK_BYTES)
    }

    private fun rescueReserve(concurrency: Int) = if (concurrency >= 8) min(8, max(1, (concurrency + 7) / 8)) else 0
    private fun hedgeDelayNanos(): Long = 900L * 1_000_000L

    private fun splitRange(start: Long, end: Long, concurrency: Int, minChunkBytes: Long): List<RangePiece> {
        val length = end - start + 1L
        val count = max(1, min(concurrency.coerceIn(1, 128), ((length + minChunkBytes - 1L) / minChunkBytes).toInt()))
        val base = length / count
        val remainder = length % count
        var cursor = start
        return buildList(count) {
            repeat(count) { index ->
                val size = base + if (index < remainder) 1L else 0L
                add(RangePiece(index, cursor, cursor + size - 1L, size.toInt()))
                cursor += size
            }
        }
    }

    private fun cancelAll() = pieceFutures.forEach { it?.cancel(true) }

    private fun unwrap(error: Exception): IOException = when (error) {
        is ExecutionException -> unwrapCause(error.cause)
        is IOException -> error
        else -> IOException(error.message ?: "BTR Range 下载失败", error)
    }

    private fun unwrapCause(error: Throwable?): IOException = when (error) {
        is IOException -> error
        else -> IOException(error?.message ?: "BTR Range 下载失败", error)
    }

    private data class RangePiece(val index: Int, val start: Long, val end: Long, val length: Int)
    private open class PieceResult(open val bytes: ByteArray, open val uri: Uri)

    private inner class AttemptTask(
        private val piece: RangePiece,
        private val template: DataSpec,
        val uri: Uri,
        private val prefix: ByteArray,
        private val generationAtOpen: Long
    ) {
        val progress = AttemptProgress(prefix)
        @Volatile private var activeSource: DataSource? = null

        fun cancel() {
            runCatching { activeSource?.close() }
        }

        fun call(): AttemptResult {
            if (generation != generationAtOpen || closed) throw InterruptedIOException("播放任务已取消")
            val source = upstreamFactory.createDataSource()
            listeners.forEach(source::addTransferListener)
            source.addTransferListener(BtrDiagnosticTransferListener())
            activeSources += source
            activeSource = source
            val permit = BtrConnectionLimiter.acquire()
            val startedAt = System.nanoTime()
            val start = piece.start + prefix.size
            try {
                if (start > piece.end) return AttemptResult(progress.snapshot(), uri)
                val spec = template.buildUpon()
                    .setUri(uri)
                    .setFlags(template.flags or BTR_ROUTE_FLAG)
                    .setPosition(start)
                    .setLength(piece.end - start + 1L)
                    .build()
                val reported = source.open(spec)
                if (reported != C.LENGTH_UNSET.toLong() && reported != piece.end - start + 1L) {
                    throw IOException("BTR Range 长度不符：$reported/${piece.end - start + 1L}")
                }
                val buffer = ByteArray(32 * 1024)
                while (progress.size < piece.length) {
                    if (Thread.currentThread().isInterrupted || generation != generationAtOpen || closed) {
                        throw InterruptedIOException("BTR Range 已取消")
                    }
                    val count = source.read(buffer, 0, min(buffer.size, piece.length - progress.size))
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (count <= 0) continue
                    progress.append(buffer, count)
                    BtrAutoConcurrency.activity()
                }
                if (progress.size != piece.length) throw IOException("BTR Range 数据不完整：${progress.size}/${piece.length}")
                val elapsed = max(1L, (System.nanoTime() - startedAt) / 1_000_000L)
                return AttemptResult(progress.snapshot(), uri, elapsed)
            } catch (error: IOException) {
                BtrRuntimeDiagnostics.counters.error(error.javaClass.simpleName)
                throw error
            } finally {
                runCatching { source.close() }
                activeSources.remove(source)
                activeSource = null
                permit.close()
            }
        }
    }

    private class AttemptProgress(initial: ByteArray) {
        private val output = ByteArrayOutputStream(initial.size + 32 * 1024).apply { write(initial) }
        private val startedAt = System.nanoTime()
        @Volatile private var lastProgressAt = startedAt
        val size: Int get() = synchronized(this) { output.size() }
        @Synchronized fun append(bytes: ByteArray, count: Int) {
            output.write(bytes, 0, count)
            lastProgressAt = System.nanoTime()
        }
        @Synchronized fun snapshot(): ByteArray = output.toByteArray()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000L
        fun lastProgressAgeMs() = (System.nanoTime() - lastProgressAt) / 1_000_000L
    }

    private class AttemptResult(
        override val bytes: ByteArray,
        override val uri: Uri,
        val elapsedMs: Long = 1L
    ) : PieceResult(bytes, uri)

    companion object {
        private const val MIN_PARALLEL_BYTES = 256L * 1024L
        private const val MIN_CHUNK_BYTES = 64L * 1024L
        private const val MAX_ROUNDS = 3
        private const val FIRST_BYTE_TIMEOUT_MS = 5_500L
        private const val STALL_TIMEOUT_MS = 4_000L
        private const val ATTEMPT_TIMEOUT_MS = 15_000L
        private const val SPEED_SAMPLE_MIN_BYTES = 48 * 1024
    }
}

/** A process-wide connection budget shared by video and audio representations. */
private object BtrConnectionLimiter {
    private val lock = Object()
    private var active = 0
    private var limit = 8

    fun acquire(): Permit {
        synchronized(lock) {
            while (active >= limit) {
                try {
                    lock.wait(100L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("等待 BTR 连接许可时被取消")
                }
            }
            active += 1
            BtrRuntimeDiagnostics.counters.connectionBudget(active, limit)
            return Permit()
        }
    }

    fun setLimit(value: Int) {
        synchronized(lock) {
            limit = value.coerceIn(1, 64)
            lock.notifyAll()
        }
    }

    class Permit : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (!released.compareAndSet(false, true)) return
            synchronized(lock) {
                active = (active - 1).coerceAtLeast(0)
                BtrRuntimeDiagnostics.counters.connectionBudget(active, limit)
                lock.notifyAll()
            }
        }
    }
}
