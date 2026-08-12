package com.spw.multilyrics.provider

import com.spw.multilyrics.codec.TtmlCodec
import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsQuality
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TextNormalizer
import com.spw.multilyrics.domain.TrackQuery
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Apple Music 歌词提供方，基于 AMLL TTML DB（开源 Apple Music Like Lyrics 逐字歌词库）。
 *
 * 索引文件 (index.jsonl) 会本地缓存 1 天，避免每次播放都重新下载。
 */
class AppleMusicProvider(
    private val root: Path,
    private val http: ProviderHttp,
    private val clock: Clock = Clock.systemUTC(),
) : LyricsProvider {
    override val source: LyricsSource = LyricsSource.APPLE_MUSIC
    private val index = AmllIndexStore(root, http, clock)
    private val ttml = TtmlCodec()

    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> =
        index.search(query, keywords, limit)

    override fun fetch(candidate: LyricsCandidate): LyricsDocument? = runCatching {
        // 候选 context["url"] 是原始 GitHub raw URL，国内可能不可达
        // 改为根据 remoteId 在多个镜像 base 间尝试
        val ttmlPath = "${candidate.remoteId}.ttml"
        val body = RAW_MIRRORS_BASES.firstNotNullOfOrNull { base ->
            runCatching { http.get("$base/$ttmlPath") }.getOrNull()
        } ?: return@runCatching null
        ttml.parse(body, source)
    }.getOrNull()?.takeIf { it.lines.isNotEmpty() }

    companion object {
        const val INDEX_URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/index.jsonl"
        const val RAW_BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics"
        // GitHub raw 国内访问不稳定，jsdelivr 镜像作为 fallback（顺序即优先级）
        val INDEX_MIRRORS = listOf(
            INDEX_URL,
            "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics/index.jsonl",
            "https://fastly.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics/index.jsonl",
        )
        val RAW_MIRRORS_BASES = listOf(
            RAW_BASE,
            "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics",
            "https://fastly.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics",
        )
    }
}

internal class AmllIndexStore(
    private val root: Path,
    private val http: ProviderHttp,
    private val clock: Clock,
) {
    private val indexPath = root.resolve("amll-index.jsonl")
    @Volatile private var records: List<AmllRecord>? = null
    @Volatile private var inverted: Map<String, Set<Int>>? = null

    fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> {
        ensureLoaded()
        val all = records.orEmpty()
        if (all.isEmpty()) return emptyList()
        // 倒排索引命中：CJK 单字也接受（中文歌名常为单字如"花"），避免短 token 被丢弃
        val tokens = TextNormalizer.tokenize(keywords)
        val ids = tokens.flatMap { inverted.orEmpty()[it].orEmpty() }.distinct()
        // 命中池非空时用命中池；命中为空（如歌名全是未索引符号）则线性扫描全量，避免直接放弃
        val pool = if (ids.isNotEmpty()) ids.mapNotNull(all::getOrNull) else all
        return pool.asSequence()
            .sortedByDescending { r ->
                TextNormalizer.similarity(query.title, r.title) * 0.7 +
                    TextNormalizer.similarity(query.artists.joinToString(" "), r.artists.joinToString(" ")) * 0.3
            }
            .take(limit)
            .map(AmllRecord::candidate)
            .toList()
    }

    @Synchronized
    private fun ensureLoaded() {
        if (records != null) return
        Files.createDirectories(root)
        val stale = !Files.isRegularFile(indexPath) ||
            Files.getLastModifiedTime(indexPath).toMillis() + Duration.ofDays(1).toMillis() < clock.millis()
        if (stale) refresh()
        val loaded = if (Files.isRegularFile(indexPath)) {
            Files.readAllLines(indexPath, StandardCharsets.UTF_8).mapNotNull(::parseRecord)
        } else emptyList()
        records = loaded
        // 索引 CJK 单字 token（中文歌名常含单字），拉丁文 token 保留长度 >= 2
        inverted = buildMap {
            loaded.forEachIndexed { index, record ->
                val tokens = TextNormalizer.tokenize(
                    listOf(record.title, record.artists.joinToString(" "), record.album).joinToString(" "),
                )
                tokens.forEach { token -> put(token, getOrDefault(token, emptySet()) + index) }
            }
        }
    }

    private fun refresh() {
        // 依次尝试 GitHub raw 与 jsdelivr 镜像，第一个成功的胜出
        val content = AppleMusicProvider.INDEX_MIRRORS.firstNotNullOfOrNull { url ->
            runCatching { http.get(url) }.getOrNull()?.takeIf(String::isNotBlank)
        } ?: return
        runCatching {
            val temp = Files.createTempFile(root, "amll-index", ".tmp")
            Files.writeString(temp, content, StandardCharsets.UTF_8)
            try {
                Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun parseRecord(line: String): AmllRecord? = runCatching {
        val root = providerJson.parseToJsonElement(line) as JsonObject
        val metadata = (root["metadata"] as? JsonArray).orEmpty().associate { pairElement ->
            val pair = pairElement as JsonArray
            val key = (pair[0] as JsonPrimitive).content
            val values = (pair[1] as JsonArray).map { (it as JsonPrimitive).content }
            key to values
        }
        val id = root.string("id") ?: return@runCatching null
        AmllRecord(
            id = id,
            title = metadata["musicName"]?.firstOrNull().orEmpty(),
            artists = metadata["artists"].orEmpty(),
            album = metadata["album"]?.firstOrNull().orEmpty(),
        )
    }.getOrNull()
}

internal data class AmllRecord(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
) {
    fun candidate() = LyricsCandidate(
        source = LyricsSource.APPLE_MUSIC,
        remoteId = id,
        title = title,
        artists = artists,
        album = album,
        qualityHint = LyricsQuality.WORD_SYNCED,
        // 不再写死 url，fetch 时会在多个镜像 base 间尝试
        context = emptyMap(),
    )
}
