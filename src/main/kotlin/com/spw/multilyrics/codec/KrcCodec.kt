package com.spw.multilyrics.codec

import com.spw.multilyrics.domain.LyricLine
import com.spw.multilyrics.domain.LyricWord
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsFormat
import com.spw.multilyrics.domain.LyricsSource
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 酷狗 KRC 解析器（含 base64+xor+inflate 解密）。 */
object KrcCodec : LyricCodec {
    private val decryptKey = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )
    private val linePattern = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordPattern = Regex("""<(\d+),(\d+),\d+>(.*?)(?=<\d+,\d+,\d+>|$)""")
    private val languagePattern = Regex("""\[language:([^]]+)]""")

    fun decryptBase64(content: String): String {
        val encrypted = Base64.getDecoder().decode(content)
        require(encrypted.size > 4 && encrypted.copyOfRange(0, 4).contentEquals("krc1".toByteArray())) {
            "Invalid KRC header"
        }
        val compressed = encrypted.copyOfRange(4, encrypted.size)
        compressed.indices.forEach { i ->
            compressed[i] = (compressed[i].toInt() xor decryptKey[i % decryptKey.size].toInt()).toByte()
        }
        return InflaterInputStream(ByteArrayInputStream(compressed)).use { stream ->
            stream.readAllBytes().toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    override fun parse(raw: String, source: LyricsSource): LyricsDocument {
        val translations = parseLanguageTrack(raw, 1)
        val romanizations = parseLanguageTrack(raw, 0)
        val lines = raw.lineSequence().mapNotNull { input ->
            val line = linePattern.matchEntire(input.trim()) ?: return@mapNotNull null
            val lineStart = line.groupValues[1].toLong()
            val lineDuration = line.groupValues[2].toLong()
            val words = wordPattern.findAll(line.groupValues[3]).map { m ->
                val offset = m.groupValues[1].toLong()
                val duration = m.groupValues[2].toLong()
                LyricWord(lineStart + offset, lineStart + offset + duration, m.groupValues[3])
            }.toList()
            LyricLine(lineStart, lineStart + lineDuration, words.joinToString("") { it.text }, words)
        }.toList()
        return LyricsDocument(
            source, LyricsFormat.KRC,
            lines.mapIndexed { index, line ->
                line.copy(
                    translation = translations.getOrNull(index)?.takeIf(String::isNotBlank),
                    romanization = romanizations.getOrNull(index)?.takeIf(String::isNotBlank),
                )
            },
        )
    }

    private fun parseLanguageTrack(raw: String, type: Int): List<String> {
        val encoded = languagePattern.find(raw)?.groupValues?.get(1) ?: return emptyList()
        return runCatching {
            val languageJson = Base64.getDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
            val content = Json.parseToJsonElement(languageJson).jsonObject["content"]?.jsonArray.orEmpty()
            val track = content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.intOrNull == type }?.jsonObject
                ?: return@runCatching emptyList()
            track["lyricContent"]?.jsonArray.orEmpty().map { row ->
                row.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString("")
            }
        }.getOrDefault(emptyList())
    }
}
