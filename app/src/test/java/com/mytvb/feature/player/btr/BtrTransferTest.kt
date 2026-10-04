package com.mytvb.feature.player.btr

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mytvb.core.common.settings.AppSettingsDataStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BtrTransferTest {
    @Before fun setup() {
        startKoin { modules(module { single { AppSettingsDataStore(RuntimeEnvironment.getApplication()) } }) }
        BtrSettingsStore.saveAutoConcurrency(false)
        BtrSettingsStore.saveConcurrency(4)
    }
    @After fun cleanup() { stopKoin() }

    @Test fun enablingBtrOnExistingDataSourceUsesParallelRangesWithOrderedBytes() {
        BtrSettingsStore.saveEnabled(false)
        val source = BtrParallelDataSourceFactory(DataSource.Factory { FakeSource(true) }).createDataSource()
        val spec = DataSpec.Builder().setUri("https://upos.example/media").setPosition(7).setLength(512 * 1024L).build()
        source.open(spec)
        source.close()
        BtrSettingsStore.saveEnabled(true)
        val before = BtrRuntimeDiagnostics.counters.snapshot()
        assertEquals(spec.length, source.open(spec))
        assertEquals(0, source.read(ByteArray(0), 0, 0))
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
        }
        source.close()
        assertArrayEquals(ByteArray(spec.length.toInt()) { ((it + 7) % 251).toByte() }, output.toByteArray())
        val after = BtrRuntimeDiagnostics.counters.snapshot()
        assertEquals(4, after.completedRanges - before.completedRanges)
        assertEquals(spec.length, after.bytes - before.bytes)
        assertEquals(0, after.connections)
        assertEquals(0, after.activeRanges)
    }

    @Test fun cacheReadsAreNotCountedAsCdnTraffic() {
        val source = FakeSource(false)
        source.addTransferListener(BtrDiagnosticTransferListener())
        val before = BtrRuntimeDiagnostics.counters.snapshot()
        source.open(DataSpec(Uri.parse("https://upos.example/media")))
        source.read(ByteArray(100), 0, 100)
        source.close()
        val after = BtrRuntimeDiagnostics.counters.snapshot()
        assertEquals(before.bytes, after.bytes)
        assertEquals(before.connections, after.connections)
    }

    private class FakeSource(private val network: Boolean) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var spec: DataSpec? = null
        private var offset = 0L
        private var remaining = 0L
        override fun addTransferListener(listener: TransferListener) { listeners.add(listener) }
        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            offset = dataSpec.position
            remaining = dataSpec.length.takeIf { it >= 0 } ?: 1024L
            listeners.forEach { it.onTransferStart(this, dataSpec, network) }
            return remaining
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = minOf(length.toLong(), remaining).toInt()
            repeat(count) { buffer[offset + it] = ((this.offset + it) % 251).toByte() }
            this.offset += count
            remaining -= count
            listeners.forEach { it.onBytesTransferred(this, spec!!, network, count) }
            return count
        }
        override fun getResponseHeaders(): Map<String, List<String>> = spec?.let { mapOf("Content-Range" to listOf("bytes ${it.position}-${it.position + it.length - 1}/99999999")) } ?: emptyMap()
        override fun getUri(): Uri? = spec?.uri
        override fun close() {
            spec?.let { dataSpec -> listeners.forEach { it.onTransferEnd(this, dataSpec, network) } }
            spec = null
        }
    }
}
