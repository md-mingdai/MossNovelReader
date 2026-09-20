package com.mossreader.text

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class Chapter(val title: String, val start: Int, val end: Int)

/** 文件字节 → 文本：自动识别 UTF-8 / UTF-16 / GB18030(GBK) / Big5。 */
object TextDecoder {
    private const val COMMON_SIMPLIFIED =
        "的一是不了人我在有他这为之大来以个中上们到说国和地也子时道出而要于就下得可你年生自会那后能对着事其里所去行过家十用发天如然作方成者多日都三小军二无同么经法当起与好看学进种将还分此心前面又定见只主没公从"
    private const val COMMON_TRADITIONAL =
        "的一是不了人我在有他這為之大來以個中上們到說國和地也子時道出而要於就下得可你年生自會那後能對著事其裡所去行過家十用發天如然作方成者多日都三小軍二無同麼經法當起與好看學進種將還分此心前面又定見只主沒公從"

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strict(bytes, Charsets.UTF_8)?.let { return it }
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (name in listOf("GB18030", "Big5")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val s = String(bytes, cs)
            val sc = score(s)
            if (sc > bestScore) { bestScore = sc; best = s }
        }
        return best ?: String(bytes, Charsets.UTF_8)
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) { null }

    private fun score(s: String): Double {
        var common = 0; var repl = 0; var nonAscii = 0
        val limit = minOf(s.length, 200_000)
        for (i in 0 until limit) {
            val c = s[i]
            if (c == '\uFFFD') repl++
            if (c.code > 127) nonAscii++
            if (COMMON_SIMPLIFIED.indexOf(c) >= 0 || COMMON_TRADITIONAL.indexOf(c) >= 0) common++
        }
        if (nonAscii == 0) return 0.0
        return common.toDouble() / nonAscii - repl.toDouble() / nonAscii * 5
    }
}

/** 换行 / 硬折行整理。 */
object TextCleaner {
    private const val TERMINAL = "。！？!?…”’」』）)\"'.；;"

    fun normalize(raw: String): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "").replace("\u0000", "")
        t = t.replace(Regex("[\\u200b-\\u200d\\u2060]"), "")
        t = unwrapIfHardWrapped(t)
        // 连续空行压缩成一个
        t = t.replace(Regex("[ \\t\\u3000]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n")
        return t.trim('\n')
    }

    private fun endsSentence(line: String): Boolean {
        val s = line.trimEnd(' ', '\u3000', '\t')
        return s.isNotEmpty() && TERMINAL.indexOf(s.last()) >= 0
    }

    /** 有些 TXT 每 30~40 个字就换一行（硬折行），检测到就把同一段的行接起来。 */
    private fun unwrapIfHardWrapped(text: String): String {
        val lines = text.split('\n')
        val sample = lines.filter { it.isNotBlank() }.take(4000)
        if (sample.size < 30) return text
        val lens = sample.map { it.trim().length }.sorted()
        val median = lens[lens.size / 2]
        val notEnding = sample.count { !endsSentence(it) }.toDouble() / sample.size
        val blank = lines.count { it.isBlank() }.toDouble() / lines.size
        val mid = sample.count { it.trim().length in 18..90 }.toDouble() / sample.size
        // 硬折行特征：行较短且整齐、多数行不以句末标点结尾、很少空行
        if (!(median in 18..90 && mid > 0.6 && notEnding > 0.5 && blank < 0.15)) return text

        val sb = StringBuilder(text.length)
        var i = 0
        while (i < lines.size) {
            var cur = lines[i]
            i++
            if (cur.isBlank()) { sb.append('\n'); continue }
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) break
                val indented = next.startsWith("　") || next.startsWith("  ") || next.startsWith("\t")
                if (indented || endsSentence(cur) || ChapterSplitter.looksLikeHeading(next.trim())) break
                cur = cur.trimEnd() + next.trim()
                i++
            }
            sb.append(cur).append('\n')
        }
        return sb.toString()
    }
}

object ChapterSplitter {
    private const val NUM = "[〇零一二三四五六七八九十百千万两0-9０-９]"
    private val HEAD_ZH = Regex("^第\\s*$NUM{1,9}\\s*[章节回卷集部篇幕话節話](?![的了是在中里也又都就把被])\\s*.{0,40}$")
    private val HEAD_SPECIAL = Regex("^(序章|序言|序|楔子|引子|前言|自序|后记|後記|尾声|尾聲|终章|終章|完结感言|完本感言|番外|番外篇|大结局|结局|附录|简介|内容简介)([\\s:：、.．\\-—·　].{0,30})?$")
    private val HEAD_EN = Regex("^(chapter|CHAPTER|Chapter)\\s+([0-9]{1,4}|[IVXLCivxlc]{1,8}|[A-Za-z\\-]{3,12})\\b.{0,60}$")
    private val HEAD_EN2 = Regex("^(prologue|epilogue|PROLOGUE|EPILOGUE|Prologue|Epilogue)\\b.{0,40}$")
    private val HEAD_MD = Regex("^#{1,3}\\s+\\S.{0,60}$")
    private val HEAD_NUM = Regex("^\\s*([0-9]{1,4})\\s*[.、．]\\s*(\\S.{0,36})$")
    private val HEAD_NUM_SPACE = Regex("^\\s*([0-9]{1,4})\\s{1,3}(\\S.{0,36})$")

    fun looksLikeHeading(line: String): Boolean = isHeading(line)

    private fun isHeading(raw: String): Boolean {
        val line = raw.trim().trim('\u3000')
        if (line.isEmpty() || line.length > 60) return false
        val last = line.last()
        if ("。，,；;”」』".indexOf(last) >= 0 || (last == '.' && !HEAD_EN.matches(line))) {
            // "第十章的内容很精彩。" 这种是正文，不是标题
            return false
        }
        if (line.startsWith("“") || line.startsWith("「") || line.startsWith("\"")) return false
        return HEAD_ZH.matches(line) || HEAD_SPECIAL.matches(line) || HEAD_EN.matches(line) ||
            HEAD_EN2.matches(line) || HEAD_MD.matches(line)
    }

    private class Line(val start: Int, val end: Int, val text: String)

    private fun lines(text: String): List<Line> {
        val res = ArrayList<Line>()
        var s = 0
        while (s <= text.length) {
            var e = text.indexOf('\n', s)
            if (e < 0) e = text.length
            res.add(Line(s, e, text.substring(s, e)))
            if (e >= text.length) break
            s = e + 1
        }
        return res
    }

    /** 返回覆盖 [0, text.length) 的章节列表，章节 start 指向标题所在行行首。 */
    fun split(text: String, bookTitle: String = "正文", fallbackChars: Int = 4000): List<Chapter> {
        if (text.isBlank()) return listOf(Chapter(bookTitle, 0, text.length))
        val all = lines(text)
        var heads = all.filter { isHeading(it.text) }

        // 备用：纯数字标题 "1. xxx" / "001 xxx"（要求编号递增且数量足够）
        if (heads.size < 3) {
            for (re in listOf(HEAD_NUM, HEAD_NUM_SPACE)) {
                val cand = all.filter { l -> re.matches(l.text) && !endsPunct(l.text) }
                val nums = cand.mapNotNull { re.matchEntire(it.text)?.groupValues?.get(1)?.toIntOrNull() }
                if (cand.size >= 5 && isMostlyAscending(nums)) { heads = cand; break }
            }
        }

        if (heads.size < 2) return fallbackSplit(text, bookTitle, fallbackChars)

        // 生成原始章节
        class Raw(val title: String, val start: Int, val end: Int, val bodyLen: Int)
        val raws = ArrayList<Raw>()
        for ((i, h) in heads.withIndex()) {
            val end = if (i + 1 < heads.size) heads[i + 1].start else text.length
            val title = cleanTitle(h.text)
            val bodyLen = text.substring(h.start, end).count { !it.isWhitespace() } - title.count { !it.isWhitespace() }
            raws.add(Raw(title, h.start, end, bodyLen))
        }
        val n = raws.size
        // 目录页：连续 3 个以上"标题后面几乎没有正文"的标题 → 整段丢弃
        val drop = BooleanArray(n)
        var i = 0
        while (i < n - 1) {
            if (raws[i].bodyLen < 30) {
                var j = i
                while (j < n - 1 && raws[j].bodyLen < 30) j++
                if (j - i >= 3) for (k in i until j) drop[k] = true
                i = if (j > i) j else i + 1
            } else i++
        }
        val firstKept = (0 until n).firstOrNull { !drop[it] }
        val out = ArrayList<Chapter>()
        val prefaceEnd = raws.first().start
        val prefaceLen = text.substring(0, prefaceEnd).count { !it.isWhitespace() }
        if (prefaceLen >= 80) out.add(Chapter("开篇", 0, prefaceEnd))
        if (firstKept == null) return fallbackSplit(text, bookTitle, fallbackChars)

        // 1~2 个孤立的卷标题并入下一章
        var carryStart = -1
        for (k in 0 until n) {
            if (drop[k]) { carryStart = -1; continue }
            val r = raws[k]
            if (r.bodyLen < 30 && k < n - 1) {
                if (carryStart < 0) carryStart = r.start
                continue
            }
            val start = if (carryStart >= 0) carryStart else r.start
            carryStart = -1
            out.add(Chapter(r.title, start, r.end))
        }
        if (out.isEmpty()) return fallbackSplit(text, bookTitle, fallbackChars)
        return out
    }

    private fun endsPunct(s: String) = s.isNotEmpty() && "。！？!?…”」』，,；;".indexOf(s.trim().last()) >= 0

    private fun isMostlyAscending(nums: List<Int>): Boolean {
        if (nums.size < 5) return false
        var up = 0
        for (i in 1 until nums.size) if (nums[i] > nums[i - 1]) up++
        return up.toDouble() / (nums.size - 1) > 0.8
    }

    private fun cleanTitle(t: String): String {
        var s = t.trim().trim('\u3000').trimStart('#').trim()
        s = s.replace(Regex("[ \\t\\u3000]+"), " ")
        return if (s.length > 40) s.substring(0, 40) else s
    }

    private fun fallbackSplit(text: String, bookTitle: String, target: Int): List<Chapter> {
        if (text.length <= target * 3 / 2) return listOf(Chapter(bookTitle, 0, text.length))
        val res = ArrayList<Chapter>()
        var start = 0
        var n = 1
        while (start < text.length) {
            var end = minOf(text.length, start + target)
            if (end < text.length) {
                val nl = text.indexOf('\n', end)
                end = if (nl in 0..(end + 800)) nl + 1 else end
            }
            if (text.length - end < target / 3) end = text.length
            res.add(Chapter("第${n}部分", start, end))
            start = end
            n++
        }
        return res
    }
}
