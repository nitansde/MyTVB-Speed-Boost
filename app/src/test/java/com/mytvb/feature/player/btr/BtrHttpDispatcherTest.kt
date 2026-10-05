package com.mytvb.feature.player.btr

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BtrHttpDispatcherTest {
    private fun exercise(dispatcher: okhttp3.Dispatcher, expectedStarts: Int): Int {
        val server = MockWebServer()
        val started = CountDownLatch(expectedStarts)
        val releaseHeaders = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                releaseHeaders.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/1").setBody("x")
            }
        }
        val client = OkHttpClient.Builder().dispatcher(dispatcher).build()
        val workers = Executors.newFixedThreadPool(16)
        server.start()
        val spec = DataSpec.Builder().setUri(Uri.parse(server.url("/video.m4s").toString())).setLength(1).build()
        val calls = (0 until 16).map { workers.submit {
            val source = OkHttpDataSource.Factory(client).createDataSource()
            try { source.open(spec) } finally { source.close() }
        } }
        try {
            assertTrue("requests must reach the server before headers are released", started.await(3, TimeUnit.SECONDS))
            // Also let the client enqueue the remaining calls; this is transport queueing, not BTR.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (dispatcher.runningCallsCount() + dispatcher.queuedCallsCount() < 16 && System.nanoTime() < until) Thread.yield()
            val queued = dispatcher.queuedCallsCount()
            releaseHeaders.countDown()
            calls.forEach { it.get(5, TimeUnit.SECONDS) }
            return queued
        } finally {
            releaseHeaders.countDown()
            calls.forEach { it.cancel(true) }
            workers.shutdownNow(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown()
        }
    }

    @Test fun defaultTransportQueuesElevenOfSixteenRequestsToOneNodeBeforeHeaders() {
        assertEquals(11, exercise(okhttp3.Dispatcher(), 5))
    }

    @Test fun btrTransportLetsSixteenRequestsReachTheNodeWithoutHiddenQueue() {
        assertEquals(0, exercise(BtrHttpDispatcher.create(), 16))
    }
}
