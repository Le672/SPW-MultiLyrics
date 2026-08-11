package com.spw.multilyrics.domain

import kotlinx.serialization.Serializable

/** 歌词原始格式，用于调试与缓存。 */
@Serializable
enum class LyricsFormat { TTML, QRC, KRC, YRC, LRC, PLAIN }

/** 歌词质量等级，rank 越高越好。 */
@Serializable
enum class LyricsQuality(val rank: Int) {
    PLAIN(0),
    LINE_SYNCED(1),
    WORD_SYNCED(2),
}

/** 逐字歌词中的一个字/词片段。 */
@Serializable
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** 单行歌词。 */
@Serializable
data class LyricLine(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null,
    val background: Boolean = false,
) {
    fun effectiveEndMs(): Long? = endMs ?: words.maxOfOrNull(LyricWord::endMs)
}

/** 一份完整的歌词文档。 */
@Serializable
data class LyricsDocument(
    val source: LyricsSource,
    val format: LyricsFormat,
    val lines: List<LyricLine>,
    val metadata: Map<String, List<String>> = emptyMap(),
) {
    val quality: LyricsQuality
        get() {
            val nonEmpty = lines.filter { it.text.isNotBlank() }
            if (nonEmpty.isEmpty()) return LyricsQuality.PLAIN
            val timed = nonEmpty.filter { it.startMs != null }
            if (timed.isEmpty()) return LyricsQuality.PLAIN
            val wordTimed = timed.count { line ->
                line.words.isNotEmpty() &&
                    line.words.all { it.startMs <= it.endMs } &&
                    line.words.zipWithNext().all { (l, r) -> l.startMs <= r.startMs && l.endMs <= r.endMs }
            }
            return if (wordTimed.toDouble() / timed.size >= 0.8) LyricsQuality.WORD_SYNCED
            else LyricsQuality.LINE_SYNCED
        }
}
