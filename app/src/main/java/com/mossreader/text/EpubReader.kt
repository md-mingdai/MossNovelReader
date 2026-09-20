package com.mossreader.text

import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

class EpubBook(val title: String, val chapters: List<Pair<String, String>>)

/** 极简 EPUB 解析：container.xml → OPF → spine → XHTML 转纯文本。 */
object EpubReader {
    private val TEXT_EXT = setOf("xhtml", "html", "htm", "xml", "opf", "ncx")

    fun read(input: InputStream): EpubBook {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext in TEXT_EXT) files[e.name] = zis.readBytes()
            }
        }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: error("不是有效的 EPUB（缺少 container.xml）")
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1)
            ?: error("EPUB 缺少 OPF 路径")
        val opf = files[opfPath]?.toString(Charsets.UTF_8) ?: error("EPUB 缺少 OPF 文件")
        val baseDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""

        val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(opf)
            ?.groupValues?.get(1)?.let { decodeEntities(stripTags(it)).trim() }.orEmpty()

        val manifest = HashMap<String, String>()
        for (m in Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            manifest[id] = href
        }
        val spine = ArrayList<String>()
        for (m in Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)) {
            val tag = m.value
            if (attr(tag, "linear")?.lowercase() == "no") continue
            val idref = attr(tag, "idref") ?: continue
            manifest[idref]?.let { spine.add(it) }
        }

        val chapters = ArrayList<Pair<String, String>>()
        for ((idx, href) in spine.withIndex()) {
            val clean = href.substringBefore('#')
            val path = resolve(baseDir, decodeUrl(clean))
            val bytes = files[path] ?: files[clean] ?: continue
            val html = decodeHtmlBytes(bytes)
            val heading = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(html)?.groupValues?.get(1)?.let { decodeEntities(stripTags(it)).replace(Regex("\\s+"), " ").trim() }.orEmpty()
            val htmlTitle = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(html)?.groupValues?.get(1)?.let { decodeEntities(stripTags(it)).trim() }.orEmpty()
            val text = htmlToText(html)
            if (text.count { !it.isWhitespace() } < 30) continue
            val t = heading.ifEmpty { htmlTitle }.ifEmpty { "第${idx + 1}节" }
            chapters.add(t.take(40) to text)
        }
        return EpubBook(title, chapters)
    }

    private fun decodeHtmlBytes(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 300), Charsets.ISO_8859_1).lowercase()
        val cs = Regex("encoding\\s*=\\s*[\"']([\\w-]+)[\"']").find(head)?.groupValues?.get(1)
        return try {
            if (cs != null && !cs.equals("utf-8", true)) String(bytes, charset(cs)) else String(bytes, Charsets.UTF_8)
        } catch (e: Exception) { String(bytes, Charsets.UTF_8) }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    private fun decodeUrl(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) { s }

    private fun resolve(base: String, rel: String): String {
        val parts = ArrayList<String>()
        for (p in (base + rel).split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), "")

    fun htmlToText(html: String): String {
        var t = html
        t = t.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace(Regex("<(script|style|head)\\b.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        t = t.replace(Regex("\\s+"), " ")
        t = t.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("</(p|div|h[1-6]|li|tr|blockquote|section|article)\\s*>", RegexOption.IGNORE_CASE), "\n")
        t = stripTags(t)
        t = decodeEntities(t)
        val lines = t.split('\n').map { it.replace('\u00A0', ' ').trim() }.filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "middot" to "·", "laquo" to "«", "raquo" to "»",
    )

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);").replace(s) { m ->
            val g = m.groupValues[1]
            when {
                g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[g] ?: m.value
            }
        }
    }
}
