package com.example.wintreader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag
import java.io.ByteArrayInputStream

/**
 * 1冊が1つの大きな HTML になっているサイト（青空文庫・Project Gutenberg）の共通部分。
 * 見出しのところで本文を分けて「話」にする（長い作品を1ページに入れると重いため）。
 * 同じ HTML を話ごとに何度も取りに行かないよう、最後に読んだ HTML を覚えておく。
 */
abstract class BookAdapter(protected val http: SiteHttp) : SiteAdapter {

    override val canCheckUpdates: Boolean get() = false

    /** 本の一区切り（話）。nodes は本文の HTML の部品 */
    protected class Part(val title: String, val section: String?, val anchor: String?, val nodes: List<Node>)

    private var cachedUrl: String? = null
    private var cachedDoc: Document? = null

    /** 本の HTML を読む（文字コードはページの meta から判断する） */
    protected suspend fun loadBook(url: String): Document {
        if (cachedUrl == url) cachedDoc?.let { return it }
        val bytes = http.getBytes(url)
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, url)
        cachedUrl = url
        cachedDoc = doc
        return doc
    }

    /**
     * 本文の部品の並びを見出しで区切る。
     * headingOf：その部品が見出しなら (見出しの文字, 区切りの強さ) を返す。強さ 1 = 章（section になる）、2 = 話
     */
    protected fun split(
        nodes: List<Node>,
        firstTitle: String,
        headingOf: (Element) -> Pair<String, Int>?,
        anchorOf: (Element) -> String?,
    ): List<Part> {
        val headings = nodes.mapNotNull { (it as? Element)?.let(headingOf) }
        // 話の見出し（強さ 2）があればそこで、なければ章の見出しで区切る
        val splitLevel = if (headings.any { it.second == 2 }) 2 else 1
        val parts = mutableListOf<Part>()
        var section: String? = null
        var title = firstTitle
        var anchor: String? = null
        var current = mutableListOf<Node>()
        fun flush() {
            val hasText = current.any { n -> (n is TextNode && !n.isBlank) || (n is Element && (n.hasText() || n.selectFirst("img") != null)) }
            if (hasText) parts += Part(title, section, anchor, current.toList())
            current = mutableListOf()
        }
        for (n in nodes) {
            val h = (n as? Element)?.let(headingOf)
            if (h == null) { current += n; continue }
            val (text, level) = h
            if (level == 1) {
                flush()
                section = text
                title = text
                anchor = anchorOf(n as Element)
            } else if (level >= splitLevel) {
                flush()
                title = text
                anchor = anchorOf(n as Element)
            } else {
                current += n
            }
        }
        flush()
        return parts
    }

    /** 区切った部品を共通本文モデルにする */
    protected fun partToBlocks(part: Part, baseUri: String): List<ContentBlock> {
        val box = Element(Tag.valueOf("div"), baseUri)
        part.nodes.forEach { box.appendChild(it.clone()) }
        val blocks = mutableListOf<ContentBlock>()
        HtmlConverter.convertSections(listOf(box), blocks)
        return blocks
    }
}

// ---------------------------------------------------------------- 青空文庫

/**
 * 青空文庫（www.aozora.gr.jp）。著作権の切れた作品
 * ページの構造（2026年11月時点で確認）：
 *   図書カード https://www.aozora.gr.jp/cards/{作家番号}/card{作品番号}.html（UTF-8）
 *     表の「作品名：」「著者名：」、本文は ./files/{作品番号}_{番号}.html（XHTML 版）へのリンク
 *   本文（XHTML 版。Shift_JIS）：.main_text の中に、br で改行した文と、見出し
 *     h3.o-midashi（大見出し）・h4.naka-midashi（中見出し）・h5.ko-midashi（小見出し）。字下げは div.jisage_N
 *     ルビは ruby、傍点は em.sesame_dot、外字は img.gaiji、入力者注は span.notes
 *   見出しで分けて話にする（中見出しがあれば中見出しで、なければ大見出しで）
 */
class AozoraAdapter(http: SiteHttp) : BookAdapter(http) {

    companion object {
        const val SITE = "aozora"
        private val CARD_URL = Regex("""^https?://(?:www\.)?aozora\.gr\.jp/cards/(\d+)/(?:card(\d+)\.html|files/(\d+)_\d+\.html)""")
    }

    override val siteIds = setOf(SITE)

    override fun parseUrl(url: String): WorkRef? {
        val m = CARD_URL.find(url) ?: return null
        val book = m.groupValues[2].ifEmpty { m.groupValues[3] }
        return refOf(SITE, "${m.groupValues[1]}-$book")
    }

    override fun refOf(siteId: String, workId: String): WorkRef? {
        if (siteId != SITE) return null
        val (person, book) = workId.split("-").takeIf { it.size == 2 } ?: return null
        return WorkRef(SITE, workId, "https://www.aozora.gr.jp/cards/$person/card$book.html")
    }

    /** 図書カードから、作品名・著者名・XHTML 版の URL を読む */
    private suspend fun card(ref: WorkRef): Triple<String, String, String> {
        val doc = Jsoup.parse(ByteArrayInputStream(http.getBytes(ref.workUrl)), null, ref.workUrl)
        fun field(label: String) = doc.select("tr").firstOrNull { it.selectFirst("td")?.text()?.trim() == label }
            ?.select("td")?.getOrNull(1)?.text()?.trim()
        val title = field("作品名：") ?: throw IllegalStateException("作品名が見つかりません（ページの構造が変わった可能性があります）")
        val author = field("著者名：") ?: ""
        val book = ref.workId.substringAfter('-')
        val xhtml = doc.select("a[href]").firstOrNull { Regex("""files/${book}_\d+\.html$""").containsMatchIn(it.attr("href")) }
            ?.absUrl("href")
            ?: throw IllegalStateException("この作品には XHTML 版の本文がないため、読み込めません")
        return Triple(title, author, xhtml)
    }

    private fun parts(doc: Document, title: String): List<Part> {
        val main = doc.selectFirst(".main_text") ?: throw IllegalStateException("本文が見つかりません（ページの構造が変わった可能性があります）")
        fun headingEl(e: Element): Element? = when {
            e.normalName() in setOf("h3", "h4") -> e
            // 字下げの div の中に見出しだけが入っている形
            e.normalName() == "div" && e.children().size == 1 && e.child(0).normalName() in setOf("h3", "h4") -> e.child(0)
            else -> null
        }
        return split(
            nodes = main.childNodes().toList(),
            firstTitle = title,
            headingOf = { e -> headingEl(e)?.let { h -> h.text().trim() to (if (h.normalName() == "h3") 1 else 2) } },
            anchorOf = { e -> headingEl(e)?.selectFirst("a[id]")?.id() },
        )
    }

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val (title, author, xhtml) = card(ref)
        val parts = parts(loadBook(xhtml), title)
        val chapters = parts.mapIndexed { i, p ->
            FetchedChapter(
                chapterId = "${i + 1}",
                title = p.title,
                url = xhtml + (p.anchor?.let { "#$it" } ?: ""),
                sectionTitle = p.section?.takeIf { it != p.title },
                updatedText = null,
            )
        }
        if (chapters.isEmpty()) throw IllegalStateException("本文が空です")
        return FetchedWork(SITE, ref.workId, title, author, "", ref.workUrl, chapters)
    }

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val xhtml = chapter.url.substringBefore('#')
        val doc = loadBook(xhtml)
        val parts = parts(doc, chapter.title)
        val part = parts.getOrNull((chapter.chapterId.toIntOrNull() ?: 1) - 1)
            ?: throw IllegalStateException("本文の区切りが見つかりません（目次を取り直してください）")
        return NovelDocument(ref.workId, chapter.chapterId, chapter.title, partToBlocks(part, xhtml))
    }
}

// ---------------------------------------------------------------- Project Gutenberg

/**
 * Project Gutenberg（www.gutenberg.org）。アメリカで著作権の切れた作品（主に英語）
 * ページの構造（2026年11月時点で確認）：
 *   作品ページ https://www.gutenberg.org/ebooks/{番号}
 *   本文 https://www.gutenberg.org/cache/epub/{番号}/pg{番号}-images.html（1冊が1つの HTML）
 *     meta[name=dc.title]・meta[name=dc.creator]、前後の案内は header/footer/section.pg-boilerplate
 *     章の見出しは h2（div.chapter に入っている本もある）。原本のページ番号は span.pagenum
 */
class GutenbergAdapter(http: SiteHttp) : BookAdapter(http) {

    companion object {
        const val SITE = "gutenberg"
        private val WORK_URL = Regex("""^https?://(?:www\.)?gutenberg\.org/(?:ebooks|cache/epub|files)/(\d+)""")
    }

    override val siteIds = setOf(SITE)

    override fun parseUrl(url: String): WorkRef? {
        val m = WORK_URL.find(url) ?: return null
        return refOf(SITE, m.groupValues[1])
    }

    override fun refOf(siteId: String, workId: String): WorkRef? =
        if (siteId == SITE) WorkRef(SITE, workId, "https://www.gutenberg.org/ebooks/$workId") else null

    private fun bookUrl(id: String) = "https://www.gutenberg.org/cache/epub/$id/pg$id-images.html"

    /** 本文の部品を、div.chapter などの入れ物をほどいて1列に並べる */
    private fun flatten(parent: Element): List<Node> = parent.childNodes().flatMap { n ->
        when {
            n is Element && n.normalName() in setOf("header", "footer") -> emptyList()
            n is Element && (n.hasClass("pg-boilerplate") || n.id() in setOf("pg-header", "pg-footer")) -> emptyList()
            // 章の入れ物（div.chapter や section）の中に見出しがあれば、ほどいて中身を並べる
            n is Element && n.normalName() in setOf("div", "section") && n.children().any { it.normalName() == "h2" } -> flatten(n)
            else -> listOf(n)
        }
    }

    private fun headingText(h: Element): String {
        val c = h.clone()
        c.select("span.pagenum").remove()
        val t = c.text().trim()
        return t.ifEmpty { h.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }
    }

    private fun parts(doc: Document, title: String): List<Part> = split(
        nodes = flatten(doc.body()),
        firstTitle = title,
        headingOf = { e -> if (e.normalName() == "h2") (headingText(e).ifEmpty { "（見出しなし）" } to 2) else null },
        anchorOf = { e -> e.selectFirst("a[id]")?.id() ?: e.id().ifEmpty { null } },
    )

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val url = bookUrl(ref.workId)
        val doc = loadBook(url)
        val title = doc.selectFirst("meta[name=dc.title]")?.attr("content")?.trim()
            ?: doc.title().trim().ifEmpty { throw IllegalStateException("作品名が見つかりません") }
        // 「Austen, Jane, 1775-1817」→「Jane Austen」
        val author = doc.select("meta[name=dc.creator]").firstOrNull()?.attr("content")?.let { c ->
            val p = c.split(", ")
            if (p.size >= 2 && !p[1].first().isDigit()) "${p[1]} ${p[0]}" else p[0]
        } ?: ""
        val chapters = parts(doc, title).mapIndexed { i, p ->
            FetchedChapter("${i + 1}", p.title, url + (p.anchor?.let { "#$it" } ?: ""), null, null)
        }
        if (chapters.isEmpty()) throw IllegalStateException("本文が空です")
        return FetchedWork(SITE, ref.workId, title, author, "", ref.workUrl, chapters)
    }

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val url = bookUrl(ref.workId)
        val doc = loadBook(url)
        val part = parts(doc, chapter.title).getOrNull((chapter.chapterId.toIntOrNull() ?: 1) - 1)
            ?: throw IllegalStateException("本文の区切りが見つかりません（目次を取り直してください）")
        val lang = doc.selectFirst("meta[name=dc.language]")?.attr("content")?.trim()?.ifEmpty { null } ?: "en"
        return NovelDocument(ref.workId, chapter.chapterId, chapter.title, partToBlocks(part, url), lang)
    }
}
