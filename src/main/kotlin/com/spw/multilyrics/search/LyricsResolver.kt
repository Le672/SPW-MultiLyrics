package com.spw.multilyrics.search

import com.spw.multilyrics.codec.SpwLyricsEncoder
import com.spw.multilyrics.domain.CandidateScore
import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.MatchEngine
import com.spw.multilyrics.domain.TrackQuery
import com.spw.multilyrics.provider.LyricsProvider
import com.spw.multilyrics.storage.CachedLyrics
import java.time.Clock

data class ResolvedLyrics(
    val candidate: LyricsCandidate,
    val document: LyricsDocument,
    val encoded: String,
)

/**
 * 协调多个 [LyricsProvider]：按来源优先级依次搜索、用 [MatchEngine] 抉择、拉取并编码歌词。
 */
class LyricsResolver(
    providers: List<LyricsProvider>,
    private val enabledSources: () -> Set<LyricsSource>,
    private val includeTranslation: () -> Boolean = { true },
    private val includeRomanization: () -> Boolean = { false },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val providers = providers.associateBy(LyricsProvider::source)
    private val orderedSources = LyricsSource.entries
        .filter { it != LyricsSource.LOCAL }
        .sortedBy(LyricsSource::priority)

    fun resolveAutomatic(query: TrackQuery, deadlineNanos: Long = Long.MAX_VALUE): ResolvedLyrics? {
        val enabled = enabledSources()
        for (source in orderedSources) {
            if (source !in enabled) continue
            if (System.nanoTime() >= deadlineNanos) return null
            val provider = providers[source] ?: continue
            val candidates = query.searchQueries()
                .takeWhile { System.nanoTime() < deadlineNanos }
                .flatMap { keywords -> search(provider, query, keywords) }
                .distinctBy { it.remoteId }
            val decision = MatchEngine.decide(query, candidates)
            val winner = decision.winner?.candidate ?: continue
            if (System.nanoTime() >= deadlineNanos) return null
            fetch(provider, winner)?.let { return it }
        }
        return null
    }

    private fun search(provider: LyricsProvider, query: TrackQuery, keywords: String): List<LyricsCandidate> =
        runCatching { provider.search(query, keywords) }.getOrDefault(emptyList())

    /** 供手动搜索使用：用指定关键词在所有已启用来源中搜索，返回带来源标记的候选。 */
    fun searchAll(query: TrackQuery, keywords: String): List<LyricsCandidate> =
        searchAllWithStatus(query, keywords).candidates

    /** 供手动搜索使用：搜索并返回每个源的状态（成功/失败/错误信息），便于 UI 展示。 */
    fun searchAllWithStatus(query: TrackQuery, keywords: String): SearchAllResult {
        val enabled = enabledSources()
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<LyricsCandidate>()
        val statuses = mutableListOf<SourceStatus>()
        for (source in orderedSources) {
            if (source !in enabled) {
                statuses.add(SourceStatus(source, enabled = false, candidates = 0, error = null))
                continue
            }
            val provider = providers[source]
            if (provider == null) {
                statuses.add(SourceStatus(source, enabled = true, candidates = 0, error = "未注册"))
                continue
            }
            val result = runCatching { provider.search(query, keywords) }
            result.onSuccess { list ->
                val filtered = list.filter { seen.add("${source.name}|${it.remoteId}") }
                candidates.addAll(filtered)
                statuses.add(SourceStatus(source, enabled = true, candidates = filtered.size, error = null))
            }.onFailure { e ->
                val root = generateSequence(e) { it.cause }.lastOrNull() ?: e
                val msg = root.message?.take(80)?.ifBlank { root.javaClass.simpleName } ?: root.javaClass.simpleName
                statuses.add(SourceStatus(source, enabled = true, candidates = 0, error = "${root.javaClass.simpleName}: $msg"))
            }
        }
        return SearchAllResult(candidates.toList(), statuses.toList())
    }

    /** 供手动搜索使用：拉取指定候选的歌词，应用翻译/罗马音偏好后返回编码结果。 */
    fun fetchManual(candidate: LyricsCandidate): ResolvedLyrics? {
        val provider = providers[candidate.source] ?: return null
        return fetch(provider, candidate)
    }

    private fun fetch(provider: LyricsProvider, candidate: LyricsCandidate): ResolvedLyrics? {
        val document = runCatching { provider.fetch(candidate) }.getOrNull()
            ?.takeIf { it.lines.isNotEmpty() } ?: return null
        val adjusted = applySecondaryPreferences(document)
        val encoded = SpwLyricsEncoder.encode(adjusted).takeIf(String::isNotBlank) ?: return null
        return ResolvedLyrics(candidate, adjusted, encoded)
    }

    /** 依据设置剔除翻译/罗马音副歌词。 */
    private fun applySecondaryPreferences(document: LyricsDocument): LyricsDocument {
        val wantTranslation = includeTranslation()
        val wantRomanization = includeRomanization()
        if (wantTranslation && wantRomanization) return document
        return document.copy(
            lines = document.lines.map { line ->
                line.copy(
                    translation = line.translation?.takeIf { wantTranslation },
                    romanization = line.romanization?.takeIf { wantRomanization },
                )
            },
        )
    }

    fun toCache(resolved: ResolvedLyrics): CachedLyrics = CachedLyrics(
        document = resolved.document,
        encoded = resolved.encoded,
        savedAtEpochMs = clock.millis(),
    )
}

/** 单个来源的搜索状态。 */
data class SourceStatus(
    val source: LyricsSource,
    val enabled: Boolean,
    val candidates: Int,
    val error: String?,
)

/** 搜索聚合结果：候选列表 + 各源状态。 */
data class SearchAllResult(
    val candidates: List<LyricsCandidate>,
    val statuses: List<SourceStatus>,
)
