package com.spw.multilyrics.provider

import com.spw.multilyrics.codec.KrcCodec
import com.spw.multilyrics.codec.LrcCodec
import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsQuality
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TrackQuery
import kotlinx.serialization.json.JsonObject

/** 酷狗音乐歌词提供方（KRC 逐字，含翻译/罗马音）。 */
class KugouProvider(private val http: ProviderHttp) : LyricsProvider {
    override val source = LyricsSource.KUGOU

    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> = runCatching {
        val url = "$SEARCH_URL?keyword=${ProviderHttpClient.encode(keywords)}&page=1&pagesize=$limit"
        val root = providerJson.parseToJsonElement(http.get(url)) as JsonObject
        root.obj("data")?.array("lists").orEmpty().mapNotNull { element ->
            val song = element.asObject() ?: return@mapNotNull null
            val hash = song.string("FileHash") ?: song.string("EMixSongID") ?: return@mapNotNull null
            LyricsCandidate(
                source = source,
                remoteId = hash,
                title = song.string("SongName").orEmpty(),
                artists = TrackQuery.splitArtists(song.string("SingerName").orEmpty()),
                album = song.string("AlbumName").orEmpty(),
                durationMs = song.long("Duration")?.times(1_000),
                qualityHint = LyricsQuality.WORD_SYNCED,
                context = mapOf("hash" to hash),
            )
        }
    }.getOrDefault(emptyList())

    override fun fetch(candidate: LyricsCandidate): LyricsDocument? = runCatching {
        val hash = candidate.context["hash"] ?: candidate.remoteId
        val duration = candidate.durationMs ?: 0L
        val keyword = "${candidate.artists.joinToString("、")} - ${candidate.title}"
        val searchUrl = "$LYRIC_SEARCH?ver=1&man=yes&client=pc&keyword=${ProviderHttpClient.encode(keyword)}&duration=$duration&hash=$hash"
        val root = providerJson.parseToJsonElement(http.get(searchUrl)) as JsonObject
        val lyric = root.array("candidates")?.firstOrNull()?.asObject() ?: return@runCatching null
        val id = lyric.string("id") ?: return@runCatching null
        val key = lyric.string("accesskey") ?: return@runCatching null
        val downloadUrl = "$LYRIC_DOWNLOAD?ver=1&client=pc&id=$id&accesskey=${ProviderHttpClient.encode(key)}&fmt=krc&charset=utf8"
        val downloaded = providerJson.parseToJsonElement(http.get(downloadUrl)) as JsonObject
        val content = downloaded.string("content") ?: return@runCatching null
        // 优先尝试 KRC（逐字），解密失败则退化为 LRC 文本
        runCatching { KrcCodec.parse(KrcCodec.decryptBase64(content), source) }.getOrElse {
            runCatching { LrcCodec.parse(downloaded.string("content") ?: "", source) }.getOrNull()
        }?.takeIf { it.lines.isNotEmpty() }
    }.getOrNull()

    companion object {
        const val SEARCH_URL = "https://songsearch.kugou.com/song_search_v2"
        const val LYRIC_SEARCH = "https://lyrics.kugou.com/search"
        const val LYRIC_DOWNLOAD = "https://lyrics.kugou.com/download"
    }
}
