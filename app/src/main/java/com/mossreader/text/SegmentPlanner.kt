package com.mossreader.text

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Role { NARRATOR, DIALOGUE, TITLE }

data class Paragraph(val index: Int, val start: Int, val end: Int)

/**
 * 一个 TTS 合成单元。
 * start/end 是相对章节文本的偏移（用于界面高亮），speakText 是实际送给模型的文本，
 * pauseMs 是这一段播完之后额外插入的静音。
 */
data class Segment(
    val index: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val role: Role,
    val speakText: String,
    val pauseMs: Int,
)

class PlannedChapter(val paragraphs: List<Paragraph>, val segments: List<Segment>) {
    /** 二分查找：某个字符偏移落在哪一段。 */
    fun segmentAt(offset: Int): Int {
        if (segments.isEmpty()) return 0
        var lo = 0; var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segments[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}

class PlannerOptions(
    val maxTokens: Int = 60,
    val pauseScale: Float = 1f,
    val splitDialogue: Boolean = false,
)

/** 分词接口：返回长度 n+1 的数组，allowed[i]=true 表示可以在第 i 个字符之前断开。 */
interface WordBreaker {
    fun allowedBreaks(text: String): BooleanArray
}

/** 没有词典时的兜底：CJK 每个字之间都允许断，拉丁词/数字不拆。 */
object SimpleWordBreaker : WordBreaker {
    override fun allowedBreaks(text: String): BooleanArray {
        val n = text.length
        val r = BooleanArray(n + 1)
        for (i in 1 until n) {
            val a = text[i - 1]; val b = text[i]
            r[i] = !(a.isLetterOrDigit() && b.isLetterOrDigit() && a.code < 0x2E80 && b.code < 0x2E80) &&
                !(Character.isHighSurrogate(a) && Character.isLowSurrogate(b))
        }
        return r
    }
}

class SegmentPlanner(
    private val tokenCount: (String) -> Int,
    private val breaker: WordBreaker,
    private val opts: PlannerOptions,
) {
    private companion object {
        const val SENT_END = "。！？!?；;"
        const val CLAUSE = "，,、：:"
        const val CLOSERS = "”’」』）】》〉)]\"'"
        const val OPENERS = "“‘「『（【《〈([\""
        const val DQ_OPEN = "“「『"
        const val DQ_CLOSE = "”」』"

        val CONJ = listOf(
            "但是", "可是", "然而", "不过", "而且", "并且", "或者", "以及", "因为", "所以", "因此", "如果", "假如", "要是",
            "虽然", "尽管", "于是", "然后", "接着", "同时", "此时", "这时", "那时", "忽然", "突然", "随后", "而后", "另外",
            "只是", "何况", "甚至", "既然", "无论", "不管", "除非", "只要", "只有", "一边", "一面", "与其", "宁可",
            "but ", "and ", "because ", "which ", "that ", "when ", "while ", "if ", "so ", "or ", "then ",
        )
        val PREP_STRONG = "把被对向从跟给让使朝往"
        val NO_START = "的了着过吗呢吧啊呀啦嘛哦哇么"
        val TIME_END = "时后前候间"
    }

    // ------------------------------------------------------------------ 入口

    fun plan(text: String, firstLineIsTitle: Boolean): PlannedChapter {
        val paragraphs = ArrayList<Paragraph>()
        val segments = ArrayList<Segment>()
        var lineStart = 0
        var first = true
        val n = text.length
        while (lineStart <= n) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = n
            var s = lineStart
            var e = lineEnd
            while (s < e && isBlank(text[s])) s++
            while (e > s && isBlank(text[e - 1])) e--
            if (e > s) {
                val pIndex = paragraphs.size
                paragraphs.add(Paragraph(pIndex, s, e))
                val line = text.substring(s, e)
                if (!hasReadable(line)) {
                    // 分隔线 / 场景切换标记：不朗读，只在前一段后面加长停顿
                    if (segments.isNotEmpty()) {
                        val last = segments.removeAt(segments.size - 1)
                        segments.add(last.copy(pauseMs = max(last.pauseMs, (1200 * opts.pauseScale).roundToInt())))
                    }
                } else if (first && firstLineIsTitle) {
                    val speak = TtsTextNormalizer.speakText(line, continuing = false)
                    segments.add(Segment(segments.size, pIndex, s, e, Role.TITLE, speak, (800 * opts.pauseScale).roundToInt()))
                } else {
                    planParagraph(text, pIndex, s, e, segments)
                }
                first = false
            }
            if (lineEnd >= n) break
            lineStart = lineEnd + 1
        }
        return PlannedChapter(paragraphs, segments)
    }

    private fun isBlank(c: Char) = c.isWhitespace() || c == '\u3000'

    private fun hasReadable(s: String): Boolean {
        for (c in s) if (c.isLetterOrDigit()) return true
        return false
    }

    // ------------------------------------------------------------------ 段落

    private class Run(val start: Int, val end: Int, val role: Role)
    private class Piece(val start: Int, val end: Int, val continuing: Boolean)

    private fun planParagraph(full: String, pIndex: Int, ps: Int, pe: Int, out: MutableList<Segment>) {
        val p = full.substring(ps, pe)
        val breaks = breaker.allowedBreaks(p)
        val runs = if (opts.splitDialogue) splitRuns(p) else listOf(Run(0, p.length, Role.NARRATOR))
        val produced = ArrayList<Triple<Piece, Role, Int>>()   // piece, role, runIndex
        for ((ri, run) in runs.withIndex()) {
            val sentences = splitSentences(p, run.start, run.end)
            for (piece in chunkSentences(p, sentences, breaks)) produced.add(Triple(piece, run.role, ri))
        }
        for ((k, item) in produced.withIndex()) {
            val (piece, role, runIdx) = item
            val raw = p.substring(piece.start, piece.end)
            val speak = TtsTextNormalizer.speakText(raw, piece.continuing)
            if (speak.isBlank() || !hasReadable(speak)) continue
            val endsParagraph = k == produced.lastIndex
            val roleChangesNext = !endsParagraph && produced[k + 1].third != runIdx
            val pause = pauseFor(raw, endsParagraph, roleChangesNext, piece.continuing)
            out.add(Segment(out.size, pIndex, ps + piece.start, ps + piece.end, role, speak, pause))
        }
    }

    /** 按引号切成 旁白 / 对话 段（未闭合的引号一直到段尾）。 */
    private fun splitRuns(p: String): List<Run> {
        val runs = ArrayList<Run>()
        var depth = 0
        var runStart = 0
        var asciiOpen = false
        var i = 0
        fun flush(end: Int, role: Role) { if (end > runStart) runs.add(Run(runStart, end, role)); runStart = end }
        while (i < p.length) {
            val c = p[i]
            val opens = DQ_OPEN.indexOf(c) >= 0 || (c == '"' && !asciiOpen)
            val closes = DQ_CLOSE.indexOf(c) >= 0 || (c == '"' && asciiOpen)
            if (opens && depth == 0) {
                flush(i, Role.NARRATOR)
                depth = 1
                if (c == '"') asciiOpen = true
            } else if (opens) {
                depth++
                if (c == '"') asciiOpen = true
            } else if (closes && depth > 0) {
                depth--
                if (c == '"') asciiOpen = false
                if (depth == 0) flush(i + 1, Role.DIALOGUE)
            }
            i++
        }
        if (runStart < p.length) runs.add(Run(runStart, p.length, if (depth > 0) Role.DIALOGUE else Role.NARRATOR))
        if (runs.isEmpty()) runs.add(Run(0, p.length, Role.NARRATOR))
        return runs
    }

    // ------------------------------------------------------------------ 分句

    private fun splitSentences(p: String, from: Int, to: Int): List<IntRange> {
        val res = ArrayList<IntRange>()
        var s = from
        var i = from
        while (i < to) {
            val c = p[i]
            var isEnd = false
            if (SENT_END.indexOf(c) >= 0) {
                isEnd = true
            } else if (c == '…' || (c == '.' && i + 2 < to + 0 && p.startsWith("...", i))) {
                // 省略号：只有出现在句尾（后面只剩引号/括号）才算句末
                var j = i
                while (j < to && (p[j] == '…' || p[j] == '.')) j++
                var k = j
                while (k < to && CLOSERS.indexOf(p[k]) >= 0) k++
                if (k >= to) { i = j - 1; isEnd = true }
            } else if (c == '.' && (i + 1 >= to || p[i + 1].isWhitespace()) && i > from && p[i - 1].code < 0x2E80) {
                isEnd = true
            }
            if (isEnd) {
                var j = i + 1
                while (j < to && (SENT_END.indexOf(p[j]) >= 0 || p[j] == '…' || p[j] == '.')) j++
                while (j < to && CLOSERS.indexOf(p[j]) >= 0) j++
                var a = s; var b = j
                while (a < b && isBlank(p[a])) a++
                while (b > a && isBlank(p[b - 1])) b--
                if (b > a) res.add(a until b)
                s = j
                i = j
                while (i < to && isBlank(p[i])) i++
                s = i
                continue
            }
            i++
        }
        if (s < to) {
            var a = s; var b = to
            while (a < b && isBlank(p[a])) a++
            while (b > a && isBlank(p[b - 1])) b--
            if (b > a) res.add(a until b)
        }
        return res
    }

    // ------------------------------------------------------------------ 合并 / 切分（按 token 预算）

    private fun tok(p: String, a: Int, b: Int, continuing: Boolean = false): Int =
        tokenCount(TtsTextNormalizer.speakText(p.substring(a, b), continuing))

    private fun chunkSentences(p: String, sentences: List<IntRange>, breaks: BooleanArray): List<Piece> {
        val pieces = ArrayList<Piece>()
        var curStart = -1
        var curEnd = -1
        var curTokens = 0
        fun flush() {
            if (curStart >= 0) pieces.add(Piece(curStart, curEnd, false))
            curStart = -1; curEnd = -1; curTokens = 0
        }
        for (r in sentences) {
            val a = r.first; val b = r.last + 1
            val t = tok(p, a, b)
            if (t > opts.maxTokens) {
                flush()
                pieces.addAll(splitLong(p, a, b, breaks))
                continue
            }
            if (curStart < 0) {
                curStart = a; curEnd = b; curTokens = t
            } else if (curTokens + t <= opts.maxTokens) {
                curEnd = b; curTokens += t
            } else {
                flush()
                curStart = a; curEnd = b; curTokens = t
            }
        }
        flush()
        return pieces
    }

    /** 太长的句子：先按逗号 / 顿号 / 冒号分，仍然太长再按语义切点分。 */
    private fun splitLong(p: String, a: Int, b: Int, breaks: BooleanArray): List<Piece> {
        val clauses = ArrayList<IntRange>()
        var s = a
        for (i in a until b) {
            if (CLAUSE.indexOf(p[i]) >= 0) {
                var j = i + 1
                while (j < b && CLOSERS.indexOf(p[j]) >= 0) j++
                if (j > s) clauses.add(s until j)
                s = j
            }
        }
        if (s < b) clauses.add(s until b)
        val res = ArrayList<Piece>()
        var curStart = -1
        var curEnd = -1
        var curTokens = 0
        fun flush() {
            if (curStart >= 0) res.add(Piece(curStart, curEnd, false))
            curStart = -1; curEnd = -1; curTokens = 0
        }
        for (r in clauses) {
            var ca = r.first; val cb = r.last + 1
            while (ca < cb && isBlank(p[ca])) ca++
            if (ca >= cb) continue
            val t = tok(p, ca, cb)
            if (t > opts.maxTokens) {
                flush()
                res.addAll(semanticSplit(p, ca, cb, breaks))
                continue
            }
            if (curStart < 0) { curStart = ca; curEnd = cb; curTokens = t }
            else if (curTokens + t <= opts.maxTokens) { curEnd = cb; curTokens += t }
            else { flush(); curStart = ca; curEnd = cb; curTokens = t }
        }
        flush()
        return res
    }

    private fun semanticSplit(p: String, a: Int, b: Int, breaks: BooleanArray): List<Piece> {
        val res = ArrayList<Piece>()
        var start = a
        while (start < b && tok(p, start, b) > opts.maxTokens) {
            // 二分：满足 token 预算的最长前缀
            var lo = start + 1
            var hi = b
            var best = start + 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (tok(p, start, mid, continuing = true) <= opts.maxTokens) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            val cut = chooseCut(p, start, best, breaks)
            res.add(Piece(start, cut, true))
            start = cut
            while (start < b && isBlank(p[start])) start++
        }
        if (start < b) res.add(Piece(start, b, false))
        return res
    }

    /** 在 [from + (limit-from)/2, limit] 内挑一个最合适的断点。 */
    private fun chooseCut(p: String, from: Int, limit: Int, breaks: BooleanArray): Int {
        val minPos = from + max(2, (limit - from) / 2)
        var bestPos = -1
        var bestScore = Int.MIN_VALUE
        var pos = limit
        while (pos >= minPos) {
            if (pos <= p.length && breaks.getOrElse(pos) { false } && pos < p.length) {
                val next = p[pos]
                val prev = p[pos - 1]
                var score = 0
                if (NO_START.indexOf(next) >= 0 || CLOSERS.indexOf(next) >= 0 || CLAUSE.indexOf(next) >= 0 || SENT_END.indexOf(next) >= 0) score -= 100
                if (OPENERS.indexOf(prev) >= 0) score -= 100
                if (prev == '的') score -= 4
                if (CONJ.any { p.startsWith(it, pos) }) score += 8
                if (TIME_END.indexOf(prev) >= 0) score += 3
                if (PREP_STRONG.indexOf(next) >= 0) score += 3
                if (next == '在' || next == '并' || next == '还' || next == '就' || next == '又' || next == '也') score += 1
                if (prev == ' ' || next == ' ') score += 2
                score += ((pos - from) * 3) / max(1, limit - from)   // 越靠近预算上限越好
                if (score > bestScore) { bestScore = score; bestPos = pos }
            }
            pos--
        }
        if (bestPos < 0 || bestScore < -50) {
            var c = limit
            if (c < p.length && Character.isLowSurrogate(p[c])) c--
            return max(from + 1, c)
        }
        return bestPos
    }

    // ------------------------------------------------------------------ 停顿

    private fun pauseFor(raw: String, endsParagraph: Boolean, roleChangesNext: Boolean, continuing: Boolean): Int {
        var idx = raw.length - 1
        while (idx >= 0 && (CLOSERS.indexOf(raw[idx]) >= 0 || isBlank(raw[idx]))) idx--
        val last = if (idx >= 0) raw[idx] else ' '
        var base = when {
            last == '…' || (idx >= 2 && raw.startsWith("...", idx - 2)) -> 440
            last == '。' || last == '.' -> 280
            last == '！' || last == '!' -> 320
            last == '？' || last == '?' -> 340
            last == '；' || last == ';' -> 240
            last == '：' || last == ':' -> 190
            last == '，' || last == ',' || last == '、' -> 130
            last == '—' || last == '―' -> 260
            else -> if (continuing) 90 else 200
        }
        if (endsParagraph) base = max(base, 560)
        else if (roleChangesNext) base += 40
        val len = raw.trim().length
        val factor = when {
            len <= 4 -> 0.75f
            len >= 40 -> 1.1f
            else -> 1f
        }
        return (base * factor * opts.pauseScale).roundToInt().coerceIn(0, 4000)
    }
}
