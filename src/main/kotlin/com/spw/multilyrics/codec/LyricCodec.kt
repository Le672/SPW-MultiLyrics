package com.spw.multilyrics.codec

import com.spw.multilyrics.domain.LyricLine
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsSource

interface LyricCodec {
    fun parse(raw: String, source: LyricsSource): LyricsDocument
}

/** 合并主歌词与翻译/罗马音副轨道（按时间轴对齐）。 */
object LyricsTrackMerger {
    fun align(
        original: LyricsDocument,
        translations: List<LyricLine> = emptyList(),
        romanizations: List<LyricLine> = emptyList(),
        toleranceMs: Long = 1_200,
    ): LyricsDocument {
        val t = SecondaryLyricsAligner.align(original.lines, translations, toleranceMs)
        val r = SecondaryLyricsAligner.align(original.lines, romanizations, toleranceMs)
        return original.copy(
            lines = original.lines.mapIndexed { i, line ->
                line.copy(
                    translation = t[i]?.text ?: line.translation,
                    romanization = r[i]?.text ?: line.romanization,
                )
            },
        )
    }
}
