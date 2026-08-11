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
        val cleanTitle = TextNormalizer.removeVersionNoise(title).trim()
        val artistText = artists.joinToString(" ").trim()
        val normalizedTitle = TextNormalizer.normalize(title).trim()
        // 标题归一化后为空（纯符号/特殊字符）时，用原始标题兜底
        val effectiveTitle = normalizedTitle.ifBlank { title.trim() }
        val effectiveCleanTitle = TextNormalizer.normalize(cleanTitle).trim().ifBlank { cleanTitle }
        return buildList {
            add(listOf(effectiveTitle, artistText, album).filter(String::isNotBlank).joinToString(" "))
            add(listOf(effectiveTitle, artistText).filter(String::isNotBlank).joinToString(" "))
            add(listOf(effectiveCleanTitle, artistText).filter(String::isNotBlank).joinToString(" "))
            add(listOf(effectiveCleanTitle, album).filter(String::isNotBlank).joinToString(" "))
            add(effectiveCleanTitle)
            // 标题归一化为空但原始标题非空：用原始标题兜底（部分平台能处理特殊符号）
            if (effectiveTitle.isBlank() && title.isNotBlank()) add(title.trim())
            // 标题完全异常时，至少用艺术家名兜底搜索
            if (effectiveTitle.isBlank() && artistText.isNotBlank()) add(artistText)
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
