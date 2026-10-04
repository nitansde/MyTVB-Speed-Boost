package com.mytvb.feature.player.btr

import com.mytvb.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform

/** Shared BTR configuration used by the system settings page and the player panel. */
enum class BtrCdnMode(val value: String) {
    MAINLAND("mainland"),
    OVERSEAS("overseas"),
    CUSTOM("custom");

    companion object {
        fun from(value: String?): BtrCdnMode = entries.firstOrNull { it.value == value } ?: MAINLAND
    }
}

enum class BtrTakeoverMode(val value: String) {
    FULL("full"),
    COMPAT("compat");

    companion object {
        fun from(value: String?): BtrTakeoverMode = entries.firstOrNull { it.value == value } ?: FULL
    }
}

data class BtrSettings(
    val enabled: Boolean = true,
    val mode: BtrCdnMode = BtrCdnMode.MAINLAND,
    val customHosts: List<String> = emptyList(),
    val concurrency: Int = 8,
    val autoConcurrency: Boolean = true,
    val takeover: BtrTakeoverMode = BtrTakeoverMode.FULL,
    val liveEnabled: Boolean = false,
    val errorNotices: Boolean = true,
    val debugNotices: Boolean = false,
    val floatingButton: Boolean = false
)

object BtrSettingsStore {
    const val KEY_ENABLED = "btr_enabled"
    const val KEY_MODE = "btr_mode"
    const val KEY_CUSTOM_HOSTS = "btr_custom_hosts"
    const val KEY_CONCURRENCY = "btr_concurrency"
    const val KEY_AUTO_CONCURRENCY = "btr_auto_concurrency"
    const val KEY_TAKEOVER = "btr_takeover"
    const val KEY_LIVE_ENABLED = "btr_live_enabled"
    const val KEY_ERROR_NOTICES = "btr_error_notices"
    const val KEY_DEBUG_NOTICES = "btr_debug_notices"
    const val KEY_FLOATING_BUTTON = "btr_floating_button"

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    fun load(): BtrSettings {
        fun value(key: String): String? = appSettings.getCachedString(key)
        return BtrSettings(
            enabled = parseToggle(value(KEY_ENABLED), true),
            mode = BtrCdnMode.from(value(KEY_MODE)),
            customHosts = sanitizeHosts(value(KEY_CUSTOM_HOSTS).orEmpty().split('|')),
            concurrency = value(KEY_CONCURRENCY)?.toIntOrNull()?.coerceIn(4, 128) ?: 8,
            autoConcurrency = parseToggle(value(KEY_AUTO_CONCURRENCY), true),
            takeover = BtrTakeoverMode.from(value(KEY_TAKEOVER)),
            liveEnabled = parseToggle(value(KEY_LIVE_ENABLED), false),
            errorNotices = parseToggle(value(KEY_ERROR_NOTICES), true),
            debugNotices = parseToggle(value(KEY_DEBUG_NOTICES), false),
            floatingButton = parseToggle(value(KEY_FLOATING_BUTTON), false)
        )
    }

    fun saveEnabled(enabled: Boolean) = save(KEY_ENABLED, enabled.toStoredToggle())
    fun saveMode(mode: BtrCdnMode) = save(KEY_MODE, mode.value)
    fun saveCustomHosts(hosts: List<String>) = save(KEY_CUSTOM_HOSTS, sanitizeHosts(hosts).joinToString("|"))
    fun saveConcurrency(value: Int) = save(KEY_CONCURRENCY, value.coerceIn(4, 128).toString())
    fun saveAutoConcurrency(enabled: Boolean) = save(KEY_AUTO_CONCURRENCY, enabled.toStoredToggle())
    fun saveTakeover(mode: BtrTakeoverMode) = save(KEY_TAKEOVER, mode.value)
    fun saveLiveEnabled(enabled: Boolean) = save(KEY_LIVE_ENABLED, enabled.toStoredToggle())
    fun saveErrorNotices(enabled: Boolean) = save(KEY_ERROR_NOTICES, enabled.toStoredToggle())
    fun saveDebugNotices(enabled: Boolean) = save(KEY_DEBUG_NOTICES, enabled.toStoredToggle())
    fun saveFloatingButton(enabled: Boolean) = save(KEY_FLOATING_BUTTON, enabled.toStoredToggle())

    private fun save(key: String, value: String) = appSettings.putStringAsync(key, value)
    private fun sanitizeHosts(hosts: List<String>): List<String> = hosts.map { it.trim().lowercase() }
        .filter { it.isNotBlank() && (it.endsWith(".bilivideo.com") || it.endsWith(".bilivideo.cn") || it.endsWith(".bilivideo.net") || it.endsWith(".akamaized.net") || it.endsWith(".szbdyd.com") || it.endsWith(".hdslb.com")) }
        .distinct().take(32)
    private fun Boolean.toStoredToggle() = if (this) "开" else "关"
    private fun parseToggle(value: String?, default: Boolean): Boolean = when (value?.trim()) {
        "开", "ON", "on", "true", "TRUE", "1" -> true
        "关", "OFF", "off", "false", "FALSE", "0" -> false
        else -> default
    }
}
