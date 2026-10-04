package com.mytvb.feature.player.btr

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.feature.player.VideoPlayerCdnFailoverDataSourceFactory
import com.mytvb.feature.player.VideoPlayerCdnFailoverState
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class BtrHttpIntegrationTest {
    @Before fun setup() {
        startKoin { modules(module { single { AppSettingsDataStore(RuntimeEnvironment.getApplication()) } }) }
        BtrSettingsStore.saveEnabled(true); BtrSettingsStore.saveAutoConcurrency(false); BtrSettingsStore.saveConcurrency(4)
    }
    @After fun cleanup() { stopKoin() }
    @Test fun realHttp206PassesThroughFailoverWithCorrectRangeAndCleansUpConnections() {
        val server=MockWebServer()
        val requests=CopyOnWriteArrayList<String>()
        val length=512*1024
        val expected=ByteArray(length) { (it%251).toByte() }
        server.dispatcher=object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val raw=request.getHeader("Range")!!; requests+=raw
                val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(raw)!!
                val start=range.groupValues[1].toInt(); val end=range.groupValues[2].toInt()
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/$length")
                    .setBody(Buffer().write(expected,start,end-start+1))
            }
        }
        server.start()
        val uri=Uri.parse(server.url("/video.m4s").toString())
        val client=OkHttpClient()
        val routes=VideoPlayerCdnFailoverState(listOf(uri))
        val source=BtrParallelDataSourceFactory(VideoPlayerCdnFailoverDataSourceFactory(OkHttpDataSource.Factory(client),routes),routes).createDataSource()
        try {
            val before=BtrRuntimeDiagnostics.counters.snapshot()
            assertEquals(length.toLong(),source.open(DataSpec.Builder().setUri(uri).setLength(length.toLong()).build()))
            val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val n=source.read(buffer,0,buffer.size); if(n==C.RESULT_END_OF_INPUT) break; output.write(buffer,0,n) }
            assertArrayEquals(expected,output.toByteArray())
            assertEquals(4,requests.size)
            assertTrue(requests.contains("bytes=0-131071"))
            source.close()
            val after=BtrRuntimeDiagnostics.counters.snapshot()
            assertEquals(length.toLong(),after.bytes-before.bytes)
            assertEquals(0,after.connections)
        } finally { source.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun generatedDashManifestParsesWithAndWithoutAudioAndRetainsSegmentBoundaries() {
        val video=com.mytvb.feature.player.DashRepresentation(120,"video/mp4","avc1.640028",20_000_000,
            baseUrl="https://cdn.example/video.m4s?token=a&b=1",segmentBase=com.mytvb.feature.player.DashSegmentBase("0-959","960-1335"))
        val catalog=com.mytvb.feature.player.SeamlessQualityCatalog(
            listOf(com.mytvb.feature.player.SeamlessVideoOption(120,com.mytvb.model.video.quality.VideoCodecEnum.AVC,video)),
            null,300_000,1_500,120,com.mytvb.model.video.quality.VideoCodecEnum.AVC)
        for(audio in listOf(null,video.copy(id=30280,mimeType="audio/mp4",codecs="mp4a.40.2"))) {
            val xml=com.mytvb.feature.player.SeamlessDashMpdBuilder.buildAdaptiveOnDemandMpd(catalog.copy(audioRepresentation=audio))
            val manifest=androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(Uri.parse("https://example/manifest.mpd"),xml.byteInputStream())
            assertEquals(300_000L,manifest.durationMs)
            assertEquals(if(audio==null) 1 else 2,manifest.getPeriod(0).adaptationSets.size)
            val representation=manifest.getPeriod(0).adaptationSets[0].representations[0]
            assertEquals(960L,representation.indexUri!!.start)
            assertEquals(376L,representation.indexUri!!.length)
        }
    }
    @Test fun liveDeadlinesFollowSpeedAndPauseWithoutCrossingMediaSources() {
        var now=1000.0
        val first=BtrMediaClock { now }; val second=BtrMediaClock { now }
        first.update(5000,2f,true); second.update(0,1f,false)
        assertEquals(6000.0,first.deadline(15_000_000),.001)
        now=2000.0
        assertEquals(6000.0,first.deadline(15_000_000),.001)
        first.update(7000,2f,false)
        now=3000.0
        assertEquals(7000.0,first.deadline(15_000_000),.001)
        assertEquals(18000.0,second.deadline(15_000_000),.001)
    }
}
