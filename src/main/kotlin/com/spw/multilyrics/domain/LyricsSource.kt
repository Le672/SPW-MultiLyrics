package com.spw.multilyrics.domain

import kotlinx.serialization.Serializable

/**
 * 歌词来源。priority 越小越优先匹配。
 */
@Serializable
enum class LyricsSource(val displayName: String, val priority: Int) {
    APPLE_MUSIC("Apple Music (AMLL)", 0),
    QQ("QQ音乐", 1),
    NETEASE("网易云音乐", 2),
    KUGOU("酷狗音乐", 3),
    KUWO("酷我音乐", 4),
    SPOTIFY("Spotify (lrclib)", 5),
    LOCAL("本地歌词", 6),
}
