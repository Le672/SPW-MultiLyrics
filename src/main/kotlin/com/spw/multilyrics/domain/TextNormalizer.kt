package com.spw.multilyrics.domain

import java.text.Normalizer
import kotlin.math.max

/**
 * 文本规范化与相似度工具，用于在不同平台的搜索结果中挑选最匹配的曲目。
 *
 * 不引入额外中文转换库，仅做 NFKC / 标点 / 大小写 / 括号清理。
 */
object TextNormalizer {
    private val versionRegex = Regex(
        """(?i)(official\s*(video|audio|mv)|lyrics?\s*video|live|现场版?|remix|remaster(?:ed)?|acoustic|cover|instrumental|inst\.?|off\s*vocal|karaoke|伴奏|纯音乐|翻唱|完整版|radio\s*edit|sped\s*up|slowed)""",
    )
    // 仅删除“版本标注型”括号尾注（如 "(Live)" "(Remix)" "(Official Video)"），保留歌名主体的括号内容
    private val versionBracketRegex = Regex(
        """\s*[\[【(（]\s*(?i:official\s*(?:video|audio|mv)|lyrics?\s*video|live|现场版?|remix|remaster(?:ed)?|acoustic|cover|instrumental|inst\.?|off\s*vocal|karaoke|伴奏|纯音乐|翻唱|完整版|radio\s*edit|sped\s*up|slowed|mv|mv版|from\s+.+)\s*[\]】)）]\s*""",
    )
    private val punctuationRegex = Regex("""[^\p{L}\p{N}]+""")
    private val artistSplitRegex = Regex(
        """(?i)\s*(?:/|、|,|，|;|；|&|＆|\+|×|\||\bfeat(?:\.|\b)|\bft(?:\.|\b)|\bwith\b)\s*""",
    )
    // 标题中的合作者标注（feat./ft./with xxx），搜索时移除以避免噪音
    // \b 防止误匹配单词内部（如 "Defeat" → "De"），仅匹配独立的 feat/ft/with 关键词
    private val featureArtistRegex = Regex(
        """(?i)\s*[\[【(（]?\s*(?:\bfeat\.?|\bft\.?|\bfeaturing|\bwith)\s+.+?[\]】)）]?\s*$""",
    )
    // CJK 统一表意文字范围（含扩展A），用于判断单字 token 是否应被索引
    private val cjkRanges = listOf(
        0x4E00..0x9FFF,   // CJK 统一表意文字
        0x3400..0x4DBF,   // CJK 扩展 A
        0x3040..0x309F,   // 平假名
        0x30A0..0x30FF,   // 片假名
        0xAC00..0xD7AF,   // 韩文音节
    )

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace('’', '\'')
        .replace(featureArtistRegex, "")
        .replace(versionBracketRegex, " ")
        .replace(punctuationRegex, " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun compact(value: String): String = normalize(value).replace(" ", "")

    /**
     * 清理标题用于搜索关键词生成：移除合作者标注（feat./ft./with xxx）
     * 和版本/来源括号尾注（[From xxx Movie] (Live) 等），保留歌名主体。
     * 不做 NFKC/小写/标点清理，保留原始拼写以匹配各平台搜索引擎。
     */
    fun cleanSearchTitle(value: String): String = value
        .replace(featureArtistRegex, "")
        .replace(versionBracketRegex, " ")
        .trim()
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * 将文本切分为可用于倒排索引的 token。
     * - CJK 单字也作为独立 token（中文歌名常为单字如"花"）
     * - 拉丁文 token 保留长度 >= 2，避免 a/the 等噪音
     */
    fun tokenize(value: String): Set<String> {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return emptySet()
        return buildSet {
            normalized.split(' ').filter(String::isNotBlank).forEach { word ->
                if (isCjkWord(word)) {
                    // CJK：每个字符作为独立 token
                    word.forEach { ch -> add(ch.toString()) }
                } else if (word.length >= 2) {
                    add(word)
                }
            }
        }
    }

    private fun isCjkWord(word: String): Boolean =
        word.any { ch -> cjkRanges.any { range -> ch.code in range } }

    fun removeVersionNoise(value: String): String =
        versionRegex.replace(value, " ").replace(Regex("\\s+"), " ").trim()

    fun versionTokens(value: String): Set<String> = versionRegex
        .findAll(Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase())
        .map { it.value.replace(Regex("\\s+"), " ") }
        .toSet()

    fun splitArtists(value: String): List<String> = value.split(artistSplitRegex)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(::compact)

    fun similarity(left: String?, right: String?): Double {
        if (left.isNullOrBlank() && right.isNullOrBlank()) return 1.0
        if (left.isNullOrBlank() || right.isNullOrBlank()) return 0.0
        val a = normalize(left)
        val b = normalize(right)
        if (a == b) return 1.0
        val cA = a.replace(" ", "")
        val cB = b.replace(" ", "")
        if (cA == cB) return 1.0
        val edit = 1.0 - levenshtein(cA, cB).toDouble() / max(cA.length, cB.length)
        val dice = dice(a.split(' ').filter(String::isNotBlank), b.split(' ').filter(String::isNotBlank))
        val contains = if (cA.contains(cB) || cB.contains(cA)) {
            val short = minOf(cA.length, cB.length).toDouble()
            0.78 + 0.22 * short / max(cA.length, cB.length)
        } else 0.0
        return maxOf(contains, edit * 0.75 + dice * 0.25).coerceIn(0.0, 1.0)
    }

    private fun dice(left: List<String>, right: List<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val inter = left.toSet().intersect(right.toSet()).size
        return 2.0 * inter / (left.toSet().size + right.toSet().size)
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var prev = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val curr = IntArray(right.length + 1).also { it[0] = i + 1 }
            for (j in right.indices) {
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + if (left[i] == right[j]) 0 else 1)
            }
            prev = curr
        }
        return prev[right.length]
    }
}
