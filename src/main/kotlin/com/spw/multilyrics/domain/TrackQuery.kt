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
        // 文件名（去扩展名、去轨道序号前缀）清理后作为关键词来源
        val fileNameQuery = fileNameQuery()
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
            // 6. 文件名清理后作为整体关键词（标题标签异常时，文件名常更干净，如 "Don Toliver - Lose My Mind.mp3"）
            if (fileNameQuery.isNotBlank() && fileNameQuery != cleanTitle && fileNameQuery != title.trim()) {
                add(fileNameQuery)
            }
            // 7. 仅原始标题（兜底，部分平台能处理特殊符号）
            if (title.trim().isNotBlank() && title.trim() != cleanTitle) add(title.trim())
            // 8. 标题异常时用艺术家名兜底
            if (cleanTitle.isBlank() && artistText.isNotBlank()) add(artistText)
        }.map(String::trim).filter(String::isNotBlank).distinct()
    }

    /**
     * 从 [path] 提取文件名（去扩展名），移除常见轨道序号前缀（如 "01 - "），
     * 并清理 feat./版本括号尾注，作为搜索关键词。
     */
    private fun fileNameQuery(): String {
        val raw = path.trim()
        if (raw.isEmpty()) return ""
        val slash = raw.lastIndexOfAny(charArrayOf('/', '\\'))
        val name = if (slash >= 0) raw.substring(slash + 1) else raw
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        if (stem.isBlank()) return ""
        // 移除轨道号前缀：开头 1-3 位数字 + 可选分隔符（- . 空格）
        val cleaned = stem.replace(Regex("""^\s*\d{1,3}\s*[-.\)]\s*"""), "")
        return TextNormalizer.cleanSearchTitle(cleaned.ifBlank { stem })
    }

    companion object {
        fun splitArtists(value: String): List<String> = TextNormalizer.splitArtists(value)

        /**
         * 从文件路径解析 "Artist - Title" 结构，返回 (title, artists)。
         *
         * 当音频文件无任何标签时，文件名常是唯一线索，且多遵循 "Artist - Title.ext" 约定。
         * 解析后让 MatchEngine 能用真实的标题/艺术家与远程候选比较，而非面对空标签直接放弃。
         *
         * - 去扩展名、去轨道号前缀（"01 - "）
         * - 按 " - " 分割：第一段为艺术家（可能含 ", " "&" 多人），其余为标题
         * - 标题清理 feat./版本括号尾注；艺术家按 splitArtists 拆分
         * - 无 " - " 时整体作为标题
         */
        fun parseFilename(path: String): Pair<String, List<String>> {
            val raw = path.trim()
            if (raw.isEmpty()) return "" to emptyList()
            val slash = raw.lastIndexOfAny(charArrayOf('/', '\\'))
            val name = if (slash >= 0) raw.substring(slash + 1) else raw
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            if (stem.isBlank()) return "" to emptyList()
            // 移除轨道号前缀：开头 1-3 位数字 + 可选分隔符（- . )）
            val cleaned = stem.replace(Regex("""^\s*\d{1,3}\s*[-.\)]\s*"""), "").ifBlank { stem }
            // 按 " - " 分割：第一段为艺术家，其余为标题
            val idx = cleaned.indexOf(" - ")
            return if (idx > 0) {
                val artistPart = cleaned.substring(0, idx).trim()
                val titlePart = cleaned.substring(idx + 3).trim()
                val title = TextNormalizer.cleanSearchTitle(titlePart)
                val artists = TextNormalizer.splitArtists(artistPart)
                title to artists
            } else {
                TextNormalizer.cleanSearchTitle(cleaned) to emptyList()
            }
        }
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
