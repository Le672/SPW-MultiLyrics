package com.spw.multilyrics.storage

import com.spw.multilyrics.codec.SpwLyricsEncoder
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsSource
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedLyrics(
    val document: LyricsDocument,
    val encoded: String,
    val savedAtEpochMs: Long,
    val modelVersion: Int = CACHE_MODEL_VERSION,
    val encoderVersion: Int = SpwLyricsEncoder.VERSION,
)

const val CACHE_MODEL_VERSION = 1

/** 本地歌词缓存：内存 LRU + 磁盘持久化。 */
class LyricsCache(
    private val root: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val successTtl: Duration = Duration.ofDays(30),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memory = object : LinkedHashMap<String, CachedLyrics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean = size > 64
    }

    init { runCatching { Files.createDirectories(root) } }

    @Synchronized
    fun getLyrics(trackKey: String): CachedLyrics? {
        memory[trackKey]?.takeIf(::validLyrics)?.let { return it }
        val stored = read(cachePath("lyrics", trackKey))
            ?.let { runCatching { json.decodeFromString<CachedLyrics>(it) }.getOrNull() }
            ?.takeIf(::validLyrics)
        stored?.let { memory[trackKey] = it }
        return stored
    }

    @Synchronized
    fun putLyrics(trackKey: String, lyrics: CachedLyrics) {
        memory[trackKey] = lyrics
        runCatching { write(cachePath("lyrics", trackKey), json.encodeToString(lyrics)) }
    }

    private fun validLyrics(value: CachedLyrics): Boolean =
        value.modelVersion == CACHE_MODEL_VERSION &&
            value.encoderVersion == SpwLyricsEncoder.VERSION &&
            !expired(value.savedAtEpochMs, successTtl)

    private fun expired(epochMs: Long, ttl: Duration): Boolean =
        Instant.ofEpochMilli(epochMs).plus(ttl).isBefore(clock.instant())

    private fun cachePath(kind: String, rawKey: String): Path = root.resolve("$kind-${safeKey(rawKey)}.json")

    private fun safeKey(raw: String): String {
        if (raw.matches(Regex("[a-f0-9]{64}"))) return raw
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun read(path: Path): String? =
        if (Files.isRegularFile(path)) runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull()
        else null

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
