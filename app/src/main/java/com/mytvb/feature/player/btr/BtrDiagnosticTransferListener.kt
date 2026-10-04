package com.mytvb.feature.player.btr

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Media3 reports isNetwork=false for cache reads; those must not be advertised as CDN traffic. */
internal class BtrDiagnosticTransferListener : TransferListener {
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) BtrRuntimeDiagnostics.counters.connected(source, source.uri?.host ?: dataSpec.uri.host ?: "未知节点")
    }
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) BtrRuntimeDiagnostics.counters.received(bytesTransferred)
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) BtrRuntimeDiagnostics.counters.disconnected(source)
    }
}
