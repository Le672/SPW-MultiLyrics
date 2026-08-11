package com.spw.multilyrics.codec

import com.spw.multilyrics.domain.LyricLine
import com.spw.multilyrics.domain.LyricWord
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsFormat
import com.spw.multilyrics.domain.LyricsSource

/** 标准 LRC 解析器，支持增强型内联时间戳 `<mm:ss.xxx>`。 */
object LrcCodec : LyricCodec {
    private val lineTime = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val inlineTime = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val metadata = Regex("""^\[([A-Za-z][\w-]*):(.*)]$""")

    override fun parse(raw: String, source: LyricsSource): LyricsDocument {
        var offset = 0L
        val attributes = linkedMapOf<String, MutableList<String>>()
        val parsed = mutableListOf<LyricLine>()

        raw.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            metadata.matchEntire(line)?.let { m ->
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].trim()
                if (key == "offset") offset = value.toLongOrNull() ?: 0
                attributes.getOrPut(key) { mutableListOf() }.add(value)
                return@forEach
            }
            val timestamps = lineTime.findAll(line).toList()
            if (timestamps.isEmpty()) {
                if (line.isNotBlank() && !line.startsWith('[')) parsed += LyricLine(text = line)
                return@forEach
            }
            val content = line.substring(timestamps.last().range.last + 1)
            timestamps.forEach { ts ->
                val start = ts.toMillis() + offset
                val words = parseInlineWords(content, start, offset)
                val text = inlineTime.replace(content, "")
                parsed += LyricLine(start.coerceAtLeast(0), null, text, words)
            }
        }
        val sorted = parsed.sortedBy { it.startMs ?: Long.MAX_VALUE }
        val completed = sorted.mapIndexed { index, line ->
            if (line.startMs == null) line else line.copy(
                endMs = line.words.maxOfOrNull(LyricWord::endMs)
                    ?: sorted.drop(index + 1).firstNotNullOfOrNull(LyricLine::startMs),
            )
        }
        return LyricsDocument(source, LyricsFormat.LRC, completed, attributes)
    }

    private fun parseInlineWords(content: String, lineStart: Long, offset: Long): List<LyricWord> {
        val markers = inlineTime.findAll(content).toList()
        if (markers.isEmpty()) return emptyList()
        val pieces = mutableListOf<Pair<Long, String>>()
        var cursor = 0
        var currentStart = lineStart
        markers.forEach { marker ->
            if (marker.range.first > cursor) {
                val text = content.substring(cursor, marker.range.first)
                if (text.isNotEmpty()) pieces += currentStart to text
            }
            currentStart = marker.toMillis() + offset
            cursor = marker.range.last + 1
        }
        if (cursor < content.length) pieces += currentStart to content.substring(cursor)
        return pieces.filter { it.second.isNotEmpty() }.mapIndexed { index, (start, text) ->
            val end = pieces.getOrNull(index + 1)?.first ?: start + 300
            LyricWord(start.coerceAtLeast(0), end.coerceAtLeast(start), text)
        }
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
        return minutes * 60_000 + seconds * 1_000 + fraction
    }
}
