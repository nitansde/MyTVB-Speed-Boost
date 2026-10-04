package com.mytvb.feature.player.btr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Transport adaptation of upstream downloadRange/downloadPiece/attempt. See docs/btr/PORT.md. */
internal class BtrDownloader {
    val policy = BtrSchedulingPolicy()
    private val gate = BtrPriorityGate(::now)
    private val trials = java.util.WeakHashMap<BtrRouteProvider, BtrSchedulingPolicy.TrialState>()
    companion object {
        private val io = Executors.newCachedThreadPool { r -> Thread(r, "BTR-network").apply { isDaemon = true } }
        private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    private fun now() = System.nanoTime() / 1_000_000.0
    data class Request(val kind: String = "media", val startup: Boolean = false, val deadline: () -> Double = { Double.POSITIVE_INFINITY })
    private data class Result(val bytes: ByteArray, val uri: Uri, val total: Long?)
    private class Progress {
        private val output = ByteArrayOutputStream()
        @Volatile var base = 0
        @Volatile var started = Double.NaN
        @Volatile var last = Double.NaN
        @Volatile var headers = false
        @Volatile var straggler = false
        @Volatile var deficit = 0
        @Synchronized fun begin(bytes: ByteArray, at: Double) { output.write(bytes); base = bytes.size; started = at; last = at }
        @Synchronized fun append(bytes: ByteArray, count: Int, at: Double) { output.write(bytes, 0, count); last = at }
        @Synchronized fun snapshot() = output.toByteArray()
        @Synchronized fun size() = output.size()
    }
    private fun candidates(provider: BtrRouteProvider, preferred: List<Uri>, index: Int, round: Int): List<Uri> {
        val offset = if (preferred.isEmpty()) 0 else round % preferred.size
        val rotated = preferred.drop(offset) + preferred.take(offset)
        val rescue = provider.rescueCandidates().filterNot { it in rotated }
        val rest = (rotated.drop(1) + rescue).sortedByDescending(provider::speed)
        return (rotated.take(1) + rest + provider.ordered(index)).distinct()
    }
    suspend fun download(spec: DataSpec, factory: DataSource.Factory, provider: BtrRouteProvider,
        listeners: List<TransferListener>, request: Request, emit: suspend (ByteArray) -> Unit) {
        val unsubscribe = BtrAutoConcurrency.subscribe {
            val limit = BtrAutoConcurrency.threads()
            maintenance.launch { if (BtrSettingsStore.load().autoConcurrency) gate.setLimit(limit) }
        }
        try { downloadRange(spec, factory, provider, listeners, request, emit) } finally { unsubscribe() }
    }
    private suspend fun downloadRange(spec: DataSpec, factory: DataSource.Factory, provider: BtrRouteProvider,
        listeners: List<TransferListener>, request: Request, emit: suspend (ByteArray) -> Unit) {
        val settings = BtrSettingsStore.load()
        val concurrency = if (settings.autoConcurrency) BtrAutoConcurrency.threads() else settings.concurrency
        val totals = mutableSetOf<Long>()
        suspend fun deliver(result: Result) {
            result.total?.let { totals += it }
            if (totals.size > 1) throw IOException("不同 CDN 文件长度不一致")
            emit(result.bytes)
        }
        if (request.kind == "meta") {
            val p = BtrSchedulingPolicy.Piece(0, spec.position, spec.position + spec.length - 1)
            var failure: IOException? = null
            val began = now()
            repeat(3) { round ->
                if (round > 0) { if (now() - began > 25000) throw failure!!; delay(min(2000L, 500L shl (round - 1))) }
                val urls = provider.startupCandidates().distinct().take(3).ifEmpty { if (round > 0) provider.ordered(round).take(3) else emptyList() }
                try {
                    val r = race(p, urls, spec, factory, provider, listeners, request, "meta", 220, BtrSchedulingPolicy.Rescue(0), ByteArray(0))
                    deliver(r); return
                } catch (e: IOException) { failure = e }
            }
            throw failure ?: IOException("没有可用 CDN")
        }
        var urls = provider.rangeCandidates().distinct().ifEmpty { listOf(spec.uri) }
        var start = spec.position
        val end = start + spec.length - 1
        var head: Result? = null
        if (request.startup) {
            val p = BtrSchedulingPolicy.Piece(0, start, min(end, start + 65536 - 1))
            head = piece(p, urls, spec, factory, provider, listeners, request, "probe", 220, BtrSchedulingPolicy.Rescue(0))
            deliver(head)
            start = p.end + 1
            if (start > end) return
        }
        val reserve = if (request.startup) max(1, min(16, ceil(concurrency / 8.0).toInt())) else if (concurrency >= 8) min(8, max(1, ceil(concurrency / 8.0).toInt())) else 0
        val mediaBudget = max(1, concurrency - reserve)
        val audioBudget = max(1, min(mediaBudget, ceil(concurrency / 8.0).toInt()))
        val budget = if (request.startup) if (request.kind == "audio") audioBudget else max(1, mediaBudget - audioBudget) else mediaBudget
        val pieces = BtrSchedulingPolicy.split(start, end, budget, policy.minChunk(end - start + 1, budget, urls.size))
        val proven = if (head != null) urls.filter { it == head.uri || provider.speed(it) > 0 }.ifEmpty { listOf(head.uri) } else urls
        val primaries = synchronized(trials) { policy.assign(proven, pieces.size, trials.getOrPut(provider) { BtrSchedulingPolicy.TrialState() }) { provider.speed(it).toDouble() } }
        if (head != null) urls = listOf(head.uri) + urls.filterNot { it == head.uri }
        val rescue = BtrSchedulingPolicy.Rescue(reserve)
        coroutineScope {
            val tasks = pieces.mapIndexed { index, p -> async {
                val primary = primaries[index]
                val adjusted = if (head != null) p.copy(index = p.index + 1) else p
                piece(adjusted, listOf(primary) + urls.filterNot { it == primary }, spec, factory, provider, listeners, request,
                    if (request.startup) "startup" else "ordinary", if (request.startup) 120 - min(30, adjusted.index) else 50 - min(20, p.index), rescue)
            } }
            for (task in tasks) deliver(task.await())
        }
    }
    private suspend fun piece(p: BtrSchedulingPolicy.Piece, preferred: List<Uri>, spec: DataSpec, factory: DataSource.Factory,
        provider: BtrRouteProvider, listeners: List<TransferListener>, request: Request, mode: String, priority: Int, rescue: BtrSchedulingPolicy.Rescue): Result {
        BtrRuntimeDiagnostics.counters.rangeStarted()
        var complete = false
        val prefix = AtomicReference(ByteArray(0))
        var failure: IOException? = null
        val began = now()
        try {
            repeat(3) { round ->
                if (round > 0) { if (now() - began > 25000) throw failure!!; delay(min(2000L, 500L shl (round - 1))) }
                val urls = candidates(provider, preferred, p.index, round)
                val tried = mutableSetOf<Uri>()
                val limit = min(8, urls.size)
                while (tried.size < limit) {
                    currentCoroutineContext().ensureActive()
                    val untried = urls.filterNot { it in tried }
                    val pair = untried.filter(provider::allows).ifEmpty { untried }.take(if (mode == "probe") limit else 2)
                    if (pair.isEmpty()) break
                    tried += pair
                    try {
                        val r = race(p, pair, spec, factory, provider, listeners, request, mode, priority, rescue, prefix.get(), prefix)
                        complete = true
                        BtrRuntimeDiagnostics.counters.delivered(p.length.toLong())
                        if (BtrSettingsStore.load().autoConcurrency) BtrAutoConcurrency.delivered(p.length.toLong())
                        return r
                    } catch (e: IOException) { failure = e; BtrRuntimeDiagnostics.counters.retry() }
                }
            }
            throw failure ?: IOException("没有可用 CDN")
        } finally { BtrRuntimeDiagnostics.counters.rangeFinished(complete) }
    }
    private suspend fun race(p: BtrSchedulingPolicy.Piece, urls: List<Uri>, spec: DataSpec, factory: DataSource.Factory,
        provider: BtrRouteProvider, listeners: List<TransferListener>, request: Request, mode: String, priority: Int,
        rescue: BtrSchedulingPolicy.Rescue, prefix: ByteArray, retained: AtomicReference<ByteArray> = AtomicReference(prefix)): Result = coroutineScope {
        if (urls.isEmpty()) throw IOException("没有可用 CDN")
        val channel = Channel<kotlin.Result<Result>>(Channel.UNLIMITED)
        val progresses = urls.map { Progress() }
        val firstFailed = CompletableDeferred<Unit>()
        val askedAt = now()
        val jobs = urls.mapIndexed { index, uri -> launch {
            val progress = progresses[index]
            try {
                if (index > 0) {
                    if (mode == "meta") delay(if (index == 1) 120 else 300)
                    else if (mode != "probe") {
                        val hasDeadline = request.deadline().isFinite()
                        val measured = policy.hedgeDelay()
                        val wait = if (mode == "startup") min(if (hasDeadline) 200.0 else 250.0, measured) else measured
                        var earlyAt = Double.POSITIVE_INFINITY
                        while (!firstFailed.isCompleted) {
                            val first = progresses[0]; val at = now()
                            if (first.straggler && earlyAt.isInfinite() && rescue.claimProgress()) earlyAt = max(at, first.started + 250)
                            if (at >= earlyAt) break
                            if (hasDeadline) {
                                if (!first.started.isNaN() && policy.hedgeDue(BtrSchedulingPolicy.Progress(first.started, first.size() - first.base, p.length - first.base, first.last), at, wait, request.deadline(), provider.speed(uri).toDouble(), rescue)) break
                            } else if (at - askedAt >= wait) break
                            delay(50)
                        }
                    }
                }
                val result = attempt(p, uri, spec, factory, provider, listeners, request, priority + if (index > 0) 20 else 0,
                    if (mode == "meta" || mode == "probe") 0 else if (mode == "startup") 1 else 2, progress,
                    begin = {
                        val saved = retained.get()
                        val live = if (index > 0) progresses[0].snapshot() else ByteArray(0)
                        val longest = if (live.size > saved.size) live else saved
                        if (longest.size >= 32768 && longest.size < p.length) longest else ByteArray(0)
                    }, observe = index == 0)
                channel.send(kotlin.Result.success(result))
            } catch (e: CancellationException) { throw e }
            catch (e: IOException) { channel.send(kotlin.Result.failure(e)) }
            finally {
                val saved = progress.snapshot()
                if (saved.size < p.length) retained.updateAndGet { if (saved.size > it.size) saved else it }
                if (index == 0) firstFailed.complete(Unit)
            }
        } }
        try {
            var failure: Throwable? = null
            repeat(urls.size) {
                val r = channel.receive()
                if (r.isSuccess) return@coroutineScope r.getOrThrow()
                failure = r.exceptionOrNull()
            }
            throw failure ?: IOException("所有 CDN 请求失败")
        } finally { jobs.forEach { it.cancel() }; channel.close() }
    }
    private suspend fun attempt(p: BtrSchedulingPolicy.Piece, uri: Uri, spec: DataSpec, factory: DataSource.Factory,
        provider: BtrRouteProvider, listeners: List<TransferListener>, request: Request, priority: Int, queueClass: Int,
        progress: Progress, begin: () -> ByteArray, observe: Boolean): Result {
        val settings = BtrSettingsStore.load()
        val release = gate.acquire(priority, queueClass, request.deadline,
            if (settings.autoConcurrency) BtrAutoConcurrency.threads() else settings.concurrency, settings.autoConcurrency)
        try {
            progress.begin(begin(), now())
            return try {
                coroutineScope {
                    val transfer = async { network(p, uri, spec, factory, listeners, progress, request, observe) }
                    val watchdog = launch {
                        while (isActive) {
                            delay(50)
                            val at = now()
                            if (at - progress.started >= 15000 || (!progress.headers && at - progress.started >= 5500) || (progress.headers && at - progress.last >= 4000)) {
                                throw RangeTimeout()
                            }
                        }
                    }
                    try { transfer.await() } finally { watchdog.cancel() }
                }.also {
                    val bytes = it.bytes.size - progress.base
                    val elapsed = max(1.0, now() - progress.started)
                    policy.record(bytes, elapsed)
                    provider.success(uri, if (bytes >= 49152) (bytes * 1000 / elapsed).toLong() else 0)
                    BtrRuntimeDiagnostics.counters.updateConnectionSpeed(policy.connectionBps.toLong())
                }
            } catch (e: CancellationException) {
                val bytes = progress.size() - progress.base
                if (bytes >= 49152) provider.sample(uri, (bytes * 1000 / max(1.0, now() - progress.started)).toLong())
                throw e
            } catch (e: IOException) {
                val bytes = progress.size() - progress.base
                val status = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                provider.failure(uri, bytes.toLong(), status)
                if (settings.autoConcurrency) {
                    if (status == 412 || status == 429) BtrAutoConcurrency.pushback(status)
                    else if (e is RangeTimeout && bytes == 0) BtrAutoConcurrency.slow()
                }
                BtrRuntimeDiagnostics.counters.error(if (status != null) "HTTP $status" else e.javaClass.simpleName)
                throw e
            }
        } finally { withContext(NonCancellable) { release() } }
    }
    private class RangeTimeout : IOException("CDN 请求超时")
    private suspend fun network(p: BtrSchedulingPolicy.Piece, uri: Uri, template: DataSpec, factory: DataSource.Factory,
        listeners: List<TransferListener>, progress: Progress, request: Request, observe: Boolean): Result = suspendCancellableCoroutine { continuation ->
        val source = factory.createDataSource()
        listeners.forEach(source::addTransferListener)
        source.addTransferListener(BtrDiagnosticTransferListener())
        val task = io.submit {
            try {
                if (!continuation.isActive) return@submit
                val start = p.start + progress.base
                val spec = template.buildUpon().setUri(uri).setFlags(template.flags or BTR_ROUTE_FLAG).setPosition(start).setLength(p.end - start + 1).build()
                source.open(spec)
                if (!continuation.isActive) return@submit
                val status = (source as? HttpDataSource)?.responseCode ?: (source as? BtrHttpResponse)?.responseCode
                if (status != null && status != 206) throw IOException("CDN Range 必须返回 HTTP 206，实际 $status")
                val header = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                val range = header?.let { Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).matchEntire(it.trim()) }
                    ?: throw IOException("CDN 未返回有效 Content-Range")
                val a = range.groupValues[1].toLongOrNull(); val b = range.groupValues[2].toLongOrNull()
                val total = range.groupValues[3].toLongOrNull()
                if (a != start || b != p.end || (range.groupValues[3] != "*" && total == null) || (total != null && total <= p.end)) throw IOException("CDN 返回的 Range 不匹配")
                progress.headers = true; progress.last = now()
                val buffer = ByteArray(32768)
                while (progress.size() < p.length) {
                    if (!continuation.isActive || Thread.currentThread().isInterrupted) return@submit
                    val count = source.read(buffer, 0, min(buffer.size, p.length - progress.size()))
                    if (count == C.RESULT_END_OF_INPUT) throw IOException("CDN Range 提前结束")
                    if (count <= 0) continue
                    val at = now(); progress.append(buffer, count, at)
                    if (BtrSettingsStore.load().autoConcurrency) BtrAutoConcurrency.activity()
                    if (observe && request.deadline().isFinite()) {
                        val bps = (progress.size() - progress.base) * 1000 / max(1.0, at - progress.started)
                        val eta = (p.length - progress.size()) * 1000 / bps
                        val remaining = request.deadline() - at
                        progress.deficit = if (eta - remaining >= 250) progress.deficit + 1 else 0
                        if ((eta >= max(0.0, remaining) && policy.connectionBps > 0 && bps < policy.connectionBps * .5 && eta >= 500) || progress.deficit >= 2) progress.straggler = true
                    }
                }
                if (continuation.isActive) continuation.resume(Result(progress.snapshot(), uri, total))
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (e is IOException) e else IOException(e)) }
            finally { runCatching { source.close() } }
        }
        continuation.invokeOnCancellation { task.cancel(true); runCatching { source.close() } }
    }
}
