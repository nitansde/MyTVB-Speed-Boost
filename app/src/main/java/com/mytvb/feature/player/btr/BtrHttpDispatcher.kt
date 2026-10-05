package com.mytvb.feature.player.btr

import okhttp3.Dispatcher
import java.lang.ref.WeakReference

/** Media3 uses Call.enqueue even though DataSource.open is blocking. BTR owns the budget. */
internal object BtrHttpDispatcher {
    private var observed = WeakReference<Dispatcher>(null)

    fun create(): Dispatcher = Dispatcher().apply {
        // Avoid a second queue after BTR's priority gate (settings allow up to 128).
        maxRequests = 128
        maxRequestsPerHost = 128
        synchronized(this@BtrHttpDispatcher) { observed = WeakReference(this) }
    }

    @Synchronized fun snapshot(): Pair<Int, Int> = observed.get()?.let {
        it.runningCallsCount() to it.queuedCallsCount()
    } ?: (0 to 0)
}
