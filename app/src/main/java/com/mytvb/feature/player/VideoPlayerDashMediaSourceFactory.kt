package com.mytvb.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@OptIn(UnstableApi::class)
internal class VideoPlayerDashMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val urlNormalizer: (String) -> String,
    private val networkFactory: DataSource.Factory = dataSourceFactory,
    private val cache: (DataSource.Factory) -> DataSource.Factory = { it }
) {

    /**
     * createMediaSource 的带 CDN state 返回版本：把本次创建的 failover state 一并吐出，
     * 供外层（ViewModel/Controller）在卡顿时调用 penalizeCurrentHost 降权。
     */
    internal data class MediaSourceWithCdnState(
        val mediaSource: MediaSource,
        val cdnFailoverStates: List<VideoPlayerCdnFailoverState>
    )

    companion object {
    }

    private val loadErrorPolicy = object : LoadErrorHandlingPolicy {
        override fun getFallbackSelectionFor(
            options: LoadErrorHandlingPolicy.FallbackOptions,
            errorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): LoadErrorHandlingPolicy.FallbackSelection? = null

        override fun getRetryDelayMsFor(
            errorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long = 500L

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 5
    }

    fun createMediaSource(route: DashRoute): MediaSource = createMediaSourceWithCdnState(route).mediaSource

    fun createMediaSourceWithCdnState(route: DashRoute): MediaSourceWithCdnState {
        val hasSegmentIndex = route.videoRepresentation.segmentBase != null &&
            (route.audioRepresentation == null || route.audioRepresentation.segmentBase != null)
        if (hasSegmentIndex && route.videoUrls.isNotEmpty() && (route.audioRepresentation == null || route.audioUrls.isNotEmpty())) {
            val video = route.videoRepresentation.copy(baseUrl = route.videoUrls.first(), backupUrls = route.videoUrls.drop(1))
            val audio = route.audioRepresentation?.let { it.copy(baseUrl = route.audioUrls.first(), backupUrls = route.audioUrls.drop(1)) }
            val catalog = SeamlessQualityCatalog(listOf(SeamlessVideoOption(video.id, route.codec, video)), audio,
                route.durationMs, route.minBufferTimeMs, video.id, route.codec)
            val created = SeamlessDashMediaSourceFactory(dataSourceFactory, urlNormalizer, networkFactory, cache).createMediaSource(catalog)
            return MediaSourceWithCdnState(created.mediaSource, created.cdnFailoverStates)
        }
        val videoSource = createProgressiveSource(
            urls = route.videoUrls,
            mimeType = route.videoRepresentation.mimeType
        )

        val audioSource = route.audioRepresentation?.let {
            if (route.audioUrls.isNotEmpty()) {
                createProgressiveSource(
                    urls = route.audioUrls,
                    mimeType = it.mimeType
                )
            } else null
        }

        val mediaSource = if (audioSource != null) {
            MergingMediaSource(true, videoSource.mediaSource, audioSource.mediaSource)
        } else {
            videoSource.mediaSource
        }
        return MediaSourceWithCdnState(
            mediaSource = mediaSource,
            cdnFailoverStates = buildList {
                addAll(videoSource.cdnFailoverStates)
                addAll(audioSource?.cdnFailoverStates.orEmpty())
            }
        )
    }

    private fun createProgressiveSource(urls: List<String>, mimeType: String): MediaSourceWithCdnState {
        val primaryUrl = urls.firstOrNull()
            ?: error("No media url candidates available")
        val (sourceFactory, state) = createCandidateAwareFactory(urls)
        val mediaItem = MediaItem.Builder()
            .setUri(primaryUrl)
            .setMimeType(mimeType.takeIf { it.isNotBlank() })
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(sourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            .createMediaSource(mediaItem)
        return MediaSourceWithCdnState(
            mediaSource = mediaSource,
            cdnFailoverStates = listOfNotNull(state)
        )
    }

    private fun createCandidateAwareFactory(urls: List<String>): Pair<DataSource.Factory, VideoPlayerCdnFailoverState?> {
        val candidates = urls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        val state = VideoPlayerCdnFailoverState(candidates = candidates.map(Uri::parse))
        val failoverFactory = VideoPlayerCdnFailoverDataSourceFactory(
            upstreamFactory = dataSourceFactory,
            state = state
        )
        return failoverFactory to state
    }
}
