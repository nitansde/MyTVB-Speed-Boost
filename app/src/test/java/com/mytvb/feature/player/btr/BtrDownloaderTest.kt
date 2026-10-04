package com.mytvb.feature.player.btr

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mytvb.core.common.settings.AppSettingsDataStore
import kotlinx.coroutines.*
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BtrDownloaderTest {
    @Before fun setup() {
        startKoin { modules(module { single { AppSettingsDataStore(RuntimeEnvironment.getApplication()) } }) }
        BtrSettingsStore.saveAutoConcurrency(false); BtrSettingsStore.saveConcurrency(4); BtrSettingsStore.saveEnabled(true)
    }
    @After fun cleanup() { stopKoin() }
    private val first = Uri.parse("https://first.bilivideo.com/video.m4s")
    private val second = Uri.parse("https://second.bilivideo.com/video.m4s")
    private val size = 512 * 1024L
    private fun spec() = DataSpec.Builder().setUri(first).setLength(size).build()
    private class Routes(private val values: List<Uri>) : BtrRouteProvider {
        val failures = CopyOnWriteArrayList<Pair<Uri, Long>>()
        override fun urls() = values
        override fun failure(uri: Uri, receivedBytes: Long, statusCode: Int?) { failures += uri to receivedBytes }
    }
    private fun bytes(start: Long, length: Int) = ByteArray(length) { ((start + it) % 251).toByte() }
    private inner class Scripted(
        private val opened: MutableList<DataSpec>, private val delayMs: Long = 0,
        private val failHalf: Boolean = false, private val invalid: Boolean = false,
        private val blockFirst: CountDownLatch? = null, private val closed: AtomicInteger? = null
    ) : DataSource {
        private lateinit var spec: DataSpec
        private var read = 0
        private var didClose = false
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            spec=dataSpec; opened.add(spec)
            if (blockFirst != null && spec.uri == second) assertTrue(blockFirst.await(3, TimeUnit.SECONDS))
            return spec.length
        }
        override fun getResponseHeaders() = mapOf("Content-Range" to listOf("bytes ${if (invalid) spec.position + 1 else spec.position}-${spec.position+spec.length-1}/99999999"))
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (blockFirst != null && spec.uri == first) { blockFirst.countDown(); Thread.sleep(10000) }
            if (failHalf && spec.uri == first && read >= 65536) throw IOException("test interrupted tail")
            if (read >= spec.length) return C.RESULT_END_OF_INPUT
            if(delayMs>0) Thread.sleep(delayMs)
            val count=minOf(length,(spec.length-read).toInt(),32768)
            bytes(spec.position+read,count).copyInto(buffer,offset); read+=count
            return count
        }
        override fun getUri() = if (::spec.isInitialized) spec.uri else null
        @Synchronized override fun close() { if(!didClose) { didClose=true; closed?.incrementAndGet() } }
    }
    @Test fun failedHalfPieceResumesOnlyMissingBytesAndOutputIsOrdered() = runBlocking {
        withTimeout(8000) {
            val opened=CopyOnWriteArrayList<DataSpec>(); val routes=Routes(listOf(first,second)); val out=ByteArrayOutputStream()
            BtrDownloader().download(spec(),DataSource.Factory { Scripted(opened,failHalf=true) },routes,emptyList(),BtrDownloader.Request()) { out.write(it) }
            assertArrayEquals(bytes(0,size.toInt()),out.toByteArray())
            assertTrue("retry must use received prefix",opened.any { it.position % 131072 == 65536L && it.length == 65536L })
            assertTrue(routes.failures.any { it.second == 65536L })
        }
    }
    @Test fun healthyRequestWithPlaybackTimeToSpareDoesNotStartDuplicateAt900ms() = runBlocking {
        withTimeout(8000) {
            val opened=CopyOnWriteArrayList<DataSpec>(); val out=ByteArrayOutputStream(); val downloader=BtrDownloader()
            downloader.policy.record(131072,1000.0)
            downloader.download(spec(),DataSource.Factory { Scripted(opened,delayMs=300) },Routes(listOf(first,second)),emptyList(),
                BtrDownloader.Request(deadline={ System.nanoTime()/1e6+60000 })) { out.write(it) }
            assertArrayEquals(bytes(0,size.toInt()),out.toByteArray())
            assertEquals("four primaries, no duplicate copies",4,opened.size)
        }
    }
    @Test fun startupEmitsSmallProbeBeforeSchedulingRemainingMedia() = runBlocking {
        withTimeout(8000) {
            val opened=CopyOnWriteArrayList<DataSpec>(); val out=ByteArrayOutputStream()
            BtrDownloader().download(spec(),DataSource.Factory { Scripted(opened) },Routes(listOf(first)),emptyList(),BtrDownloader.Request(startup=true)) {
                if(out.size()==0) { assertEquals(65536,it.size); assertEquals(1,opened.size) }
                out.write(it)
            }
            assertArrayEquals(bytes(0,size.toInt()),out.toByteArray())
        }
    }
    @Test fun losingProbeIsCancelledAndNotReportedAsCdnFailure() = runBlocking {
        withTimeout(8000) {
            val opened=CopyOnWriteArrayList<DataSpec>(); val routes=Routes(listOf(first,second)); val latch=CountDownLatch(1); val closed=AtomicInteger()
            val out=ByteArrayOutputStream()
            BtrDownloader().download(spec().buildUpon().setLength(65536).build(), DataSource.Factory { Scripted(opened,blockFirst=latch,closed=closed) },
                routes,emptyList(),BtrDownloader.Request(startup=true)) { out.write(it) }
            assertArrayEquals(bytes(0,65536),out.toByteArray())
            assertEquals(0,routes.failures.size)
            assertEquals(2,closed.get())
        }
    }
    @Test fun mismatchedContentRangeNeverReachesPlayer() = runBlocking {
        withTimeout(8000) {
            val opened=CopyOnWriteArrayList<DataSpec>(); var emitted=0
            try {
                BtrDownloader().download(spec(),DataSource.Factory { Scripted(opened,invalid=true) },Routes(listOf(first)),emptyList(),BtrDownloader.Request()) { emitted+=it.size }
                fail("invalid Content-Range must fail")
            } catch (_: IOException) { assertEquals(0,emitted) }
        }
    }
    @Test fun priorityQueueDoesNotLeakCancelledRequestsAndReevaluatesDeadlines() = runBlocking {
        withTimeout(3000) {
            var now=0.0; val gate=BtrPriorityGate { now }; val order=mutableListOf<Int>()
            val release=gate.acquire(0,0,{Double.POSITIVE_INFINITY},1,false)
            val cancelled=launch(start=CoroutineStart.UNDISPATCHED) { gate.acquire(999,0,{0.0},1,false)() }
            cancelled.cancelAndJoin()
            val ordinary=launch(start=CoroutineStart.UNDISPATCHED) { val done=gate.acquire(50,2,{Double.POSITIVE_INFINITY},1,false); order+=1; done() }
            val due=launch(start=CoroutineStart.UNDISPATCHED) { val done=gate.acquire(45,2,{1200.0},1,false); order+=2; done() }
            val startup=launch(start=CoroutineStart.UNDISPATCHED) { val done=gate.acquire(1,1,{Double.POSITIVE_INFINITY},1,false); order+=3; done() }
            now=1500.0; release(); joinAll(ordinary,due,startup)
            assertEquals(listOf(3,2,1),order)
        }
    }
}
