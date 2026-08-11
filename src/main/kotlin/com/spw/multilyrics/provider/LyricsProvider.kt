package com.spw.multilyrics.provider

import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TrackQuery

/** 单个歌词来源的抽象。 */
interface LyricsProvider {
    val source: LyricsSource

    /** 按关键词搜索候选曲目。 */
    fun search(query: TrackQuery, keywords: String, limit: Int = 20): List<LyricsCandidate>

    /** 拉取某个候选项的歌词文档，失败返回 null。 */
    fun fetch(candidate: LyricsCandidate): LyricsDocument?
}
