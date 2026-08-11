package com.spw.multilyrics.codec

import com.spw.multilyrics.domain.LyricLine
import com.spw.multilyrics.domain.LyricWord
import com.spw.multilyrics.domain.LyricsDocument
import com.spw.multilyrics.domain.LyricsFormat
import com.spw.multilyrics.domain.LyricsSource

/** 网易云 YRC 逐字歌词解析器。 */
object YrcCodec : LyricCodec {
    private val linePattern = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordPattern = Regex("""\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)""")

    override fun parse(raw: String, source: LyricsSource): LyricsDocument {
        val lines = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { input ->
            val line = linePattern.matchEntire(input.trim()) ?: return@forEach
            val start = line.groupValues[1].toLong()
            val duration = line.groupValues[2].toLong()
            val body = line.groupValues[3]
            val words = wordPattern.findAll(body).map { m ->
                val ws = m.groupValues[1].toLong()
                val wd = m.groupValues[2].toLong()
                LyricWord(ws, ws + wd, m.groupValues[3])
            }.toList()
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else body
            if (text.isNotBlank()) lines += LyricLine(start, start + duration, text, words)
        }
        return LyricsDocument(source, LyricsFormat.YRC, lines)
    }
}
