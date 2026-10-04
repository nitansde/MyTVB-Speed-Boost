package com.mytvb.feature.player.btr

import android.net.Uri
import java.util.Locale

/**
 * BTR-inspired CDN candidate expansion for TV playback.
 *
 * Bilibili signs the path and query, while compatible CDN nodes can serve the
 * same signed media address. We keep the signature untouched and only replace
 * the host. The existing MyTVB failover DataSource then measures and rotates
 * these candidates when a node is slow or unavailable.
 */
internal object BtrCdnResolver {
    @Volatile
    var enabled: Boolean = true

    enum class Mode { MAINLAND, OVERSEAS }

    private val mainlandHosts = listOf(
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com",
        "upos-sz-mirror14b.bilivideo.com",
        "upos-sz-estgoss.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com"
    )

    private val overseasHosts = listOf(
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "cn-hk-eq-01-01.bilivideo.com",
        "cn-hk-eq-01-03.bilivideo.com"
    )

    fun expand(primaryUrl: String, backupUrls: List<String>, mode: Mode = Mode.MAINLAND): List<String> {
        val originals = buildList {
            if (primaryUrl.isNotBlank()) add(primaryUrl)
            addAll(backupUrls.filter(String::isNotBlank))
        }.distinct()
        if (originals.isEmpty()) return emptyList()
        if (!enabled) return originals

        val hosts = if (mode == Mode.OVERSEAS) overseasHosts else mainlandHosts
        val donor = originals.firstOrNull { !isAkamai(it) } ?: originals.first()
        val synthetic = hosts.mapNotNull { swapHost(donor, it) }
        return (originals + synthetic).distinct()
    }

    private fun isAkamai(url: String): Boolean =
        Uri.parse(url).host?.lowercase(Locale.US)?.endsWith(".akamaized.net") == true

    private fun swapHost(rawUrl: String, host: String): String? {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val sourceHost = uri.host?.lowercase(Locale.US) ?: return null
        if (!isSupportedMediaHost(sourceHost)) return null
        return uri.buildUpon().scheme("https").authority(host).build().toString()
    }

    private fun isSupportedMediaHost(host: String): Boolean =
        host.endsWith(".bilivideo.com") ||
            host.endsWith(".bilivideo.cn") ||
            host.endsWith(".bilivideo.net") ||
            host.endsWith(".akamaized.net") ||
            host.endsWith(".szbdyd.com") ||
            host.endsWith(".hdslb.com")
}
