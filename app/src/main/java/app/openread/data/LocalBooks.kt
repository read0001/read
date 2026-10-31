package app.openread.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeVisitor
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 本地导入的 TXT / EPUB。导入时把文件存进 filesDir/local/（TXT 统一转成 UTF-8），切好章节写进书架；读的时候按章节
 * 地址取正文：TXT 是 `txt:起:止`（字符位置），EPUB 是 `epub:压缩包里的路径`。本地书没有书源，不参与换源和核对。
 */
object LocalBooks {
    class Imported(val book: BookRow, val chapters: List<ChapterRow>)

    private fun dir(context: Context) = File(context.filesDir, "local").apply { mkdirs() }

    private fun key(bookUrl: String) = bookUrl.removePrefix("local://")

    private fun txtFile(context: Context, key: String) = File(dir(context), "$key.txt")
    private fun epubFile(context: Context, key: String) = File(dir(context), "$key.epub")

    /** 读 [uri] 指向的文件并切章；同一个文件再导入一次得到的是同一本书（地址由内容决定）。 */
    suspend fun import(context: Context, uri: Uri): Imported = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: "未命名"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("打不开这个文件")
        if (bytes.isEmpty()) error("文件是空的")
        val key = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(24)
        val bookUrl = "local://$key"
        val isEpub = name.endsWith(".epub", ignoreCase = true) || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
        if (isEpub) {
            val f = epubFile(context, key)
            f.writeBytes(bytes)
            runCatching { epub(context, f, bookUrl, key, name) }.getOrElse { f.delete(); throw IllegalStateException("EPUB 解析失败：${it.message}") }
        } else {
            val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
            txtFile(context, key).writeText(text)
            txt(text, bookUrl, name)
        }
    }

    fun delete(context: Context, bookUrl: String) {
        val k = key(bookUrl)
        dir(context).listFiles()?.filter { it.name.startsWith(k) }?.forEach { it.delete() }
        synchronized(textCache) { textCache.remove(k) }
    }

    /** 一章的正文。 */
    fun chapterText(context: Context, row: BookRow, ch: ChapterRow): String {
        val k = key(row.bookUrl)
        return when {
            ch.url.startsWith("txt:") -> {
                val (from, to) = ch.url.removePrefix("txt:").split(':').map { it.toInt() }
                val all = wholeText(context, k)
                all.substring(from.coerceIn(0, all.length), to.coerceIn(0, all.length))
            }
            ch.url.startsWith("epub:") -> ZipFile(epubFile(context, k)).use { z ->
                val e = z.getEntry(ch.url.removePrefix("epub:")) ?: error("EPUB 里没有这一章")
                val text = htmlToText(z.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8))
                // 正文开头的 <h1> 往往就是章节名，阅读页已经显示了标题，不再重复
                val first = text.substringBefore('\n').trim()
                val title = ch.title.trim()
                if (first.isNotEmpty() && first.length <= 40 && (first == title || title.startsWith(first) || first.startsWith(title))) text.substringAfter('\n', "") else text
            }
            else -> error("无法识别的本地章节")
        }
    }

    // 最近读的一本 TXT 整本留在内存里，翻章不用反复读文件
    private val textCache = LinkedHashMap<String, String>()

    private fun wholeText(context: Context, k: String): String = synchronized(textCache) {
        textCache[k] ?: txtFile(context, k).readText().also { textCache.clear(); textCache[k] = it }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** UTF-8（带不带 BOM 都行），不是合法 UTF-8 就按 GB18030（兼容 GBK / GB2312）读。 */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    // ---- TXT ----

    private const val NUM = "[0-9０-９零〇一二两三四五六七八九十百千万]"
    /** 一行就是章节标题：第x章/节/回/卷…，或者常见的序章、楔子、番外等。 */
    private val HEADING = Regex(
        "^[ \\t　]*((第${NUM}{1,9}[章节回卷集部篇].{0,30})|((?:序章|序言|楔子|引子|前言|后记|尾声|终章|完本感言|番外)(?:[ \\t　:：、].{0,30})?)|(Chapter\\s*\\d{1,5}.{0,30}))[ \\t　]*$",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )
    private const val PART = 6000

    private fun txt(text: String, bookUrl: String, fileName: String): Imported {
        val base = fileName.substringBeforeLast('.').trim()
        val name = Regex("《(.+?)》").find(base)?.groupValues?.get(1) ?: base
        val author = Regex("作者[：:]\\s*([^\\s\\n]{1,20})").find(text.take(2000))?.groupValues?.get(1)
            ?: Regex("[（(]?作者[：:]?\\s*([^）)]+)[）)]?").find(base.substringAfter(name, ""))?.groupValues?.get(1).orEmpty()
        val heads = HEADING.findAll(text).filter { it.value.trim().length <= 40 }.toList()
        val chapters = ArrayList<ChapterRow>()
        fun add(title: String, from: Int, to: Int) {
            // 只有标题、没有正文的（比如“第一卷”），当作分卷标题
            chapters += ChapterRow(bookUrl, chapters.size, title, "txt:$from:$to", text.substring(from, to).isBlank(), "{}")
        }
        if (heads.size >= 2) {
            // 第一个标题之前有内容（简介、版权说明），单独作为一章
            if (text.substring(0, heads[0].range.first).isNotBlank()) add("开始", 0, heads[0].range.first)
            heads.forEachIndexed { i, m ->
                val bodyStart = (m.range.last + 1).coerceAtMost(text.length)
                val end = heads.getOrNull(i + 1)?.range?.first ?: text.length
                add(m.value.trim(), bodyStart, end)
            }
        } else {
            // 没有章节标题：按约 6000 字切，切在换行处
            var from = 0
            while (from < text.length) {
                var to = (from + PART).coerceAtMost(text.length)
                if (to < text.length) text.indexOf('\n', to).let { if (it in to..(to + 2000)) to = it + 1 }
                add("第${chapters.size + 1}部分", from, to)
                from = to
            }
        }
        if (chapters.isEmpty()) error("没有读到文字")
        return Imported(row(bookUrl, name, author, null, chapters, text.length), chapters)
    }

    private fun row(bookUrl: String, name: String, author: String, cover: String?, chapters: List<ChapterRow>, chars: Int): BookRow {
        val now = System.currentTimeMillis()
        return BookRow(
            bookUrl = bookUrl, origin = LOCAL_ORIGIN, originName = "本地", name = name.ifBlank { "未命名" }, author = author, coverUrl = cover,
            intro = null, kind = null, tocUrl = "", latestChapter = chapters.lastOrNull()?.title, wordCount = if (chars > 10000) "${chars / 10000}万字" else "${chars}字",
            vars = "{}", addedAt = now, readAt = now, totalChapters = chapters.size, checkedAt = now,
        )
    }

    // ---- EPUB ----

    private fun epub(context: Context, file: File, bookUrl: String, key: String, fileName: String): Imported = ZipFile(file).use { z ->
        fun read(path: String) = z.getEntry(path)?.let { e -> z.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8) }
        val container = read("META-INF/container.xml") ?: error("缺少 container.xml")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).selectFirst("rootfile")?.attr("full-path") ?: error("找不到 OPF")
        val opf = Jsoup.parse(read(opfPath) ?: error("找不到 OPF"), "", Parser.xmlParser())
        val baseDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun resolve(href: String) = normalize(baseDir + java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8"))

        val title = opf.selectFirst("metadata > *|title, dc|title")?.text()?.trim().orEmpty().ifBlank { fileName.substringBeforeLast('.') }
        val author = opf.selectFirst("metadata > *|creator, dc|creator")?.text()?.trim().orEmpty()
        val items = opf.select("manifest > item").associate { it.attr("id") to it }
        val hrefOf = items.mapValues { resolve(it.value.attr("href")) }

        // 目录里的章节名：EPUB3 的 nav，或者 EPUB2 的 NCX
        val names = HashMap<String, String>()
        items.values.firstOrNull { it.attr("properties").split(' ').contains("nav") }?.let { nav ->
            val path = resolve(nav.attr("href"))
            val dirOf = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            Jsoup.parse(read(path).orEmpty()).select("nav a[href]").forEach { a ->
                names.putIfAbsent(normalize(dirOf + java.net.URLDecoder.decode(a.attr("href").substringBefore('#'), "UTF-8")), a.text().trim())
            }
        }
        if (names.isEmpty()) {
            val ncxId = opf.selectFirst("spine")?.attr("toc").orEmpty()
            val ncxPath = hrefOf[ncxId] ?: items.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }?.let { resolve(it.attr("href")) }
            if (ncxPath != null) {
                val dirOf = ncxPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                Jsoup.parse(read(ncxPath).orEmpty(), "", Parser.xmlParser()).select("navPoint").forEach { p ->
                    val src = p.selectFirst("content")?.attr("src") ?: return@forEach
                    val label = p.selectFirst("navLabel")?.text()?.trim().orEmpty()
                    names.putIfAbsent(normalize(dirOf + java.net.URLDecoder.decode(src.substringBefore('#'), "UTF-8")), label)
                }
            }
        }

        val chapters = ArrayList<ChapterRow>()
        var chars = 0
        opf.select("spine > itemref").forEach { ref ->
            val path = hrefOf[ref.attr("idref")] ?: return@forEach
            val html = read(path) ?: return@forEach
            val text = htmlToText(html)
            if (text.isBlank()) return@forEach
            chars += text.length
            val doc = Jsoup.parse(html)
            val t = names[path]?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() && it.length <= 40 }
                ?: doc.title().trim().takeIf { it.isNotBlank() && it != title }
                ?: "第${chapters.size + 1}节"
            chapters += ChapterRow(bookUrl, chapters.size, t, "epub:$path", false, "{}")
        }
        if (chapters.isEmpty()) error("没有读到正文")

        // 封面：EPUB3 的 cover-image，或 EPUB2 的 <meta name="cover">
        val coverItem = items.values.firstOrNull { it.attr("properties").split(' ').contains("cover-image") }
            ?: opf.selectFirst("meta[name=cover]")?.attr("content")?.let { items[it] }
        val cover = coverItem?.let { c ->
            val e = z.getEntry(resolve(c.attr("href"))) ?: return@let null
            val out = File(dir(context), "${key}_cover")
            z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            "file://" + out.absolutePath
        }
        Imported(row(bookUrl, title, author, cover, chapters, chars), chapters)
    }

    /** 去掉 `a/../b` 和 `./`。 */
    private fun normalize(path: String): String {
        val out = ArrayList<String>()
        path.split('/').forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += seg
            }
        }
        return out.joinToString("/")
    }

    private val BLOCKS = setOf("p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "section", "article", "blockquote", "pre", "hr", "dd", "dt")

    /** 网页正文变成一行一段的纯文字。 */
    fun htmlToText(html: String): String {
        val body = Jsoup.parse(html).body()
        body.select("script, style, rt, rp").remove()
        val sb = StringBuilder()
        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> sb.append(node.text())
                    is Element -> if (node.normalName() in BLOCKS && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCKS && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            }
        })
        return sb.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
