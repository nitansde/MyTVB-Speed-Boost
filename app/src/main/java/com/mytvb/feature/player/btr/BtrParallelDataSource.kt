package com.mytvb.feature.player.btr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Range scheduler based on BTR's range-core/idm-downloader model.
 *
 * A Media3 load is split into verified contiguous ranges. Each range gets its own
 * DataSource (and therefore its own HTTP connection/CDN candidate), while read()
 * exposes completed bytes in the original order expected by the extractor.
 */
internal class BtrParallelDataSourceFactory(
    private val upstreamFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BtrParallelDataSource(upstreamFactory)
}

private class BtrParallelDataSource(
    private val upstreamFactory: DataSource.Factory
) : DataSource {
    private val listeners = ArrayList<TransferListener>(2)
    private var executor: ExecutorService? = null
    private var jobs: List<Future<ByteArray>> = emptyList()
    private var activeChunk: ByteArray? = null
    private var activeOffset = 0
    private var nextJob = 0
    private var openedUri: Uri? = null
    private var openedLength: Long = C.LENGTH_UNSET.toLong()
    private var fallback: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val config = BtrSettingsStore.load()
        if (!config.enabled) {
            val length = openFallback(dataSpec)
            BtrRuntimeDiagnostics.counters.transport("BTR 关闭：原有单连接")
            return length
        }

        // Probe once so open-ended Media3 requests can still be split after the server
        // reports the object length. If the server does not report a finite length, retain
        // the probe and let Media3 consume it normally.
        val probe = upstreamFactory.createDataSource()
        listeners.forEach(probe::addTransferListener)
        probe.addTransferListener(BtrDiagnosticTransferListener())
        val length = try {
            probe.open(dataSpec)
        } catch (error: IOException) {
            BtrRuntimeDiagnostics.counters.error(error.javaClass.simpleName)
            runCatching { probe.close() }
            throw error
        }
        openedUri = probe.uri ?: dataSpec.uri
        val total = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            length != C.LENGTH_UNSET.toLong() -> length
            else -> C.LENGTH_UNSET.toLong()
        }
        val workers = effectiveConcurrency(config)
        if (total <= MIN_PARALLEL_BYTES || total == C.LENGTH_UNSET.toLong() || workers <= 1) {
            fallback = probe
            openedLength = length
            BtrRuntimeDiagnostics.counters.transport(
                if (total == C.LENGTH_UNSET.toLong()) "单连接：服务器未报告长度" else "单连接：请求不超过 256 KiB"
            )
            return length
        }

        runCatching { probe.close() }
        val start = dataSpec.position
        val ranges = splitRange(start, start + total - 1L, workers)
        BtrRuntimeDiagnostics.counters.transport("已拆分 Range：${ranges.size} 段")
        executor = Executors.newFixedThreadPool(ranges.size.coerceAtMost(workers))
        jobs = ranges.map { range ->
            executor!!.submit<ByteArray> { downloadRange(dataSpec, range.first, range.second) }
        }
        openedLength = total
        return total
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        fallback?.let { return it.read(buffer, offset, length) }
        if (nextJob >= jobs.size && activeChunk == null) return C.RESULT_END_OF_INPUT
        while (activeChunk == null || activeOffset >= activeChunk!!.size) {
            if (nextJob >= jobs.size) return C.RESULT_END_OF_INPUT
            activeChunk = try {
                jobs[nextJob++].get()
            } catch (error: Exception) {
                cancelJobs()
                throw unwrap(error)
            }
            activeOffset = 0
        }
        val chunk = activeChunk ?: return C.RESULT_END_OF_INPUT
        val count = minOf(length, chunk.size - activeOffset)
        chunk.copyInto(buffer, offset, activeOffset, activeOffset + count)
        activeOffset += count
        return count
    }

    override fun getUri(): Uri? = fallback?.uri ?: openedUri

    override fun close() {
        runCatching { fallback?.close() }
        fallback = null
        cancelJobs()
        executor?.shutdownNow()
        executor = null
        jobs = emptyList()
        activeChunk = null
        activeOffset = 0
        nextJob = 0
        openedUri = null
        openedLength = C.LENGTH_UNSET.toLong()
    }

    private fun openFallback(dataSpec: DataSpec): Long {
        val source = upstreamFactory.createDataSource()
        listeners.forEach(source::addTransferListener)
        source.addTransferListener(BtrDiagnosticTransferListener())
        fallback = source
        openedUri = dataSpec.uri
        openedLength = source.open(dataSpec)
        return openedLength
    }

    private fun downloadRange(template: DataSpec, start: Long, end: Long): ByteArray {
        var lastError: IOException? = null
        var completed = false
        BtrRuntimeDiagnostics.counters.rangeStarted()
        try {
            repeat(MAX_ATTEMPTS) { attempt ->
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Range cancelled")
                try {
                    val bytes = downloadRangeOnce(template, start, end)
                    completed = true
                    return bytes
                } catch (error: IOException) {
                    lastError = error
                    BtrRuntimeDiagnostics.counters.error(error.javaClass.simpleName)
                    if (Thread.currentThread().isInterrupted) throw error
                    if (attempt + 1 < MAX_ATTEMPTS) BtrRuntimeDiagnostics.counters.retry()
                }
            }
            throw lastError ?: IOException("BTR Range download failed")
        } finally {
            BtrRuntimeDiagnostics.counters.rangeFinished(completed)
        }
    }

    private fun downloadRangeOnce(template: DataSpec, start: Long, end: Long): ByteArray {
        val spec = template.buildUpon()
            .setPosition(start)
            .setLength(end - start + 1L)
            .build()
        val source = upstreamFactory.createDataSource()
        listeners.forEach(source::addTransferListener)
        source.addTransferListener(BtrDiagnosticTransferListener())
        try {
            val expected = end - start + 1L
            val reported = source.open(spec)
            if (reported != C.LENGTH_UNSET.toLong() && reported != expected) {
                throw IOException("BTR Range length mismatch: $reported/$expected")
            }
            val output = ByteArrayOutputStream(expected.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val buffer = ByteArray(32 * 1024)
            while (output.size().toLong() < expected) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Range cancelled")
                val remaining = (expected - output.size()).coerceAtMost(buffer.size.toLong()).toInt()
                val count = source.read(buffer, 0, remaining)
                if (count == C.RESULT_END_OF_INPUT) break
                if (count <= 0) continue
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            if (bytes.size.toLong() != expected) throw IOException("BTR Range body mismatch: ${bytes.size}/$expected")
            return bytes
        } finally {
            runCatching { source.close() }
        }
    }

    private fun cancelJobs() {
        jobs.forEach { it.cancel(true) }
    }

    private fun unwrap(error: Exception): IOException = when (error) {
        is ExecutionException -> unwrapCause(error.cause)
        is IOException -> error
        else -> IOException(error.message ?: "BTR Range download failed", error)
    }

    private fun unwrapCause(error: Throwable?): IOException = when (error) {
        is IOException -> error
        else -> IOException(error?.message ?: "BTR Range download failed", error)
    }

    private fun effectiveConcurrency(config: BtrSettings): Int =
        if (config.autoConcurrency) AUTO_CONCURRENCY else config.concurrency

    private fun splitRange(start: Long, end: Long, concurrency: Int): List<Pair<Long, Long>> {
        val length = end - start + 1L
        val count = minOf(concurrency.coerceIn(1, 128), length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val base = length / count
        val remainder = length % count
        var cursor = start
        return buildList(count) {
            repeat(count) { index ->
                val size = base + if (index < remainder) 1L else 0L
                add(cursor to (cursor + size - 1L))
                cursor += size
            }
        }
    }

    companion object {
        private const val MIN_PARALLEL_BYTES = 256L * 1024L
        private const val AUTO_CONCURRENCY = 16
        private const val MAX_ATTEMPTS = 3
    }
}
