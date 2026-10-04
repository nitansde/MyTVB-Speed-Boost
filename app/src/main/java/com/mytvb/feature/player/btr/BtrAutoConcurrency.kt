package com.mytvb.feature.player.btr

import android.os.SystemClock
import kotlin.math.*

/** Direct state-machine port of upstream createAutoConcurrency (bbf4d3d). */
internal open class BtrAutoController(private val now: () -> Long) {
    private val ladder = intArrayOf(8, 12, 16, 24, 32)
    private var level = 0
    private var changedAt = 0L
    private var reason = "起步"
    private var trial: Trial? = null
    private val resting = mutableMapOf<Int, Rest>()
    private val buckets = mutableListOf<Bucket>()
    private var lastActivityAt = Long.MIN_VALUE / 2
    private var saturated = false
    private var saturatedSince = 0L
    private val spans = mutableListOf<Pair<Long, Long>>()
    private val aheadSamples = mutableListOf<Pair<Long, Double>>()
    private var pressureSince = 0L
    private var startup: Startup? = null
    private val listeners = linkedSetOf<() -> Unit>()
    @Synchronized fun threads() = ladder[level]
    @Synchronized fun subscribe(listener: () -> Unit): () -> Unit { listeners += listener; return { synchronized(this) { listeners -= listener; Unit } } }
    private fun rest(l: Int, at: Long): Rest? = resting[l]?.takeIf { it.until > at }.also { if (it == null) resting.remove(l) }
    private fun setLevel(next: Int, why: String, nextTrial: Trial?) {
        level = next; changedAt = now(); reason = why; trial = nextTrial; pressureSince = 0
        listeners.toList().forEach { runCatching(it) }
    }
    private fun stepUp(why: String, strong: Boolean): Boolean {
        val at = now()
        if (at - changedAt < 2500 || rest(level, at)?.hard == true) return false
        var next = level + 1
        while (next < ladder.size) {
            val r = rest(next, at) ?: break
            if (r.hard || !strong) return false
            next++
        }
        if (next >= ladder.size) return false
        setLevel(next, why, Trial(level, next, at, throughput(at)))
        startup = null
        return true
    }
    private fun stepDown(target: Int, restLevel: Int, why: String, ms: Long, hard: Boolean): Boolean {
        resting[restLevel] = Rest(now() + ms, hard)
        if (target >= level) return false
        setLevel(target, why, null); return true
    }
    private fun throughput(at: Long): Long {
        buckets.removeAll { at - it.at > 5000 }
        if (buckets.isEmpty()) return 0
        return buckets.sumOf { it.bytes } * 1000 / max(1000, buckets.last().at - buckets.first().at + 250)
    }
    private fun saturation(at: Long): Double {
        val from = at - 5000
        spans.removeAll { it.second <= from }
        var busy = spans.sumOf { max(0, it.second - max(it.first, from)) }
        if (saturated) busy += max(0, at - max(saturatedSince, from))
        return min(1.0, busy / 5000.0)
    }
    @Synchronized fun delivered(bytes: Long) {
        val at = now(); val last = buckets.lastOrNull()
        if (last != null && at - last.at < 250) last.bytes += bytes else buckets += Bucket(at, bytes)
        throughput(at)
        val t = trial ?: return
        if (at - t.at < 10000) return
        trial = null
        if (t.stalled || saturation(at) < .6 || t.baseline <= 0) return
        if (throughput(at) < t.baseline) stepDown(t.from, t.level, "${ladder[t.level]} 线程没有更快", 90000, false)
    }
    @Synchronized fun activity() { lastActivityAt = now() }
    @Synchronized fun demand(active: Int, limit: Int, queued: Int) {
        val next = active >= limit && queued > 0
        if (next == saturated) return
        val at = now()
        if (saturated) spans += saturatedSince to at
        saturated = next; saturatedSince = at; saturation(at)
    }
    @Synchronized fun stall(reason: String = "播放卡顿"): Boolean { trial?.stalled = true; return stepUp(reason, true) }
    @Synchronized fun slow(): Boolean = if (saturation(now()) >= .6) stepUp("连接排队等太久", false) else false
    @Synchronized fun pushback(status: Int): Boolean = stepDown(max(0, level - 1), level, "服务器返回 $status", 180000, true)
    @Synchronized fun buffer(aheadSeconds: Double, playing: Boolean): Boolean {
        val at = now(); aheadSamples += at to aheadSeconds
        aheadSamples.removeAll { at - it.first > 1250 }
        startup?.let { start ->
            if (at >= start.until) startup = null
            else if (aheadSeconds >= 15 && at - changedAt >= 2500) {
                if (level > start.base) setLevel(level - 1, "开头已跟上，降低线程", null)
                if (level <= start.base) startup = null
            }
        }
        val earlier = aheadSamples.firstOrNull { at - it.first >= 1000 }
        val pressed = playing && at - lastActivityAt < 1500 && aheadSeconds < 6 && earlier != null && aheadSeconds <= earlier.second + .05
        if (!pressed) { pressureSince = 0; return false }
        if (pressureSince == 0L) { pressureSince = at; return false }
        if (at - pressureSince < 1000) return false
        pressureSince = 0
        return stepUp("缓冲跟不上播放", false)
    }
    @Synchronized fun newSession() {
        val at = now()
        aheadSamples.clear(); pressureSince = 0; trial = null; buckets.clear(); spans.clear()
        if (saturated) saturatedSince = at
        val base = startup?.base ?: level
        var target = level
        if (rest(level, at)?.hard != true) while (target < 2 && rest(target + 1, at)?.hard != true) target++
        if (target > level) setLevel(target, "开头先用 ${ladder[target]} 线程", null)
        startup = if (level > base) Startup(base, at + 30000) else null
    }
    @Synchronized fun status(): Status {
        val at = now()
        return Status(ladder[level], level, reason, throughput(at), saturation(at), trial?.let { TrialStatus(ladder[it.from], ladder[it.level], at - it.at) })
    }
    data class Status(val threads: Int, val level: Int, val reason: String, val throughputBps: Long, val saturation: Double, val trial: TrialStatus?)
    data class TrialStatus(val from: Int, val to: Int, val ageMs: Long)
    private data class Rest(val until: Long, val hard: Boolean)
    private data class Bucket(val at: Long, var bytes: Long)
    private data class Trial(val from: Int, val level: Int, val at: Long, val baseline: Long, var stalled: Boolean = false)
    private data class Startup(val base: Int, val until: Long)
}
internal object BtrAutoConcurrency : BtrAutoController({ SystemClock.elapsedRealtime() })
