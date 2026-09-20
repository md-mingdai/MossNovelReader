package com.mossreader.tts

import java.io.File

/**
 * 纯 Kotlin 实现的 SentencePiece 编码器（只做 encode → ids）。
 *
 * 支持：Unigram / BPE、byte_fallback、user_defined_symbols、
 * precompiled_charsmap（nmt_nfkc 等）、add_dummy_prefix / remove_extra_whitespaces / escape_whitespaces。
 * 行为对照 google/sentencepiece 的 C++ 实现，用来读取 MOSS-TTS-Nano 的 tokenizer.model。
 */
class SentencePiece private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val types: IntArray,
    private val modelType: Int,
    private val byteFallback: Boolean,
    private val unkId: Int,
    private val normalizer: Normalizer,
) {
    private val pieceToId = HashMap<String, Int>()        // NORMAL / USER_DEFINED / UNUSED
    private val reservedToId = HashMap<String, Int>()     // CONTROL / UNKNOWN / BYTE
    private val byteIds = IntArray(256) { -1 }
    private var maxPieceLen = 1
    private var minScore = Float.MAX_VALUE
    private val userDefined = ArrayList<String>()

    init {
        for (i in pieces.indices) {
            val p = pieces[i]
            when (types[i]) {
                TYPE_NORMAL, TYPE_USER_DEFINED, TYPE_UNUSED -> {
                    if (!pieceToId.containsKey(p)) pieceToId[p] = i
                    if (p.length > maxPieceLen) maxPieceLen = p.length
                    if (types[i] == TYPE_USER_DEFINED) userDefined.add(p)
                    if (types[i] == TYPE_NORMAL && scores[i] < minScore) minScore = scores[i]
                }
                else -> {
                    if (!reservedToId.containsKey(p)) reservedToId[p] = i
                    if (types[i] == TYPE_BYTE && p.length == 6 && p.startsWith("<0x") && p.endsWith(">")) {
                        val v = p.substring(3, 5).toIntOrNull(16)
                        if (v != null) byteIds[v] = i
                    }
                }
            }
        }
        userDefined.sortByDescending { it.length }
        if (minScore == Float.MAX_VALUE) minScore = 0f
    }

    val vocabSize: Int get() = pieces.size

    fun pieceToId(piece: String): Int = reservedToId[piece] ?: pieceToId[piece] ?: unkId

    /** 等价于 sp.encode(text, out_type=int)（不加 bos/eos）。 */
    fun encode(text: String): IntArray {
        val normalized = normalizer.normalize(text)
        if (normalized.isEmpty()) return IntArray(0)
        val raw: List<Pair<String, Int>> = when (modelType) {
            MODEL_BPE -> encodeBpe(normalized)
            else -> encodeUnigram(normalized)
        }
        // 与 sentencepiece 一致：连续的 <unk> 合并成一个
        val merged = ArrayList<Pair<String, Int>>(raw.size)
        for (item in raw) {
            val lastItem = merged.lastOrNull()
            if (item.second == unkId && lastItem != null && lastItem.second == unkId) {
                merged[merged.size - 1] = (lastItem.first + item.first) to unkId
            } else merged.add(item)
        }
        val out = ArrayList<Int>(merged.size + 8)
        for ((surface, id) in merged) {
            if (id == unkId && byteFallback && types.getOrElse(id) { TYPE_UNKNOWN } == TYPE_UNKNOWN) {
                val bytes = surface.toByteArray(Charsets.UTF_8)
                var ok = true
                val ids = IntArray(bytes.size)
                for (k in bytes.indices) {
                    val bid = byteIds[bytes[k].toInt() and 0xFF]
                    if (bid < 0) { ok = false; break }
                    ids[k] = bid
                }
                if (ok) { for (v in ids) out.add(v) } else out.add(unkId)
            } else {
                out.add(id)
            }
        }
        return out.toIntArray()
    }

    // ------------------------------------------------------------------ Unigram

    private class Node(val begin: Int, val length: Int, val id: Int, val score: Float) {
        var prev: Node? = null
        var backtrace = 0f
    }

    private fun encodeUnigram(s: String): List<Pair<String, Int>> {
        val n = s.length
        val beginNodes = Array(n + 1) { ArrayList<Node>(4) }
        val endNodes = Array(n + 1) { ArrayList<Node>(4) }
        val unkScore = minScore - K_UNK_PENALTY
        var pos = 0
        while (pos < n) {
            val cp = s.codePointAt(pos)
            val mblen = Character.charCount(cp)
            var hasSingle = false
            val maxLen = minOf(maxPieceLen, n - pos)
            for (len in 1..maxLen) {
                // 不要把代理对切开
                if (pos + len < n && Character.isLowSurrogate(s[pos + len]) && Character.isHighSurrogate(s[pos + len - 1])) continue
                val id = pieceToId[s.substring(pos, pos + len)] ?: continue
                if (types[id] == TYPE_UNUSED) continue
                val sc = if (types[id] == TYPE_USER_DEFINED) -0.1f else scores[id]
                val node = Node(pos, len, id, sc)
                beginNodes[pos].add(node)
                endNodes[pos + len].add(node)
                if (len == mblen) hasSingle = true
            }
            if (!hasSingle) {
                val node = Node(pos, mblen, unkId, unkScore)
                beginNodes[pos].add(node)
                endNodes[pos + mblen].add(node)
            }
            pos += mblen
        }
        // Viterbi（严格 > 才更新，与 C++ 一致）
        for (p in 0 until n) {
            for (r in beginNodes[p]) {
                var best = 0f
                r.prev = null
                for (l in endNodes[p]) {
                    val sc = l.backtrace + r.score
                    if (r.prev == null || sc > best) { r.prev = l; best = sc }
                }
                r.backtrace = best
            }
            if (p == 0) {
                for (r in beginNodes[0]) { r.prev = null; r.backtrace = r.score }
            }
        }
        // 终点：endNodes[n] 中 backtrace 最大者
        var last: Node? = null
        var bestScore = 0f
        for (nd in endNodes[n]) {
            if (last == null || nd.backtrace > bestScore) { last = nd; bestScore = nd.backtrace }
        }
        val res = ArrayList<Pair<String, Int>>()
        var cur = last
        while (cur != null) {
            res.add(s.substring(cur.begin, cur.begin + cur.length) to cur.id)
            cur = cur.prev
        }
        res.reverse()
        return res
    }

    // ------------------------------------------------------------------ BPE

    private class Sym(var piece: String, var prev: Int, var next: Int, val freeze: Boolean)
    private class Pair2(val left: Int, val right: Int, val score: Float, val size: Int)

    private fun encodeBpe(s: String): List<Pair<String, Int>> {
        val syms = ArrayList<Sym>()
        var pos = 0
        while (pos < s.length) {
            var matched = 0
            for (u in userDefined) {
                if (s.startsWith(u, pos)) { matched = u.length; break }
            }
            val len = if (matched > 0) matched else Character.charCount(s.codePointAt(pos))
            syms.add(Sym(s.substring(pos, pos + len), syms.size - 1, -2, matched > 0))
            pos += len
        }
        for (i in syms.indices) syms[i].next = if (i == syms.size - 1) -1 else i + 1

        val cmp = Comparator<Pair2> { a, b ->
            // PriorityQueue 头部 = 最优：score 大者优先，score 相同 left 小者优先
            when {
                a.score > b.score -> -1
                a.score < b.score -> 1
                else -> a.left.compareTo(b.left)
            }
        }
        val agenda = java.util.PriorityQueue<Pair2>(16, cmp)

        fun maybeAdd(left: Int, right: Int) {
            if (left == -1 || right == -1) return
            val l = syms[left]; val r = syms[right]
            if (l.freeze || r.freeze) return
            val merged = l.piece + r.piece
            val id = pieceToId[merged] ?: return
            if (types[id] == TYPE_UNUSED) return
            agenda.add(Pair2(left, right, scores[id], merged.length))
        }
        for (i in 1 until syms.size) maybeAdd(i - 1, i)

        while (agenda.isNotEmpty()) {
            val top = agenda.poll()!!
            val l = syms[top.left]; val r = syms[top.right]
            if (l.piece.isEmpty() || r.piece.isEmpty() || l.piece.length + r.piece.length != top.size) continue
            l.piece = l.piece + r.piece
            l.next = r.next
            if (r.next >= 0) syms[r.next].prev = top.left
            r.piece = ""
            maybeAdd(l.prev, top.left)
            maybeAdd(top.left, l.next)
        }
        val res = ArrayList<Pair<String, Int>>()
        var idx = 0
        while (idx != -1 && idx < syms.size) {
            val p = syms[idx].piece
            if (p.isNotEmpty()) res.add(p to pieceToId(p))
            idx = syms[idx].next
        }
        return res
    }

    // ------------------------------------------------------------------ Normalizer

    class Normalizer(
        private val trie: IntArray?,
        private val normalizedBlob: ByteArray,
        private val addDummyPrefix: Boolean,
        private val removeExtraWhitespaces: Boolean,
        private val escapeWhitespaces: Boolean,
        private val treatWhitespaceAsSuffix: Boolean,
    ) {
        private fun unitOffset(u: Int): Int = (u ushr 10) shl ((u and (1 shl 9)) ushr 6)
        private fun unitLabel(u: Int): Int = u and ((1 shl 31) or 0xFF)
        private fun unitHasLeaf(u: Int): Boolean = ((u ushr 8) and 1) == 1
        private fun unitValue(u: Int): Int = u and 0x7FFFFFFF

        /** darts-clone commonPrefixSearch，返回最长匹配 (value, length)；没有返回 null。 */
        private fun longestMatch(input: ByteArray, start: Int): Pair<Int, Int>? {
            val t = trie ?: return null
            var nodePos = 0
            var unit = t[nodePos]
            nodePos = nodePos xor unitOffset(unit)
            var bestValue = -1
            var bestLen = 0
            var i = start
            while (i < input.size) {
                val k = input[i].toInt() and 0xFF
                nodePos = nodePos xor k
                if (nodePos < 0 || nodePos >= t.size) break
                unit = t[nodePos]
                if (unitLabel(unit) != k) break
                nodePos = nodePos xor unitOffset(unit)
                if (unitHasLeaf(unit)) {
                    if (nodePos in t.indices) {
                        bestValue = unitValue(t[nodePos])
                        bestLen = i - start + 1
                    }
                }
                i++
            }
            return if (bestLen > 0) bestValue to bestLen else null
        }

        private fun oneCharLen(b: Int): Int = when {
            b < 0x80 -> 1
            b < 0xC0 -> 1
            b < 0xE0 -> 2
            b < 0xF0 -> 3
            else -> 4
        }

        private fun normalizePrefix(input: ByteArray, pos: Int): Pair<String, Int> {
            val m = longestMatch(input, pos)
            if (m != null) {
                var e = m.first
                while (e < normalizedBlob.size && normalizedBlob[e].toInt() != 0) e++
                return String(normalizedBlob, m.first, e - m.first, Charsets.UTF_8) to m.second
            }
            val len = minOf(oneCharLen(input[pos].toInt() and 0xFF), input.size - pos)
            return String(input, pos, len, Charsets.UTF_8) to len
        }

        fun normalize(text: String): String {
            val input = text.toByteArray(Charsets.UTF_8)
            var pos = 0
            if (!treatWhitespaceAsSuffix && removeExtraWhitespaces) {
                while (pos < input.size) {
                    val (s, n) = normalizePrefix(input, pos)
                    if (s != " ") break
                    pos += n
                }
            }
            if (pos >= input.size) return ""
            val out = StringBuilder()
            val ws = if (escapeWhitespaces) "\u2581" else " "
            if (!treatWhitespaceAsSuffix && addDummyPrefix) out.append(ws)
            var isPrevSpace = removeExtraWhitespaces
            while (pos < input.size) {
                val (raw, n) = normalizePrefix(input, pos)
                var sp = raw
                while (isPrevSpace && sp.startsWith(" ")) sp = sp.substring(1)
                if (sp.isNotEmpty()) {
                    for (ch in sp) {
                        if (escapeWhitespaces && ch == ' ') out.append('\u2581') else out.append(ch)
                    }
                    isPrevSpace = sp.endsWith(" ")
                }
                pos += n
                if (!removeExtraWhitespaces) isPrevSpace = false
            }
            if (removeExtraWhitespaces) {
                while (out.endsWith(ws)) out.setLength(out.length - ws.length)
            }
            if (treatWhitespaceAsSuffix && addDummyPrefix) out.append(ws)
            return out.toString()
        }
    }

    // ------------------------------------------------------------------ 解析 protobuf

    private class Pb(val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return r
        }
        fun bytes(): ByteArray { val l = varint().toInt(); val r = buf.copyOfRange(pos, pos + l); pos += l; return r }
        fun fixed32(): Int {
            val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        fun skip(wt: Int) {
            when (wt) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val l = varint().toInt(); pos += l }
                5 -> pos += 4
                else -> error("bad wire type $wt")
            }
        }
    }

    companion object {
        const val TYPE_NORMAL = 1
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4
        const val TYPE_UNUSED = 5
        const val TYPE_BYTE = 6
        const val MODEL_UNIGRAM = 1
        const val MODEL_BPE = 2
        private const val K_UNK_PENALTY = 10.0f

        fun load(file: File): SentencePiece = parse(file.readBytes())

        fun parse(data: ByteArray): SentencePiece {
            val pieces = ArrayList<String>()
            val scores = ArrayList<Float>()
            val types = ArrayList<Int>()
            var modelType = MODEL_UNIGRAM
            var byteFallback = false
            var unkId = 0
            var addDummy = true
            var removeExtra = true
            var escapeWs = true
            var wsSuffix = false
            var charsmap = ByteArray(0)

            val top = Pb(data)
            while (top.more()) {
                val tag = top.varint().toInt()
                val field = tag ushr 3
                val wt = tag and 7
                when {
                    field == 1 && wt == 2 -> {
                        val sub = Pb(top.bytes())
                        var piece = ""; var score = 0f; var type = TYPE_NORMAL
                        while (sub.more()) {
                            val t = sub.varint().toInt()
                            when (t ushr 3) {
                                1 -> piece = String(sub.bytes(), Charsets.UTF_8)
                                2 -> score = java.lang.Float.intBitsToFloat(sub.fixed32())
                                3 -> type = sub.varint().toInt()
                                else -> sub.skip(t and 7)
                            }
                        }
                        pieces.add(piece); scores.add(score); types.add(type)
                    }
                    field == 2 && wt == 2 -> {
                        val sub = Pb(top.bytes())
                        while (sub.more()) {
                            val t = sub.varint().toInt()
                            when (t ushr 3) {
                                3 -> modelType = sub.varint().toInt()
                                35 -> byteFallback = sub.varint() != 0L
                                40 -> unkId = sub.varint().toInt()
                                24 -> wsSuffix = sub.varint() != 0L
                                else -> sub.skip(t and 7)
                            }
                        }
                    }
                    field == 3 && wt == 2 -> {
                        val sub = Pb(top.bytes())
                        while (sub.more()) {
                            val t = sub.varint().toInt()
                            when (t ushr 3) {
                                2 -> charsmap = sub.bytes()
                                3 -> addDummy = sub.varint() != 0L
                                4 -> removeExtra = sub.varint() != 0L
                                5 -> escapeWs = sub.varint() != 0L
                                else -> sub.skip(t and 7)
                            }
                        }
                    }
                    else -> top.skip(wt)
                }
            }
            var trie: IntArray? = null
            var blob = ByteArray(0)
            if (charsmap.size > 4) {
                val trieSize = (charsmap[0].toInt() and 0xFF) or ((charsmap[1].toInt() and 0xFF) shl 8) or
                    ((charsmap[2].toInt() and 0xFF) shl 16) or ((charsmap[3].toInt() and 0xFF) shl 24)
                val units = trieSize / 4
                val arr = IntArray(units)
                for (i in 0 until units) {
                    val o = 4 + i * 4
                    arr[i] = (charsmap[o].toInt() and 0xFF) or ((charsmap[o + 1].toInt() and 0xFF) shl 8) or
                        ((charsmap[o + 2].toInt() and 0xFF) shl 16) or ((charsmap[o + 3].toInt() and 0xFF) shl 24)
                }
                trie = arr
                blob = charsmap.copyOfRange(4 + trieSize, charsmap.size)
            }
            val norm = Normalizer(trie, blob, addDummy, removeExtra, escapeWs, wsSuffix)
            return SentencePiece(
                pieces.toTypedArray(), scores.toFloatArray(), types.toIntArray(),
                modelType, byteFallback, unkId, norm,
            )
        }
    }
}
