package com.spw.multilyrics.domain

import kotlin.math.abs

/** 对一个候选项的打分。 */
data class CandidateScore(
    val candidate: LyricsCandidate,
    val score: Double,
    val titleScore: Double,
    val artistScore: Double?,
    val albumScore: Double?,
    val durationScore: Double?,
    val versionConflict: Boolean,
) {
    /** 是否达到自动采用阈值。 */
    val passesAutomaticGate: Boolean
        get() {
            if (versionConflict) return false
            if (durationScore != null && durationScore < MatchEngine.MIN_DURATION) return false
            val titleAndArtist = titleScore >= MatchEngine.MIN_TITLE &&
                artistScore != null && artistScore >= MatchEngine.MIN_ARTIST &&
                score >= MatchEngine.MIN_TOTAL
            val titleAndAlbum = titleScore >= MatchEngine.STRONG_TITLE &&
                albumScore != null && albumScore >= MatchEngine.MIN_ALBUM &&
                score >= MatchEngine.MIN_TITLE_ALBUM_TOTAL
            val corroborated = titleScore >= MatchEngine.RELAXED_TITLE &&
                artistScore != null && artistScore >= MatchEngine.STRONG_ARTIST &&
                albumScore != null && albumScore >= MatchEngine.STRONG_ALBUM &&
                score >= MatchEngine.MIN_TOTAL
            return titleAndArtist || titleAndAlbum || corroborated
        }
}

data class MatchDecision(
    val winner: CandidateScore?,
    val ranked: List<CandidateScore>,
    val ambiguous: Boolean,
)

/** 候选项打分与自动抉择引擎。 */
object MatchEngine {
    const val RELAXED_TITLE = 0.78
    const val MIN_TITLE = 0.84
    const val STRONG_TITLE = 0.90
    const val MIN_ARTIST = 0.72
    const val STRONG_ARTIST = 0.88
    const val MIN_ALBUM = 0.78
    const val STRONG_ALBUM = 0.82
    const val MIN_TOTAL = 0.80
    const val MIN_TITLE_ALBUM_TOTAL = 0.82
    const val MIN_DURATION = 0.60
    const val MIN_GAP = 0.04

    fun score(query: TrackQuery, candidate: LyricsCandidate): CandidateScore {
        val title = TextNormalizer.similarity(query.title, candidate.title)
        val artist = artistSimilarity(query, candidate)
        val album = if (query.album.isBlank() || candidate.album.isBlank()) null
        else TextNormalizer.similarity(query.album, candidate.album)
        val duration = durationSimilarity(query.durationMs, candidate.durationMs)

        val components = buildList {
            add(title to 0.55)
            artist?.let { add(it to 0.30) }
            album?.let { add(it to 0.10) }
            duration?.let { add(it to 0.20) }
        }
        val totalWeight = components.sumOf { it.second }
        val raw = components.sumOf { (v, w) -> v * w } / totalWeight

        val localVersions = TextNormalizer.versionTokens(query.title)
        val remoteVersions = TextNormalizer.versionTokens(candidate.title)
        val conflict = localVersions != remoteVersions &&
            (localVersions.isNotEmpty() || remoteVersions.isNotEmpty())

        return CandidateScore(candidate, if (conflict) raw - 0.25 else raw, title, artist, album, duration, conflict)
    }

    fun decide(query: TrackQuery, candidates: List<LyricsCandidate>): MatchDecision {
        // 标题和艺术家都缺失时才放弃；标题异常但艺术家存在时仍尝试匹配
        if (query.artists.isEmpty() && query.album.isBlank() &&
            TextNormalizer.normalize(query.title).isBlank()) {
            return MatchDecision(null, emptyList(), false)
        }
        val ranked = candidates.map { score(query, it) }
            .sortedWith(
                compareByDescending<CandidateScore> { it.score }
                    .thenByDescending { it.candidate.qualityHint?.rank ?: -1 }
                    .thenBy { it.candidate.remoteId },
            )
        val best = ranked.firstOrNull() ?: return MatchDecision(null, ranked, false)
        if (!best.passesAutomaticGate) return MatchDecision(null, ranked, false)
        val second = ranked.drop(1).firstOrNull { it.passesAutomaticGate && !sameMetadata(best.candidate, it.candidate) }
        val ambiguous = second != null && best.score - second.score < MIN_GAP
        return MatchDecision(if (ambiguous) null else best, ranked, ambiguous)
    }

    private fun artistSimilarity(query: TrackQuery, candidate: LyricsCandidate): Double? {
        val local = (query.artists + query.albumArtists).distinctBy(TextNormalizer::compact)
        if (local.isEmpty() || candidate.artists.isEmpty()) return null
        val localCoverage = local.map { l -> candidate.artists.maxOf { r -> TextNormalizer.similarity(l, r) } }.average()
        val remoteCoverage = candidate.artists.map { r -> local.maxOf { l -> TextNormalizer.similarity(l, r) } }.average()
        val strongest = local.maxOf { l -> candidate.artists.maxOf { r -> TextNormalizer.similarity(l, r) } }
        val balanced = minOf(localCoverage, remoteCoverage) * 0.35 + maxOf(localCoverage, remoteCoverage) * 0.65
        return maxOf(strongest * 0.85, balanced).coerceIn(0.0, 1.0)
    }

    /**
     * 判定两个候选项是否为“同一录音”：标题 + 艺术家一致即视为同一首作品。
     *
     * 同一录音常被收录进多个合辑/单曲/精选集（如 "F1 The Album" / "Discoteka 2025" /
     * "Lose My Mind - Single"），album 字段不同不代表是不同歌曲。若把这类候选项
     * 当作“并列歧义”会令 [decide] 拒绝全部，反而搜不到歌词。
     * 时长显著不同（>8s）才视为不同版本/录音。
     */
    private fun sameMetadata(left: LyricsCandidate, right: LyricsCandidate): Boolean {
        if (TextNormalizer.compact(left.title) != TextNormalizer.compact(right.title)) return false
        if (left.artists.map(TextNormalizer::compact).toSet() != right.artists.map(TextNormalizer::compact).toSet()) return false
        val ld = left.durationMs ?: 0L
        val rd = right.durationMs ?: 0L
        if (ld > 0 && rd > 0 && abs(ld - rd) > 8_000) return false
        return true
    }

    private fun durationSimilarity(local: Long?, remote: Long?): Double? {
        if (local == null || remote == null || local <= 0 || remote <= 0) return null
        return when (abs(local - remote)) {
            in 0..1_500 -> 1.0
            in 1_501..3_000 -> 0.85
            in 3_001..5_000 -> 0.60
            in 5_001..8_000 -> 0.30
            else -> 0.0
        }
    }
}
