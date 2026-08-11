package com.spw.multilyrics.domain

import java.io.RandomAccessFile
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 从本地音频文件头部读取播放时长（毫秒）。
 *
 * SPW 的 [com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem] 不提供 duration，
 * 但时长对 lrclib /api/get 精确匹配和 MatchEngine 时长评分至关重要——没有时长时，
 * 预览片段（80s）与正片（209s）分数完全相同却被判为“不同录音”，触发歧义导致搜不到歌词。
 *
 * 支持 FLAC / M4A(MP4) / MP3 / WAV；OGG 等不支持的格式返回 null。
 * 仅读取文件头部，不解码整首音频。
 */
object AudioDurationReader {

    fun readDurationMs(rawPath: String): Long? = runCatching {
        val path = resolvePath(rawPath) ?: return@runCatching null
        if (!path.toFile().isFile) return@runCatching null
        RandomAccessFile(path.toFile(), "r").use { raf ->
            when (peekMagic(raf, 4)) {
                "fLaC" -> readFlac(raf)
                "RIFF" -> readWav(raf)
                else -> {
                    // M4A/MP4 原子可能不在文件最开头（可能有 ID3 等前置），扫描查找；
                    // MP3 以 ID3v2 或帧同步 0xFFE 开头。
                    if (looksLikeMp4(raf)) readMp4(raf) else readMp3(raf, path)
                }
            }
        }
    }.getOrNull()

    private fun resolvePath(raw: String): Path? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // 处理 file:/// URI
        val p = trimmed.removePrefix("file:").removePrefix("///").removePrefix("//")
        return runCatching { Paths.get(if (p.startsWith("/")) p else trimmed) }.getOrNull()
    }

    private fun peekMagic(raf: RandomAccessFile, len: Int): String {
        val buf = ByteArray(len)
        raf.seek(0)
        val read = raf.read(buf)
        if (read < len) return ""
        return String(buf, Charsets.US_ASCII)
    }

    // ===== FLAC =====
    private fun readFlac(raf: RandomAccessFile): Long? {
        // "fLaC" + metadata blocks。第一个 block 必为 STREAMINFO (type=0)
        raf.seek(4)
        // block header: 1 byte (lastFlag<<7 | type) + 3 bytes length
        val header = raf.readUnsignedByte()
        val type = header and 0x7F
        if (type != 0) return null
        val length = (raf.readUnsignedByte() shl 16) or (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
        if (length < 18) return null
        // STREAMINFO: min/max block(6) + min/max frame(6) + sampleRate(20b)+channels(3b)+bps(5b)+totalSamples(36b)
        raf.skipBytes(10) // 跳到 sampleRate 位置
        val sr1 = raf.readUnsignedByte()
        val sr2 = raf.readUnsignedByte()
        val sr3 = raf.readUnsignedByte()
        val sampleRate = (sr1 shl 12) or (sr2 shl 4) or (sr3 ushr 4)
        if (sampleRate <= 0) return null
        // totalSamples: sr3 低 4 位 + 后 4 字节
        val totalHigh = sr3 and 0x0F
        val totalLow = (raf.readUnsignedByte() shl 24) or (raf.readUnsignedByte() shl 16) or
            (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
        val totalSamples = ((totalHigh.toLong() shl 32) or (totalLow.toLong() and 0xFFFFFFFFL))
        if (totalSamples <= 0) return null
        return totalSamples * 1000 / sampleRate
    }

    // ===== WAV =====
    private fun readWav(raf: RandomAccessFile): Long? {
        // RIFF(size)"WAVE" + "fmt "(size, ...) + "data"(size)
        raf.seek(12) // 跳过 "RIFF" + size + "WAVE"
        var byteRate = 0
        var pos = 12L
        val len = raf.length()
        while (pos + 8 <= len) {
            raf.seek(pos)
            val chunkId = String(ByteArray(4).also { raf.readFully(it) }, Charsets.US_ASCII)
            val chunkSize = Integer.toUnsignedLong(raf.readInt().toInt())
            when (chunkId) {
                "fmt " -> {
                    // audioFormat(2) + channels(2) + sampleRate(4) + byteRate(4) + ...
                    raf.skipBytes(6)
                    byteRate = raf.readInt()
                }
                "data" -> {
                    if (byteRate > 0 && chunkSize > 0) return chunkSize * 1000 / byteRate
                    return null
                }
            }
            pos += 8 + chunkSize + (chunkSize and 1L) // pad to even
        }
        return null
    }

    // ===== M4A / MP4 =====
    private fun looksLikeMp4(raf: RandomAccessFile): Boolean {
        raf.seek(4)
        val type = String(ByteArray(4).also { raf.readFully(it) }, Charsets.US_ASCII)
        return type == "ftyp" || type == "moov" || type == "mdat"
    }

    private fun readMp4(raf: RandomAccessFile): Long? {
        // 遍历顶层 atom 找 moov → mvhd，取 timescale + duration
        val (timescale, duration) = findMvhd(raf, 0, raf.length()) ?: return null
        if (timescale <= 0 || duration <= 0) return null
        return duration * 1000 / timescale
    }

    private fun findMvhd(raf: RandomAccessFile, start: Long, end: Long): Pair<Long, Long>? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { raf.readFully(it) }, Charsets.US_ASCII)
            val headerLen = 8L
            val bodyStart = pos + headerLen
            if (size == 1L) {
                // 64-bit size
                val bigSize = raf.readLong()
                val realSize = if (bigSize > 0) bigSize else (end - pos)
                return scanAtom(raf, type, pos, realSize, bodyStart + 8, end)
            }
            if (size < 8) {
                if (type == "mdat") return null // moov 在 mdat 之后且未找到，放弃
                break
            }
            val result = scanAtom(raf, type, pos, size, bodyStart, end)
            if (result != null) return result
            pos += size
        }
        return null
    }

    private fun scanAtom(
        raf: RandomAccessFile, type: String, atomStart: Long, atomSize: Long, bodyStart: Long, end: Long,
    ): Pair<Long, Long>? {
        if (type == "mvhd") {
            raf.seek(bodyStart)
            val version = raf.readUnsignedByte()
            raf.skipBytes(3) // flags
            raf.skipBytes(if (version == 1) 16 else 8) // creation + modification time
            val timescale = raf.readInt().toLong() and 0xFFFFFFFFL
            val duration = if (version == 1) raf.readLong() else raf.readInt().toLong() and 0xFFFFFFFFL
            return timescale to duration
        }
        if (type == "moov" || type == "trak" || type == "mdia") {
            // 容器 atom，递归查找
            return findMvhd(raf, bodyStart, atomStart + atomSize)
        }
        return null
    }

    // ===== MP3 =====
    private fun readMp3(raf: RandomAccessFile, path: Path): Long? {
        var offset = 0L
        // 跳过 ID3v2
        raf.seek(0)
        val head = ByteArray(10)
        if (raf.read(head) >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            // synchsafe int: 4 bytes (head[6..9])，每字节低 7 位
            val id3Size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            offset = 10L + id3Size
        }
        // 找第一个有效帧头
        val frameHeader = findFirstFrameHeader(raf, offset, raf.length()) ?: return null
        val (frameOffset, headerBytes) = frameHeader
        val parsed = parseMp3Header(headerBytes) ?: return null

        // 检查 Xing/LAME VBR 头（精确帧数）
        val xingFrames = readXingNumFrames(raf, frameOffset, parsed) ?: return estimateBySize(path, parsed)
        if (xingFrames > 0 && parsed.sampleRate > 0) {
            return xingFrames.toLong() * parsed.samplesPerFrame * 1000 / parsed.sampleRate
        }
        return estimateBySize(path, parsed)
    }

    private data class Mp3FrameInfo(
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val bitrate: Int, // bps
    )

    private fun parseMp3Header(b: IntArray): Mp3FrameInfo? {
        if (b.size < 4) return null
        // 同步字 11 位全 1
        if (b[0] != 0xFF || (b[1] and 0xE0) != 0xE0) return null
        val versionBits = (b[1] ushr 3) and 0x03 // 0=2.5, 2=2, 3=1
        val layerBits = (b[1] ushr 1) and 0x03 // 1=Layer3
        if (layerBits != 1) return null // 仅处理 Layer III
        val bitrateIndex = (b[2] ushr 4) and 0x0F
        val srIndex = (b[2] ushr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || srIndex == 3) return null

        // Layer III bitrate 表 (kbps)
        val bitrateTable = when (versionBits) {
            3 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0) // MPEG1
            2, 0 -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0) // MPEG2/2.5
            else -> return null
        }
        val srTable = when (versionBits) {
            3 -> intArrayOf(44100, 48000, 32000, 0)
            2 -> intArrayOf(22050, 24000, 16000, 0)
            0 -> intArrayOf(11025, 12000, 8000, 0)
            else -> return null
        }
        val bitrate = bitrateTable[bitrateIndex] * 1000
        val sampleRate = srTable[srIndex]
        if (bitrate <= 0 || sampleRate <= 0) return null
        // MPEG1 LayerIII = 1152, MPEG2/2.5 LayerIII = 576
        val samplesPerFrame = if (versionBits == 3) 1152 else 576
        return Mp3FrameInfo(sampleRate, samplesPerFrame, bitrate)
    }

    private fun findFirstFrameHeader(raf: RandomAccessFile, start: Long, end: Long): Pair<Long, IntArray>? {
        var pos = start
        val buf = ByteArray(4)
        while (pos + 4 <= end) {
            raf.seek(pos)
            if (raf.read(buf) < 4) return null
            val ints = IntArray(4) { buf[it].toInt() and 0xFF }
            // 帧同步：0xFF + 3 高位为 111
            if (ints[0] == 0xFF && (ints[1] and 0xE0) == 0xE0 && parseMp3Header(ints) != null) {
                return pos to ints
            }
            pos++
        }
        return null
    }

    private fun readXingNumFrames(raf: RandomAccessFile, frameOffset: Long, info: Mp3FrameInfo): Int? {
        // Xing/LAME 头在帧数据部分，偏移取决于 MPEG 版本和声道模式
        // MPEG1: mono=9, stereo=17 ; MPEG2/2.5: mono=9, stereo=17 (实际均为 9/17 区分单双声道)
        // 通用做法：在 frameOffset+4 后的若干字节内搜索 "Xing" 或 "Info"
        raf.seek(frameOffset + 4)
        val scan = ByteArray(64)
        val read = raf.read(scan)
        if (read < 32) return null
        for (i in 0..read - 8) {
            if (scan[i] == 'X'.code.toByte() && scan[i + 1] == 'i'.code.toByte() &&
                scan[i + 2] == 'n'.code.toByte() && scan[i + 3] == 'g'.code.toByte()
            ) {
                // flags (4 bytes) 后是 numFrames (4 bytes, big-endian)，若 flags bit0 set
                val flags = (scan[i + 4].toInt() and 0xFF shl 24) or (scan[i + 5].toInt() and 0xFF shl 16) or
                    (scan[i + 6].toInt() and 0xFF shl 8) or (scan[i + 7].toInt() and 0xFF)
                if (flags and 0x01 == 0) return null // numFrames 不存在
                if (i + 12 > read) return null
                val numFrames = (scan[i + 8].toInt() and 0xFF shl 24) or (scan[i + 9].toInt() and 0xFF shl 16) or
                    (scan[i + 10].toInt() and 0xFF shl 8) or (scan[i + 11].toInt() and 0xFF)
                return numFrames
            }
            // "Info" 也是 VBR 头标记
            if (scan[i] == 'I'.code.toByte() && scan[i + 1] == 'n'.code.toByte() &&
                scan[i + 2] == 'f'.code.toByte() && scan[i + 3] == 'o'.code.toByte()
            ) {
                if (i + 12 > read) return null
                val flags = (scan[i + 4].toInt() and 0xFF shl 24) or (scan[i + 5].toInt() and 0xFF shl 16) or
                    (scan[i + 6].toInt() and 0xFF shl 8) or (scan[i + 7].toInt() and 0xFF)
                if (flags and 0x01 == 0) return null
                val numFrames = (scan[i + 8].toInt() and 0xFF shl 24) or (scan[i + 9].toInt() and 0xFF shl 16) or
                    (scan[i + 10].toInt() and 0xFF shl 8) or (scan[i + 11].toInt() and 0xFF)
                return numFrames
            }
        }
        return null
    }

    private fun estimateBySize(path: Path, info: Mp3FrameInfo): Long? {
        // CBR 估算：duration ≈ (fileSize - tagOverhead) * 8 / bitrate
        // VBR 时为近似值，但足以区分正片与预览片段
        val fileSize = path.toFile().length()
        if (fileSize <= 0 || info.bitrate <= 0) return null
        return fileSize * 8 * 1000 / info.bitrate
    }
}
