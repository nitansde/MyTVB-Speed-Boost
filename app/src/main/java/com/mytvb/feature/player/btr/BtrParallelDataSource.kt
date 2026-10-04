package com.mytvb.feature.player.btr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException

internal class BtrParallelDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val routeProvider: BtrRouteProvider? = null,
    private val downloader: BtrDownloader = BtrDownloader(),
    private val requests: BtrDashRequests? = null
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BtrParallelDataSource(upstreamFactory, routeProvider, downloader, requests)
}

/** A blocking Media3 facade. Only bounded DASH ranges enter the BTR scheduler. */
private class BtrParallelDataSource(
    private val upstream: DataSource.Factory, private val provider: BtrRouteProvider?,
    private val downloader: BtrDownloader, private val requests: BtrDashRequests?
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var fallback: DataSource? = null
    private var job: Job? = null
    private var output: Channel<ByteArray>? = null
    private var current: ByteArray? = null
    private var offset = 0
    private var uri: Uri? = null
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        close(); uri = dataSpec.uri
        val settings = BtrSettingsStore.load()
        // Progressive requests can span an entire movie and have no playback deadline.
        // They must stay streaming; splitting a whole file is not what upstream schedules.
        val context = requests?.find(dataSpec)
        if (!settings.enabled || dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > 32L * 1024 * 1024 ||
            (requests != null && context == null)) {
            val source = upstream.createDataSource()
            fallback = source
            listeners.forEach(source::addTransferListener)
            source.addTransferListener(BtrDiagnosticTransferListener())
            BtrRuntimeDiagnostics.counters.transport(if (!settings.enabled) "BTR 关闭：原有单连接" else "单连接：当前请求不具备片段边界")
            return source.open(dataSpec)
        }
        if (context?.startup == true && settings.autoConcurrency && requests?.beginSession() == true) BtrAutoConcurrency.newSession()
        val routes = provider ?: object : BtrRouteProvider { override fun urls() = listOf(dataSpec.uri) }
        val channel = Channel<ByteArray>(1)
        output = channel
        BtrRuntimeDiagnostics.counters.transport("片段调度：${if (context?.kind == "meta") "索引" else "音视频"}，${dataSpec.length / 1024} KiB")
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                downloader.download(dataSpec, upstream, routes, listeners.toList(), context ?: BtrDownloader.Request()) { channel.send(it) }
                channel.close()
            } catch (e: Throwable) { channel.close(e) }
        }
        return dataSpec.length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        fallback?.let { return it.read(buffer, offset, length) }
        if (current == null) {
            val channel = output ?: return C.RESULT_END_OF_INPUT
            current = try { runBlocking { channel.receive() } }
                catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) { return C.RESULT_END_OF_INPUT }
                catch (e: Exception) { throw if (e is IOException) e else IOException("BTR 下载已结束或取消", e) }
            this.offset = 0
        }
        val piece = current!!
        val count = minOf(length, piece.size - this.offset)
        piece.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        if (this.offset == piece.size) current = null
        return count
    }
    override fun getUri() = fallback?.uri ?: uri
    override fun getResponseHeaders(): Map<String, List<String>> = fallback?.responseHeaders ?: emptyMap()
    override fun close() {
        job?.cancel(); job = null
        output?.cancel(); output = null
        runCatching { fallback?.close() }; fallback = null
        current = null; offset = 0; uri = null
    }
}
