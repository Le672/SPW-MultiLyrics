@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spw.multilyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/** 播放扩展点：在歌词加载阶段接入多平台搜索。 */
@Extension
class MultiLyricsExtension : PlaybackExtensionPoint {

    /** 优先加载（启用“优先模式”时接管歌词加载）。 */
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        PluginRuntime.beforeLoad(mediaItem)

    /** 后置加载：SPW 默认逻辑无法加载歌词时的兜底。 */
    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        PluginRuntime.afterLoad(mediaItem)
}
