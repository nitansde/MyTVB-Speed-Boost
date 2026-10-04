package com.mytvb.feature.player.btr

import android.net.Uri
import com.mytvb.feature.player.VideoPlayerCdnFailoverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BtrSchedulerPolicyTest {
    @Test
    fun measuredRouteIsPreferredAndEmptyFailuresAreTemporarilyExcluded() {
        val slow = Uri.parse("https://slow.bilivideo.com/video.m4s?x=1")
        val fast = Uri.parse("https://fast.bilivideo.com/video.m4s?x=1")
        val state = VideoPlayerCdnFailoverState(listOf(slow, fast))

        state.success(slow, 100_000L)
        state.success(fast, 2_000_000L)
        assertEquals(fast, state.rangeCandidates().first())

        state.failure(fast, 0L)
        state.failure(fast, 0L)
        assertTrue(state.rangeCandidates().none { it.host == "fast.bilivideo.com" })
    }
}
