package com.mossreader.text

import java.util.Locale

/** 用 Android 自带的 ICU 词典分词（中日文词边界），用来避免在词中间断句。 */
class AndroidWordBreaker : WordBreaker {
    override fun allowedBreaks(text: String): BooleanArray {
        val n = text.length
        val r = BooleanArray(n + 1)
        try {
            val it = android.icu.text.BreakIterator.getWordInstance(Locale.CHINA)
            it.setText(text)
            var b = it.first()
            var count = 0
            while (b != android.icu.text.BreakIterator.DONE) {
                if (b in 0..n) { r[b] = true; count++ }
                b = it.next()
            }
            // 词典不可用时会退化成整段不分：这时改用逐字断点
            if (n > 12 && count < n / 8) return SimpleWordBreaker.allowedBreaks(text)
        } catch (e: Throwable) {
            return SimpleWordBreaker.allowedBreaks(text)
        }
        return r
    }
}
