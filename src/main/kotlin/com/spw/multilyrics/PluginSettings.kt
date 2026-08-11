@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spw.multilyrics

import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.spw.multilyrics.domain.LyricsSource

/** 从 SPW 配置界面读取插件设置。 */
class PluginSettings {
    private val helper: ConfigHelper? = runCatching {
        WorkshopApi.manager.createConfigManager().getConfig(CONFIG_FILE)
    }.getOrNull()

    fun reload() { runCatching { helper?.reload() } }

    val enabled: Boolean get() = get("enabled", true)
    val priorityMode: Boolean get() = get("priority_mode", false)
    val showToast: Boolean get() = get("show_toast", false)
    val translation: Boolean get() = get("translation", true)
    val romanization: Boolean get() = get("romanization", false)
    val timeoutSeconds: Int get() = get("timeout_seconds", 8.0).toInt().coerceIn(3, 20)
    /** 自动搜索失败时是否弹出手动搜索窗口。默认开启。 */
    val manualSearchOnFail: Boolean get() = get("manual_search_on_fail", true)

    fun enabledSources(): Set<LyricsSource> {
        val all = LyricsSource.entries.filter { it != LyricsSource.LOCAL }
        return all.filter { get("source.${it.name.lowercase()}", true) }.toSet()
    }

    private fun <T> get(key: String, default: T): T = runCatching {
        helper?.get(key, default) ?: default
    }.getOrDefault(default)

    companion object {
        const val CONFIG_FILE = "multilyrics.json"
    }
}
