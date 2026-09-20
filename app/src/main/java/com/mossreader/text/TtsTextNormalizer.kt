package com.mossreader.text

/**
 * 送进 TTS 之前的文本清洗（参考 MOSS-TTS-Nano 的 tts_robust_normalizer，针对小说场景做了调整）：
 *  - 去掉零宽/控制字符/表情符号，全角空格转空格
 *  - 「」『』【】等括号统一成中文引号
 *  - 省略号、破折号：句中变逗号，句尾变句号
 *  - 重复标点收敛
 *  - 中文语境下把阿拉伯数字读成汉字（WeTextProcessing 的轻量替代）
 *  - 保证末尾有标点；被强行截断的半句补逗号而不是句号，避免语气"落地"
 */
object TtsTextNormalizer {

    private val ZERO_WIDTH = Regex("[\\u200b-\\u200d\\u2060\\ufeff]")
    private val ELLIPSIS = Regex("(?:\\.{3,}|…+|。{2,}|·{3,})")
    private val DASH = Regex("\\s*(?:[—―─]+|-{2,})\\s*")
    private val TAIL_CLOSERS = "”’」』）】》〉)]\"' \t"
    private const val CJK_CLASS = "\\u3400-\\u4dbf\\u4e00-\\u9fff\\u3040-\\u30ff"
    private val ARROWS = Regex("\\s*[→←↔⇒⇐⇔]\\s*")
    private val BRACKET_CN = Regex("[【〖]\\s*([^】〗]+?)\\s*[】〗]")
    private val BRACKET_SQ = Regex("\\[\\s*([^\\[\\]]+?)\\s*]")
    private val DOTS = Regex("[。．]{2,}")
    private val COMMAS = Regex("[，,]{2,}")
    private val EXCL = Regex("[!！]{2,}")
    private val QUES = Regex("[?？]{2,}")
    private val MIXED = Regex("[!?！？]{2,}")
    private val COMMA_BEFORE_END = Regex("[，、；：]+([。！？])")
    private val MULTI_SPACE = Regex("[ ]{2,}")
    private val CJK_SPACE = Regex("([$CJK_CLASS])\\s+(?=[$CJK_CLASS])")
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([，。！？；：、”’）】》])")
    private val SPACE_AFTER_OPEN = Regex("([（【《“‘])\\s+")

    fun containsCjk(s: String): Boolean {
        for (c in s) {
            if (c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf' || c in '\u3040'..'\u30ff' || c in '\uac00'..'\ud7af') return true
        }
        return false
    }

    private fun isTail(s: String, from: Int): Boolean {
        var i = from
        while (i < s.length) {
            if (TAIL_CLOSERS.indexOf(s[i]) < 0) return false
            i++
        }
        return true
    }

    /** 基础清洗：不含数字读法、不补句尾标点。 */
    fun clean(input: String): String {
        var t = input.replace("\r\n", "\n").replace('\r', '\n').replace('\u3000', ' ')
        t = ZERO_WIDTH.replace(t, "")
        t = t.replace("℃", "摄氏度").replace("℉", "华氏度")
        t = ARROWS.replace(t, "，")
        // 全角数字 / 字母 → 半角
        val fw = StringBuilder(t.length)
        for (c in t) {
            if (c in '０'..'９' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ') fw.append((c.code - 0xFEE0).toChar()) else fw.append(c)
        }
        t = fw.toString()
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val n = Character.charCount(cp)
            val type = Character.getType(cp)
            val drop = when (type.toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                Character.UNASSIGNED, Character.OTHER_SYMBOL, Character.MODIFIER_SYMBOL -> true
                else -> false
            }
            if (cp == '\n'.code || cp == '\t'.code) sb.append(' ')
            else if (!drop) sb.appendCodePoint(cp)
            i += n
        }
        t = sb.toString()

        // 括号 → 中文引号
        t = t.replace('「', '“').replace('」', '”').replace('『', '“').replace('』', '”')
        t = BRACKET_CN.replace(t) { "“" + it.groupValues[1] + "”" }
        t = BRACKET_SQ.replace(t) { "“" + it.groupValues[1] + "”" }

        // 省略号 / 破折号
        t = ELLIPSIS.replace(t) { m -> if (isTail(t, m.range.last + 1)) "。" else "，" }
        t = DASH.replace(t) { m -> if (isTail(t, m.range.last + 1)) "。" else "，" }

        // 重复 / 混合标点
        t = DOTS.replace(t, "。")
        t = COMMAS.replace(t, "，")
        t = EXCL.replace(t, "！")
        t = QUES.replace(t, "？")
        t = MIXED.replace(t) { m ->
            val q = m.value.any { it == '?' || it == '？' }
            val e = m.value.any { it == '!' || it == '！' }
            if (q && e) "？！" else if (q) "？" else "！"
        }
        t = COMMA_BEFORE_END.replace(t, "$1")

        // 空格：汉字之间去掉；标点前后去掉
        t = MULTI_SPACE.replace(t, " ")
        t = CJK_SPACE.replace(t, "$1")
        t = SPACE_BEFORE_PUNCT.replace(t, "$1")
        t = SPACE_AFTER_OPEN.replace(t, "$1")
        return t.trim()
    }

    /** 最终送给模型的文本。continuing=true 表示这是句子中间被切开的一段。 */
    fun speakText(raw: String, continuing: Boolean = false): String {
        var t = clean(raw)
        if (t.isEmpty()) return t
        val cjk = containsCjk(t)
        if (cjk) t = ChineseNumbers.normalize(t)
        t = ensureTerminal(t, cjk, continuing)
        if (!cjk) {
            if (t[0].isLowerCase()) t = t[0].uppercaseChar() + t.substring(1)
            if (t.split(' ').count { it.isNotEmpty() } < 5) t = "        $t"
        }
        return t
    }

    private fun ensureTerminal(text: String, cjk: Boolean, continuing: Boolean): String {
        var idx = text.length - 1
        while (idx >= 0 && (text[idx].isWhitespace() || TAIL_CLOSERS.indexOf(text[idx]) >= 0)) idx--
        if (idx < 0) return text
        val ch = text[idx]
        val isPunct = Character.getType(ch).let {
            it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
                it == Character.OTHER_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
                it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                it == Character.FINAL_QUOTE_PUNCTUATION.toInt()
        }
        if (isPunct) return text
        val add = if (continuing) (if (cjk) "，" else ",") else (if (cjk) "。" else ".")
        return text.substring(0, idx + 1) + add + text.substring(idx + 1)
    }
}

/** 阿拉伯数字 → 中文读法（整数 / 小数 / 百分数 / 年份 / 电话号码式逐位读）。 */
object ChineseNumbers {
    private const val DIG = "零一二三四五六七八九"
    private val NUM = Regex("(?<![A-Za-z\\d.])(\\d+(?:\\.\\d+)?)(\\s?[%％])?(?![A-Za-z])")
    private val TIME = Regex("(?<![\\d:])([01]?\\d|2[0-3])(?::|：)([0-5]\\d)(?![\\d:])")

    fun normalize(text: String): String {
        var t = TIME.replace(text) { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            readInt(h.toString()) + "点" + (if (mi == 0) "整" else if (mi < 10) "零" + readInt(mi.toString()) + "分" else readInt(mi.toString()) + "分")
        }
        t = NUM.replace(t) { m ->
            val num = m.groupValues[1]
            val pct = m.groupValues[2].isNotEmpty()
            val after = m.range.last + 1
            val followedByYear = after < t.length && t[after] == '年'
            val body = when {
                followedByYear && num.length == 4 && !num.contains('.') -> digitByDigit(num)
                num.contains('.') -> {
                    val (ip, fp) = num.split('.', limit = 2)
                    (if (ip.length > 12 || (ip.length > 1 && ip.startsWith("0"))) digitByDigit(ip) else readInt(ip)) + "点" + digitByDigit(fp)
                }
                num.length >= 9 || (num.length > 1 && num.startsWith("0")) -> digitByDigit(num)
                else -> readInt(num)
            }
            if (pct) "百分之$body" else body
        }
        return t
    }

    fun digitByDigit(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (c in '0'..'9') sb.append(DIG[c - '0']) else sb.append(c)
        return sb.toString()
    }

    /** 纯数字串（≤12 位）读成汉字。 */
    fun readInt(digits: String): String {
        val s = digits.trimStart('0').ifEmpty { return "零" }
        val groups = s.reversed().chunked(4).map { it.reversed() }   // groups[0] 是个级
        val units = listOf("", "万", "亿", "万亿")
        var out = ""
        var zeroPending = false
        for (gi in groups.indices.reversed()) {
            val g = groups[gi]
            val v = g.toInt()
            if (v == 0) { if (out.isNotEmpty()) zeroPending = true; continue }
            var text = readGroup(g)
            if (gi >= 1 && v == 2) text = "两"
            if (out.isNotEmpty() && (zeroPending || v < 1000)) out += "零"
            zeroPending = false
            if (out.isEmpty() && text.startsWith("一十")) text = text.substring(1)
            out += text + units.getOrElse(gi) { "" }
        }
        return out
    }

    private fun readGroup(g: String): String {
        val sb = StringBuilder()
        var zero = false
        val len = g.length
        for ((i, ch) in g.withIndex()) {
            val d = ch - '0'
            val pos = len - 1 - i
            if (d == 0) { zero = true; continue }
            if (zero && sb.isNotEmpty()) sb.append('零')
            zero = false
            if (d == 2 && pos >= 2) sb.append('两') else sb.append(DIG[d])
            if (pos > 0) sb.append("个十百千"[pos])
        }
        return sb.toString()
    }
}
