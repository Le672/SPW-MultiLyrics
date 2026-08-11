package com.spw.multilyrics.provider

import com.spw.multilyrics.codec.LrcCodec
import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsQuality
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TrackQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Spotify 歌词提供方，基于 lrclib.net（开源同步歌词聚合库，含大量 Spotify 元数据）。
 *
 * Spotify 官方未公开歌词 API，因此这里通过 lrclib 按曲目标签检索，
 * 返回标准 LRC（优先逐字 syncedLyrics，退而求其次 plainLyrics）。
 */
class SpotifyProvider(private val http: ProviderHttp) : LyricsProvider {
    override val source = LyricsSource.SPOTIFY

    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> = runCatching {
        val artist = query.artists.firstOrNull().orEmpty()
        val url = "$SEARCH_URL?track_name=${ProviderHttpClient.encode(query.title)}" +
            "&artist_name=${ProviderHttpClient.encode(artist)}"
        val root = providerJson.parseToJsonElement(http.get(url, HEADERS)) as? JsonArray ?: return@runCatching emptyList()
        root.mapNotNull { element ->
            val item = element.asObject() ?: return@mapNotNull null
            val synced = item.string("syncedLyrics")
            val plain = item.string("plainLyrics")
            if (synced.isNullOrBlank() && plain.isNullOrBlank()) return@mapNotNull null
            LyricsCandidate(
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
        }.take(limit)
    }.getOrDefault(emptyList())

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
