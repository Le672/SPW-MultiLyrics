package com.spw.multilyrics.provider

import com.spw.multilyrics.codec.LrcCodec
import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsQuality
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TrackQuery
import kotlinx.serialization.json.JsonObject

/** 酷我音乐歌词提供方（LRC 行级同步）。 */
class KuwoProvider(private val http: ProviderHttp) : LyricsProvider {
    override val source = LyricsSource.KUWO

    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> = runCatching {
        val url = "$SEARCH_URL?all=${ProviderHttpClient.encode(keywords)}&ft=music&itemset=web_2013&client=kt&pn=0&rn=$limit&rformat=json&encoding=utf8"
        // 酷我返回非标准单引号 JSON（{'k':'v'}），需先规范化为双引号 JSON
        val root = providerJson.parseToJsonElement(normalizeJsonQuotes(http.get(url))) as JsonObject
        root.array("abslist").orEmpty().mapNotNull { element ->
            val song = element.asObject() ?: return@mapNotNull null
            val musicRid = song.string("MUSICRID") ?: return@mapNotNull null
            val id = musicRid.removePrefix("MUSIC_").trim()
            if (id.isEmpty()) return@mapNotNull null
            LyricsCandidate(
                source = source,
                remoteId = id,
                title = song.string("SONGNAME")?.let(::unescape).orEmpty(),
                artists = TrackQuery.splitArtists(song.string("ARTIST")?.let(::unescape).orEmpty()),
                album = song.string("ALBUM")?.let(::unescape).orEmpty(),
                durationMs = song.string("DURATION")?.toLongOrNull()?.times(1_000),
                qualityHint = LyricsQuality.LINE_SYNCED,
                context = mapOf("id" to id),
            )
        }
    }.getOrDefault(emptyList())

    override fun fetch(candidate: LyricsCandidate): LyricsDocument? = runCatching {
        val id = candidate.context["id"] ?: candidate.remoteId
        // 使用 openapi 接口（旧接口 m.kuwo.cn/newh5/singles/songinfoandlrc 已失效，返回 status:301）
        val url = "$LYRIC_URL?musicId=$id"
        val root = providerJson.parseToJsonElement(http.get(url, HEADERS)) as JsonObject
        val list = root.obj("data")?.array("lrclist").orEmpty()
        if (list.isEmpty()) return@runCatching null
        val lrc = list.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val time = obj.string("time")?.toDoubleOrNull() ?: return@mapNotNull null
            // 新接口字段名为 lineLyric，旧接口为 line，两者都兼容
            val line = (obj.string("lineLyric") ?: obj.string("line"))
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val totalMs = (time * 1000).toLong()
            "[${formatTime(totalMs)}]${unescape(line)}"
        }.joinToString("\n")
        LrcCodec.parse(lrc, source).takeIf { it.lines.isNotEmpty() }
    }.getOrNull()

    private fun formatTime(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = ms % 60_000 / 1_000
        val millis = ms % 1_000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    /** 处理酷我返回的 HTML 实体（&nbsp; &amp; &lt; &gt; &quot; &apos;）。 */
    private fun unescape(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    companion object {
        const val SEARCH_URL = "https://search.kuwo.cn/r.s"
        const val LYRIC_URL = "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric"
        val HEADERS = mapOf("Referer" to "https://www.kuwo.cn/")
    }
}
