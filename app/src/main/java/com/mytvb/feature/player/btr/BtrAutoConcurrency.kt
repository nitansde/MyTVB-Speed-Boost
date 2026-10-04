package com.mytvb.feature.player.btr

import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min

/**
 * The same conservative ladder used by BTR's IDM controller.  It is deliberately shared by
 * audio and video: increasing both independently is a common cause of 4K stalls on a TV.
 */
internal object BtrAutoConcurrency {
    private val ladder = intArrayOf(8, 12, 16, 24, 32)
    private val lock = Any()
    private val buckets = ArrayDeque<Bucket>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var level = 0
    private var changedAt = 0L
    private var lastActivityAt = 0L
    private var pressureSince = 0L
    private var startupUntil = 0L
    private var lastSessionAt = 0L
    private var baseLevel = 0
    private var saturated = false
    private var saturatedSince = 0L
    private var saturatedMillis = 0L
    private var trial: Trial? = null
    private val restUntil = HashMap<Int, Long>()

    fun threads(): Int = synchronized(lock) { ladder[level] }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun newSession() = synchronized(lock) {
        val now = now()
        if (now - lastSessionAt < 1_000L) return@synchronized
        lastSessionAt = now
        buckets.clear()
        pressureSince = 0L
        trial = null
        baseLevel = level
        val target = startupLevel(now)
        if (target > level) setLevelLocked(target, "起步先用 ${ladder[target]} 线程", now)
        startupUntil = if (level > baseLevel) now + 30_000L else 0L
    }

    fun delivered(bytes: Long) = synchronized(lock) {
        val at = now()
        val last = buckets.lastOrNull()
        if (last != null && at - last.at < 250L) last.bytes += bytes
        else buckets.addLast(Bucket(at, bytes))
        pruneLocked(at)
        judgeTrialLocked(at)
    }

    fun activity() = synchronized(lock) { lastActivityAt = now() }

    fun demand(active: Int, limit: Int, queued: Int) = synchronized(lock) {
        val now = now()
        val next = active >= limit && queued > 0
        if (next == saturated) return@synchronized
        if (saturated) saturatedMillis += now - saturatedSince
        saturated = next
        saturatedSince = now
    }

    fun stall(reason: String = "播放卡顿") = synchronized(lock) { stepUpLocked(reason, true) }

    fun slow() = synchronized(lock) {
        if (saturationLocked(now()) >= 0.6) stepUpLocked("连接排队等太久", false) else false
    }

    fun pushback(status: Int) = synchronized(lock) {
        val target = max(0, level - 1)
        restUntil[level] = now() + 180_000L
        if (target == level) false else setLevelLocked(target, "服务器返回 $status", now())
    }

    fun buffer(aheadSeconds: Double, playing: Boolean) = synchronized(lock) {
        val at = now()
        if (startupUntil > 0L && at >= startupUntil) startupUntil = 0L
        if (startupUntil > 0L && aheadSeconds >= 15.0 && at - changedAt >= 2_500L) {
            if (level > baseLevel) setLevelLocked(level - 1, "开头已跟上，降低线程", at)
            if (level <= baseLevel) startupUntil = 0L
        }
        val downloading = at - lastActivityAt < 1_500L
        val pressed = playing && downloading && aheadSeconds < 6.0
        if (!pressed) {
            pressureSince = 0L
            return@synchronized false
        }
        if (pressureSince == 0L) pressureSince = at
        if (at - pressureSince < 1_000L) return@synchronized false
        pressureSince = 0L
        stepUpLocked("缓冲跟不上播放", false)
    }

    fun status(): Status = synchronized(lock) {
        val at = now()
        Status(
            threads = ladder[level],
            level = level,
            reason = trial?.reason ?: "稳定",
            throughputBps = throughputLocked(at),
            saturation = saturationLocked(at),
            trial = trial?.let { TrialStatus(ladder[it.from], ladder[it.level], at - it.at) }
        )
    }

    private fun startupLevel(at: Long): Int {
        var target = level
        while (target < 2 && (restUntil[target + 1] ?: 0L) <= at) target++
        return target
    }

    private fun stepUpLocked(reason: String, strong: Boolean): Boolean {
        val at = now()
        if (at - changedAt < 2_500L || level >= ladder.lastIndex) return false
        if ((restUntil[level] ?: 0L) > at) return false
        var next = level + 1
        while (next < ladder.size && (restUntil[next] ?: 0L) > at) {
            if (!strong) return false
            next++
        }
        if (next >= ladder.size) return false
        val baseline = throughputLocked(at)
        trial = Trial(level, next, at, baseline, reason)
        return setLevelLocked(next, reason, at)
    }

    private fun setLevelLocked(next: Int, reason: String, at: Long): Boolean {
        if (next == level) return false
        level = next
        changedAt = at
        listeners.forEach { runCatching { it() } }
        return true
    }

    private fun judgeTrialLocked(at: Long) {
        val current = trial ?: return
        if (at - current.at < 10_000L) return
        trial = null
        if (current.baseline <= 0L || saturationLocked(at) < 0.6) return
        if (throughputLocked(at) < current.baseline) {
            restUntil[current.level] = at + 90_000L
            setLevelLocked(current.from, "${ladder[current.level]} 线程没有更快", at)
        }
    }

    private fun throughputLocked(at: Long): Long {
        pruneLocked(at)
        if (buckets.isEmpty()) return 0L
        val first = buckets.first().at
        val last = buckets.last().at
        return (buckets.sumOf { it.bytes } * 1000L) / max(1000L, last - first + 250L)
    }

    private fun saturationLocked(at: Long): Double {
        var millis = saturatedMillis
        if (saturated) millis += at - saturatedSince
        return min(1.0, millis / 5_000.0)
    }

    private fun pruneLocked(at: Long) {
        while (buckets.isNotEmpty() && at - buckets.first().at > 5_000L) buckets.removeFirst()
    }

    private fun now() = SystemClock.elapsedRealtime()

    data class Status(
        val threads: Int,
        val level: Int,
        val reason: String,
        val throughputBps: Long,
        val saturation: Double,
        val trial: TrialStatus?
    )

    data class TrialStatus(val from: Int, val to: Int, val ageMs: Long)
    private data class Bucket(val at: Long, var bytes: Long)
    private data class Trial(val from: Int, val level: Int, val at: Long, val baseline: Long, val reason: String)
}
