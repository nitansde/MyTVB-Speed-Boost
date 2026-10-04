package com.mytvb.feature.player.btr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coroutine adaptation of upstream Semaphore; queue ordering and aging are unchanged. */
internal class BtrPriorityGate(private val now: () -> Double) {
    private val lock = Mutex()
    private var limit = 8
    private var active = 0
    private var sequence = 0L
    private val queue = mutableListOf<Entry>()
    private data class Entry(val priority: Int, val queueClass: Int, val deadline: () -> Double,
        val queued: Double, val sequence: Long, val ready: CompletableDeferred<Unit> = CompletableDeferred(), var granted: Boolean = false)
    suspend fun acquire(priority: Int, queueClass: Int, deadline: () -> Double, concurrency: Int, auto: Boolean): suspend () -> Unit {
        val entry = lock.withLock {
            limit = concurrency.coerceIn(1, 128)
            Entry(priority, queueClass, deadline, now(), sequence++).also { queue += it; drain(auto) }
        }
        try { entry.ready.await() } catch (e: Throwable) {
            withContext(NonCancellable) { lock.withLock { queue.remove(entry); if (entry.granted) active--; drain(auto) } }
            throw e
        }
        var released = false
        return { lock.withLock { if (!released) { released = true; active--; drain(auto) } } }
    }
    suspend fun setLimit(value: Int) = lock.withLock { limit = value.coerceIn(1, 128); drain(true) }
    private fun drain(auto: Boolean) {
        while (active < limit && queue.isNotEmpty()) {
            val at = now()
            val best = queue.minWith(compareBy<Entry> { it.queueClass }.thenByDescending {
                val deadline = it.deadline()
                BtrSchedulingPolicy.rank(it.priority, it.queued, at, deadline, it.queueClass == 2 && deadline.isFinite())
            }.thenBy { it.sequence })
            queue.remove(best); active++; best.granted = true; best.ready.complete(Unit)
        }
        if (auto) BtrAutoConcurrency.demand(active, limit, queue.size)
        BtrRuntimeDiagnostics.counters.scheduler(queue.size, limit)
    }
}
