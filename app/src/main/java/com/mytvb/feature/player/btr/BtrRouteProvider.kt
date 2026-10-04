package com.mytvb.feature.player.btr

import android.net.Uri

/**
 * Per representation CDN view used by the BTR range scheduler.
 *
 * The browser/desktop implementation keeps this state beside the downloader: speed is
 * measured per route (host + path), failures are backed off for a short time, and a node is
 * only removed after repeated empty failures.  Keeping the same boundary here means the
 * Media3 data source can schedule a range on a specific node instead of asking the failover
 * data source to pick the same preferred node for every range.
 */
internal interface BtrRouteProvider {
    fun urls(): List<Uri>
    fun startupCandidates(): List<Uri> = urls().take(8)
    fun rangeCandidates(): List<Uri> = urls()
    fun rescueCandidates(): List<Uri> = urls()
    fun ordered(pieceIndex: Int = 0, exclude: Set<Uri> = emptySet()): List<Uri> =
        urls().filterNot(exclude::contains)
    fun allows(uri: Uri): Boolean = true
    fun speed(uri: Uri): Long = 0L
    fun success(uri: Uri, bytesPerSecond: Long) = Unit
    fun sample(uri: Uri, bytesPerSecond: Long) = Unit
    fun failure(uri: Uri, receivedBytes: Long, statusCode: Int? = null) = Unit
}

/** DataSpec marker consumed by the CDN failover layer for an explicitly scheduled node. */
internal const val BTR_ROUTE_FLAG: Int = 1 shl 29

/** Preserve the actual HTTP status through the CDN wrapper for strict Range validation. */
internal interface BtrHttpResponse {
    val responseCode: Int?
}
