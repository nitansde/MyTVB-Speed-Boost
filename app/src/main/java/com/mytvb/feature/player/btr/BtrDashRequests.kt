package com.mytvb.feature.player.btr

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.*
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.source.chunk.*
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoaderErrorThrower

/** One registry per media source. ChunkSource supplies real segment time/range, never bitrate guesses. */
internal class BtrDashRequests {
    private data class Entry(val uri: String, val start: Long, val end: Long, val request: BtrDownloader.Request)
    private val entries = java.util.ArrayDeque<Entry>()
    val clock = BtrMediaClock()
    private var started = false
    @Synchronized fun beginSession(): Boolean = (!started).also { started = true }
    @Synchronized fun register(spec: DataSpec, request: BtrDownloader.Request) {
        entries.addLast(Entry(spec.uri.toString(), spec.position, spec.position + spec.length, request))
        while (entries.size > 128) entries.removeFirst()
    }
    @Synchronized fun find(spec: DataSpec): BtrDownloader.Request? = entries.toList().asReversed().firstOrNull {
        it.uri == spec.uri.toString() && spec.position >= it.start && spec.length >= 0 && spec.position + spec.length <= it.end
    }?.request
}

/** Clock belongs to a media source, so a preloaded video cannot use the active video's playhead. */
internal class BtrMediaClock(private val now: () -> Double = { System.nanoTime() / 1_000_000.0 }) {
    private data class Sample(val positionMs: Double, val rate: Double, val playing: Boolean, val at: Double)
    @Volatile private var sample = Sample(0.0, 1.0, false, now())
    fun update(positionMs: Long, speed: Float, playing: Boolean) {
        sample = Sample(positionMs.toDouble(), kotlin.math.abs(speed.toDouble()).coerceAtLeast(.25), playing, now())
    }
    fun loading(positionUs: Long, speed: Float) {
        val old = sample
        update(positionUs / 1000, speed, old.playing)
    }
    fun deadline(segmentUs: Long): Double {
        val at = now(); val s = sample
        val position = s.positionMs + if (s.playing) (at - s.at) * s.rate else 0.0
        return at + ((segmentUs / 1000.0 - position) / s.rate).coerceAtLeast(0.0)
    }
}
internal object BtrPlaybackClock {
    private val clocks = mutableMapOf<String, java.lang.ref.WeakReference<BtrMediaClock>>()
    @Synchronized fun bind(uri: String, clock: BtrMediaClock) {
        clocks.entries.removeAll { it.value.get() == null }
        clocks[uri] = java.lang.ref.WeakReference(clock)
    }
    @Synchronized fun update(uri: String?, positionMs: Long, speed: Float, playing: Boolean) {
        clocks[uri]?.get()?.let { clock ->
            clock.update(positionMs, speed, playing)
            BtrRuntimeDiagnostics.counters.playing(clock, speed.toDouble())
        }
    }
}

internal class BtrDashChunkSourceFactory(
    private val delegate: DashChunkSource.Factory,
    private val requests: BtrDashRequests
) : DashChunkSource.Factory by delegate {
    override fun createDashChunkSource(
        manifestLoaderErrorThrower: LoaderErrorThrower, manifest: DashManifest, baseUrlExclusionList: BaseUrlExclusionList,
        periodIndex: Int, adaptationSetIndices: IntArray, trackSelection: ExoTrackSelection, trackType: Int,
        elapsedRealtimeOffsetMs: Long, enableEventMessageTrack: Boolean, closedCaptionFormats: MutableList<Format>,
        playerTrackEmsgHandler: PlayerEmsgHandler.PlayerTrackEmsgHandler?, transferListener: TransferListener?,
        playerId: PlayerId, cmcdConfiguration: CmcdConfiguration?
    ): DashChunkSource {
        val source = delegate.createDashChunkSource(manifestLoaderErrorThrower, manifest, baseUrlExclusionList, periodIndex,
            adaptationSetIndices, trackSelection, trackType, elapsedRealtimeOffsetMs, enableEventMessageTrack,
            closedCaptionFormats, playerTrackEmsgHandler, transferListener, playerId, cmcdConfiguration)
        return object : DashChunkSource by source {
            override fun getNextChunk(loadingInfo: androidx.media3.exoplayer.LoadingInfo, loadPositionUs: Long,
                queue: MutableList<out MediaChunk>, out: ChunkHolder) {
                requests.clock.loading(loadingInfo.playbackPositionUs, loadingInfo.playbackSpeed)
                source.getNextChunk(loadingInfo, loadPositionUs, queue, out)
                val chunk = out.chunk ?: return
                val media = chunk is MediaChunk
                if (media) {
                    val kind = if (trackType == C.TRACK_TYPE_AUDIO) "audio" else "media"
                    val durationUs = chunk.endTimeUs - chunk.startTimeUs
                    val bitrate = chunk.trackFormat.averageBitrate.takeIf { it > 0 }
                        ?: chunk.trackFormat.peakBitrate.takeIf { it > 0 }
                    val bytesPerSecond = if (bitrate != null) bitrate.toLong() / 8 else
                        if (durationUs > 0 && chunk.dataSpec.length > 0) chunk.dataSpec.length * 1_000_000 / durationUs else 0
                    BtrRuntimeDiagnostics.counters.requirement(requests.clock, kind, bytesPerSecond)
                }
                requests.register(chunk.dataSpec, BtrDownloader.Request(
                    kind = if (!media) "meta" else if (trackType == C.TRACK_TYPE_AUDIO) "audio" else "media",
                    startup = media && queue.isEmpty(),
                    deadline = if (media) ({ requests.clock.deadline(chunk.startTimeUs) }) else ({ Double.POSITIVE_INFINITY })
                ))
            }
        }
    }
}
