@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spw.multilyrics

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * Salt Player for Windows 创意工坊插件入口：多平台在线歌词搜索。
 *
 * 支持 Apple Music / 网易云 / QQ / 酷狗 / 酷我 / Spotify 等主流平台，
 * 按曲目标签（标题/艺术家/专辑）自动匹配并加载逐字/翻译歌词。
 */
class MultiLyricsPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    override fun start() {
        PluginRuntime.install()
        runCatching { WorkshopApi.ui.toast("MultiLyrics 已启动", WorkshopApi.Ui.ToastType.Success) }
    }

    override fun stop() {
        PluginRuntime.close()
        runCatching { WorkshopApi.ui.toast("MultiLyrics 已停止", WorkshopApi.Ui.ToastType.Warning) }
    }

    override fun delete() {
        PluginRuntime.close()
    }

    companion object {
        /** 配置界面按钮回调：清除内存搜索缓存（强制下次重新搜索）。 */
        @JvmStatic
        @JvmName("clearSearchCache")
        fun clearSearchCache() {
            PluginRuntime.clearSearchMemo()
            runCatching { WorkshopApi.ui.toast("已清除本次会话的搜索缓存", WorkshopApi.Ui.ToastType.Success) }
        }
    }
}
