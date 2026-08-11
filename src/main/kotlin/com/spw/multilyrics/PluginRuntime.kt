@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spw.multilyrics

import com.spw.multilyrics.domain.AudioDurationReader
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TrackQuery
import com.spw.multilyrics.provider.AppleMusicProvider
import com.spw.multilyrics.provider.KugouProvider
import com.spw.multilyrics.provider.KuwoProvider
import com.spw.multilyrics.provider.NeteaseProvider
import com.spw.multilyrics.provider.ProviderHttpClient
import com.spw.multilyrics.provider.QqMusicProvider
import com.spw.multilyrics.provider.SpotifyProvider
import com.spw.multilyrics.search.LyricsResolver
import com.spw.multilyrics.storage.LyricsCache
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 插件运行时：装配各组件并处理来自扩展点的歌词加载请求。 */
object PluginRuntime {
    @Volatile private var settings: PluginSettings? = null
    @Volatile private var cache: LyricsCache? = null
    @Volatile private var resolver: LyricsResolver? = null

    // 防止 onBefore/onAfter 对同一曲目重复联网搜索
    @Volatile private var lastSearchedKey: String? = null
    @Volatile private var lastSearchedResult: String? = null

    @Synchronized
    fun install() {
        if (resolver != null) return
        val s = PluginSettings()
        settings = s
        val localData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"))
        val root: Path = localData.resolve("MultiLyrics")
        val cacheDir = root.resolve("cache")
        runCatching { Files.createDirectories(cacheDir) }
        cache = LyricsCache(cacheDir)
        val http = ProviderHttpClient()
        val amllHttp = ProviderHttpClient(requestTimeout = Duration.ofSeconds(6))
        val providers = listOf(
            AppleMusicProvider(root.resolve("amll"), amllHttp),
            QqMusicProvider(http),
            NeteaseProvider(http),
            KugouProvider(http),
            KuwoProvider(http),
            SpotifyProvider(http),
        )
        resolver = LyricsResolver(
            providers = providers,
            enabledSources = { settings()?.enabledSources().orEmpty() },
            includeTranslation = { settings()?.translation ?: true },
            includeRomanization = { settings()?.romanization ?: false },
        )
    }

    @Synchronized
    fun close() {
        resolver = null
        cache = null
        settings = null
        lastSearchedKey = null
        lastSearchedResult = null
    }

    /** 清除内存搜索记忆，使下次播放该曲时重新联网搜索（磁盘缓存仍生效）。 */
    @Synchronized
    fun clearSearchMemo() {
        lastSearchedKey = null
        lastSearchedResult = null
    }

    private fun settings(): PluginSettings = settings ?: PluginSettings()

    /** 优先加载（在 SPW 默认逻辑之前）。仅在启用优先模式时执行完整搜索。 */
    fun beforeLoad(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        val s = settings()
        if (!s.enabled || !s.priorityMode) return null
        return load(toQuery(mediaItem))
    }

    /** 后置加载（SPW 默认逻辑未能加载歌词时调用）。 */
    fun afterLoad(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        val s = settings()
        if (!s.enabled) return null
        if (s.priorityMode) return null // 优先模式已在 beforeLoad 处理且未命中，无需重复
        return load(toQuery(mediaItem))
    }

    private fun load(query: TrackQuery): String? {
        val c = cache ?: return null
        val r = resolver ?: return null
        val s = settings()
        c.getLyrics(query.key)?.let { return it.encoded }

        // 同一曲已搜索过则不重复联网
        if (lastSearchedKey == query.key) return lastSearchedResult

        s.reload()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(s.timeoutSeconds.toLong())
        val resolved = runCatching { r.resolveAutomatic(query, deadline) }.getOrNull()
        val encoded = resolved?.encoded
        lastSearchedKey = query.key
        lastSearchedResult = encoded
        resolved?.let { c.putLyrics(query.key, r.toCache(it)) }
        if (encoded != null) toast("已加载歌词：${resolved.candidate.source.displayName}", s)
        return encoded
    }

    private fun toQuery(mediaItem: PlaybackExtensionPoint.MediaItem): TrackQuery {
        // MediaItem 不提供 duration，从本地音频文件读取（FLAC/M4A/MP3/WAV）。
        val durationMs = mediaItem.path.takeIf(String::isNotBlank)
            ?.let { runCatching { AudioDurationReader.readDurationMs(it) }.getOrNull() }
        var title = mediaItem.title
        var artists = TrackQuery.splitArtists(mediaItem.artist)
        // 标签缺失时从文件名解析 "Artist - Title"，否则 MatchEngine 面对空标签直接放弃
        if (title.isBlank() && artists.isEmpty()) {
            val (fileTitle, fileArtists) = TrackQuery.parseFilename(mediaItem.path)
            if (title.isBlank()) title = fileTitle
            if (artists.isEmpty()) artists = fileArtists
        }
        return TrackQuery(
            title = title,
            artists = artists,
            album = mediaItem.album,
            albumArtists = TrackQuery.splitArtists(mediaItem.albumArtist),
            path = mediaItem.path,
            durationMs = durationMs,
        )
    }

    private fun toast(message: String, s: PluginSettings) {
        if (s.showToast) runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Success) }
    }
}
