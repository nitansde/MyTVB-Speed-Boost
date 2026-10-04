package com.mytvb.feature.player.btr

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class BtrDiagnosticsTest {
    @Test fun audioAndVideoRequestsKeepIndependentConnectionsAndCumulativeBytes() {
        val stats = BtrDiagnostics()
        val audio = Any()
        val video = Any()
        stats.connected(audio, "audio.example")
        stats.received(100)
        stats.connected(video, "video.example")
        stats.received(200)
        stats.disconnected(audio)
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.connections)
        assertEquals("video.example", snapshot.hosts)
        assertEquals(300L, snapshot.bytes)
        stats.disconnected(video)
        stats.disconnected(video) // cancellation and normal close may both arrive
        assertEquals(0, stats.snapshot().connections)
    }

    @Test fun concurrentCompletionAndCancellationReturnToIdleWithoutLosingCounts() {
        val stats = BtrDiagnostics()
        val executor = Executors.newFixedThreadPool(8)
        try {
            (0 until 100).map { index ->
                executor.submit {
                    val source = Any()
                    stats.rangeStarted()
                    stats.connected(source, "cdn.example")
                    stats.received(1024)
                    if (index % 2 == 0) stats.retry()
                    stats.disconnected(source)
                    stats.rangeFinished(completed = index % 2 == 0)
                }
            }.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        val snapshot = stats.snapshot()
        assertEquals(0, snapshot.connections)
        assertEquals(0, snapshot.activeRanges)
        assertEquals(50L, snapshot.completedRanges)
        assertEquals(50L, snapshot.retries)
        assertEquals(102400L, snapshot.bytes)
    }
}
