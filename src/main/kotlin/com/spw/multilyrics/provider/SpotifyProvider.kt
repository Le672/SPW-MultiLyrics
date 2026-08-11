package com.spw.multilyrics.provider

import com.spw.multilyrics.codec.LrcCodec
import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsQuality
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TextNormalizer
import com.spw.multilyrics.domain.TrackQuery
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Spotify 歌词提供方，基于 lrclib.net（开源同步歌词聚合库，含大量 Spotify 元数据）。
 *
 * Spotify 官方未公开歌词 API，因此这里通过 lrclib 按曲目标签检索，
 * 返回标准 LRC（优先逐字 syncedLyrics，退而求其次 plainLyrics）。
 *
 * lrclib 需要 track_name / artist_name 分别传参，无法直接使用 resolver 传入的组合 keywords，
 * 因此从 [TrackQuery] 取结构化数据，并清理标题中的 feat./From Movie 等噪音以提升命中率。
 */
class SpotifyProvider(private val http: ProviderHttp) : LyricsProvider {
    override val source = LyricsSource.SPOTIFY

    // 同一 query 会被 resolver 以多组 keywords 重复调用（本 provider 不依赖 keywords），
    // 用轻量缓存避免对 lrclib 发起重复请求。
    private val responseCache = ConcurrentHashMap<String, List<LyricsCandidate>>()

    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> = runCatching {
        val cleanTitle = TextNormalizer.cleanSearchTitle(query.title).ifBlank { query.title.trim() }
        if (cleanTitle.isBlank()) return@runCatching emptyList()
        val cacheKey = "$cleanTitle|${query.artists.joinToString("|")}|${query.durationMs ?: 0}"
        responseCache[cacheKey]?.let { return@runCatching it }

        val results = performSearch(cleanTitle, query, limit)
        if (responseCache.size > 64) responseCache.clear()
        responseCache[cacheKey] = results
        results
    }.getOrDefault(emptyList())

    private fun performSearch(cleanTitle: String, query: TrackQuery, limit: Int): List<LyricsCandidate> {
        val primaryArtist = query.artists.firstOrNull()?.trim().orEmpty()
        val seen = mutableSetOf<String>()
        val results = mutableListOf<LyricsCandidate>()

        // 1. /api/get 精确匹配（含 duration 时命中率最高，返回单个最匹配项）
        if (query.durationMs != null && query.durationMs > 0) {
            getExact(cleanTitle, primaryArtist, query.album, query.durationMs)?.let {
                if (seen.add(it.remoteId)) results.add(it)
            }
        }

        // 2. /api/search 模糊搜索（主艺术家）
        if (results.size < limit) {
            searchByTrack(cleanTitle, primaryArtist, limit - results.size).forEach { c ->
                if (seen.add(c.remoteId)) results.add(c)
            }
        }

        // 3. 仍无结果时放宽：仅按标题搜索（不带艺术家），覆盖 lrclib 元数据中艺术家缺失的情况
        if (results.isEmpty() && primaryArtist.isNotBlank()) {
            searchByTrack(cleanTitle, "", limit).forEach { c ->
                if (seen.add(c.remoteId)) results.add(c)
            }
        }
        return results
    }

    /** lrclib /api/get：按 track_name + artist_name + duration 精确匹配，返回单个结果。 */
    private fun getExact(
        trackName: String,
        artistName: String,
        album: String,
        durationMs: Long,
    ): LyricsCandidate? = runCatching {
        val params = buildString {
            append("?track_name=").append(ProviderHttpClient.encode(trackName))
            if (artistName.isNotBlank()) append("&artist_name=").append(ProviderHttpClient.encode(artistName))
            if (album.isNotBlank()) append("&album_name=").append(ProviderHttpClient.encode(album))
            append("&duration=").append(durationMs / 1000)
        }
        val item = providerJson.parseToJsonElement(http.get("$GET_URL$params", HEADERS)) as? JsonObject
            ?: return@runCatching null
        parseCandidate(item)
    }.getOrNull()

    /** lrclib /api/search：模糊搜索，返回候选列表。 */
    private fun searchByTrack(trackName: String, artistName: String, limit: Int): List<LyricsCandidate> = runCatching {
        val params = buildString {
            append("?track_name=").append(ProviderHttpClient.encode(trackName))
            if (artistName.isNotBlank()) append("&artist_name=").append(ProviderHttpClient.encode(artistName))
        }
        val root = providerJson.parseToJsonElement(http.get("$SEARCH_URL$params", HEADERS)) as? JsonArray
            ?: return@runCatching emptyList()
        root.mapNotNull { it.asObject()?.let(::parseCandidate) }.take(limit)
    }.getOrDefault(emptyList())

    private fun parseCandidate(item: JsonObject): LyricsCandidate? {
        val synced = item.string("syncedLyrics")
        val plain = item.string("plainLyrics")
        if (synced.isNullOrBlank() && plain.isNullOrBlank()) return null
        return LyricsCandidate(
            source = source,
            remoteId = item.string("id") ?: item.string("trackName").orEmpty(),
            title = item.string("trackName").orEmpty(),
            artists = TrackQuery.splitArtists(item.string("artistName").orEmpty()),
            album = item.string("albumName").orEmpty(),
            durationMs = item.double("duration")?.let { (it * 1000).toLong() },
            qualityHint = if (synced.isNullOrBlank()) LyricsQuality.PLAIN else LyricsQuality.LINE_SYNCED,
            context = mapOf(
                "synced" to (synced ?: ""),
                "plain" to (plain ?: ""),
            ),
        )
    }

    override fun fetch(candidate: LyricsCandidate): LyricsDocument? = runCatching {
        val synced = candidate.context["synced"]?.takeIf(String::isNotBlank)
        val plain = candidate.context["plain"]?.takeIf(String::isNotBlank)
        val raw = synced ?: plain ?: return@runCatching null
        // lrclib 返回的 syncedLyrics 是带 [mm:ss.xxx] 的标准 LRC
        LrcCodec.parse(raw, source).takeIf { it.lines.isNotEmpty() }
    }.getOrNull()

    companion object {
        const val SEARCH_URL = "https://lrclib.net/api/search"
        const val GET_URL = "https://lrclib.net/api/get"
        val HEADERS = mapOf("Lrclib-Client" to "MultiLyrics SPW plugin")
    }
}
