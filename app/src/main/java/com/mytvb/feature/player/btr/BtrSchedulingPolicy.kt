package com.mytvb.feature.player.btr

import kotlin.math.*

/** Port of idm-downloader.js at bbf4d3d. Keep the policy separate from Android I/O. */
internal class BtrSchedulingPolicy {
    data class Piece(val index: Int, val start: Long, val end: Long) {
        val length: Int get() = Math.toIntExact(end - start + 1)
    }
    data class TrialState(var waited: Int = 0, var cursor: Int = 0)
    private var turn = 0
    @Volatile var connectionBps = 0.0; private set
    var pieceMs = 0.0; private set

    @Synchronized fun record(bytes: Int, elapsed: Double) {
        if (bytes < 48 * 1024 || elapsed <= 0) return
        val bps = bytes * 1000 / elapsed
        connectionBps = if (connectionBps > 0) connectionBps * .7 + bps * .3 else bps
        pieceMs = if (pieceMs > 0) pieceMs * .7 + elapsed * .3 else elapsed
    }
    @Synchronized fun minChunk(length: Long, limit: Int, hosts: Int): Long {
        if (connectionBps == 0.0) return 65536
        val target = floor(connectionBps * .6 / 65536) * 65536
        val spread = ceil(length.toDouble() / max(1, min(max(4, hosts), limit)))
        return max(65536.0, min(1048576.0, min(target, spread))).toLong()
    }
    @Synchronized fun hedgeDelay(): Double = if (pieceMs > 0) max(250.0, min(900.0, floor(pieceMs * 1.5 + .5))) else 900.0

    @Synchronized fun <T> assign(input: List<T>, count: Int, trial: TrialState, speed: (T) -> Double): List<T> {
        if (input.isEmpty() || count <= 0) return emptyList()
        if (input.size == 1) return List(count) { input[0] }
        val thisTurn = turn
        turn = (turn + 1) % 4096
        var urls = input
        var known = urls.map { max(0.0, speed(it)) }
        if (known.none { it > 0 }) return List(count) { urls[(it + thisTurn) % urls.size] }
        val top = known.max()
        urls = urls.filterIndexed { i, _ -> known[i] == 0.0 || known[i] >= top / 12 }
        known = urls.map(speed)
        val unknown = urls.filterIndexed { i, _ -> known[i] == 0.0 }
        var trials = min(unknown.size * 2, count / 4)
        if (trials == 0 && unknown.isNotEmpty() && count >= 2 && ++trial.waited >= 4) trials = 1
        if (trials > 0) trial.waited = 0
        if (unknown.isNotEmpty()) { urls = urls.filterIndexed { i, _ -> known[i] > 0 }; known = urls.map(speed) }
        val weights = known.map { max(it, top * .05) }
        val total = weights.sum()
        val credit = DoubleArray(weights.size)
        val order = urls.indices.map { (it + thisTurn) % urls.size }
        val result = ArrayList<T>()
        repeat(count - trials) {
            var best = order[0]
            for (i in order) { credit[i] += weights[i]; if (credit[i] > credit[best]) best = i }
            credit[best] -= total
            result += urls[best]
        }
        repeat(trials) { result += unknown[(it + trial.cursor) % unknown.size] }
        trial.cursor = (trial.cursor + trials) % 4096
        return result
    }

    class Rescue(limit: Int) {
        private var progress = max(0, limit)
        private var stale = max(1, limit)
        @Synchronized fun claimProgress(): Boolean = if (progress > 0) { progress--; true } else false
        @Synchronized fun claimStale(): Boolean = if (stale > 0) { stale--; true } else false
    }
    data class Progress(val started: Double, val bytes: Int, val length: Int, val lastProgress: Double)
    @Synchronized fun hedgeDue(p: Progress, now: Double, delay: Double, deadline: Double, copyBps: Double, rescue: Rescue): Boolean {
        val ran = now - p.started
        if (ran < delay) return false
        if (p.bytes == 0 || now - p.lastProgress >= delay) return true
        val rate = p.bytes * 1000 / ran
        val finish = now + max(0, p.length - p.bytes) * 1000 / rate
        if (finish <= deadline - 1500) return false
        if (copyBps > rate * 1.5) return true
        if (connectionBps <= 0 || rate < connectionBps * .6) return true
        if (copyBps <= 0) return true
        return finish > deadline && rescue.claimStale()
    }
    companion object {
        fun split(start: Long, end: Long, concurrency: Int, minimum: Long): List<Piece> {
            val length = end - start + 1
            require(length > 0)
            val count = max(1, min(concurrency.coerceIn(1, 512).toLong(), (length + max(32768, minimum) - 1) / max(32768, minimum))).toInt()
            val base = length / count
            val remainder = length % count
            var cursor = start
            return List(count) { i ->
                val size = base + if (i < remainder) 1 else 0
                Piece(i, cursor, cursor + size - 1).also { cursor += size; require(size <= Int.MAX_VALUE) }
            }
        }
        fun urgency(remaining: Double): Int = when { !remaining.isFinite() -> 0; remaining <= 0 -> 12; remaining <= 750 -> 9; remaining <= 2000 -> 6; else -> 0 }
        fun rank(priority: Int, queuedAt: Double, now: Double, deadline: Double, ages: Boolean): Double =
            priority + urgency(deadline - now) + if (ages) (now - queuedAt) * 20 / 900 else 0.0
    }
}
