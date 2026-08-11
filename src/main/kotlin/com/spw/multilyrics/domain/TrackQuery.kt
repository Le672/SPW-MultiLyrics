package com.spw.multilyrics.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 一次“按标签搜索歌词”的请求，由 SPW 的 MediaItem 转换而来。
 */
data class TrackQuery(
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumArtists: List<String> = emptyList(),
    val path: String = "",
    val durationMs: Long? = null,
) {
    /** 用于缓存与去重的稳定键。 */
    val key: String
        get() {
            val identity = listOf(
                path.trim().lowercase(),
                TextNormalizer.normalize(title),
                artists.joinToString("/") { TextNormalizer.normalize(it) },
                TextNormalizer.normalize(album),
            ).joinToString("|")
            return MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

    /** 生成多组搜索关键词，从精确到宽泛依次尝试。 */
    fun searchQueries(): List<String> {
        // 清理后的标题：移除 feat./ft./with 合作者标注和版本/来源括号尾注
        val cleanTitle = TextNormalizer.cleanSearchTitle(title)
        val primaryArtist = artists.firstOrNull()?.trim().orEmpty()
        val artistText = artists.joinToString(" ").trim()
        return buildList {
            // 1. 原始标题 + 第一艺术家（最精确，保留原始拼写和符号）
            add(listOf(title.trim(), primaryArtist).filter(String::isNotBlank).joinToString(" "))
            // 2. 清理后标题 + 第一艺术家（移除 feat./from 等，搜索引擎友好）
            if (cleanTitle.isNotBlank() && cleanTitle != title.trim()) {
                add(listOf(cleanTitle, primaryArtist).filter(String::isNotBlank).joinToString(" "))
            }
            // 3. 清理后标题 + 所有艺术家
            add(listOf(cleanTitle, artistText).filter(String::isNotBlank).joinToString(" "))
            // 4. 清理后标题 + 专辑
            if (album.isNotBlank()) add(listOf(cleanTitle, album).filter(String::isNotBlank).joinToString(" "))
            // 5. 仅清理后标题
            if (cleanTitle.isNotBlank()) add(cleanTitle)
            // 6. 仅原始标题（兜底，部分平台能处理特殊符号）
            if (title.trim().isNotBlank() && title.trim() != cleanTitle) add(title.trim())
            // 7. 标题异常时用艺术家名兜底
            if (cleanTitle.isBlank() && artistText.isNotBlank()) add(artistText)
        }.map(String::trim).filter(String::isNotBlank).distinct()
    }

    companion object {
        fun splitArtists(value: String): List<String> = TextNormalizer.splitArtists(value)
    }
}

/** 一个候选歌词匹配项。 */
data class LyricsCandidate(
    val source: LyricsSource,
    val remoteId: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val durationMs: Long? = null,
    val qualityHint: LyricsQuality? = null,
    val context: Map<String, String> = emptyMap(),
)
