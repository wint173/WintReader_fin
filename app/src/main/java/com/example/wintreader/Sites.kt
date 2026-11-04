package com.example.wintreader

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

// ---------------------------------------------------------------- サイトの共通の形

/** 作品の情報と目次（サイトから取得したもの） */
data class FetchedWork(
    val siteId: String,
    val workId: String,
    val title: String,
    val author: String,
    val summary: String,
    val url: String,
    val chapters: List<FetchedChapter>,
)

data class FetchedChapter(
    val chapterId: String,
    val title: String,
    val url: String,
    val sectionTitle: String?,
    val updatedText: String?,
)

/** どのサイトのどの作品か */
data class WorkRef(val siteId: String, val workId: String, val workUrl: String)

/**
 * 1つのサイト（またはサイトのグループ）から作品を取り込む部品。
 * サイトごとの違い（URL の形・HTML の構造）はここに閉じ込め、あとは共通本文モデルで扱う。
 */
interface SiteAdapter {
    /** このアダプターが受け持つ siteId */
    val siteIds: Set<String>

    /** 作品（またはその話）のページの URL なら、どの作品かを返す（WEB-04） */
    fun parseUrl(url: String): WorkRef?

    /** 本棚に保存した siteId・workId から作品を指し直す */
    fun refOf(siteId: String, workId: String): WorkRef?

    suspend fun fetchWork(ref: WorkRef): FetchedWork

    suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument

    /** 目次を読まずに話数だけ軽く調べる方法があれば使う（なければ null） */
    suspend fun fetchEpisodeCount(ref: WorkRef): Int? = null

    /** 更新を確かめる意味があるか（青空文庫・Project Gutenberg のように、完結した作品だけのサイトは false） */
    val canCheckUpdates: Boolean get() = true
}

/** 対応サイトの一覧。URL や siteId から受け持ちのアダプターを探す */
class SiteRegistry(private val adapters: List<SiteAdapter>) {
    fun parseUrl(url: String): WorkRef? = adapters.firstNotNullOfOrNull { it.parseUrl(url) }
    fun adapterFor(siteId: String): SiteAdapter? = adapters.firstOrNull { siteId in it.siteIds }
    fun refOf(siteId: String, workId: String): WorkRef? = adapterFor(siteId)?.refOf(siteId, workId)
}

// ---------------------------------------------------------------- 本文の HTML → 共通本文モデル

/**
 * 各サイトの本文の段落（p）を共通本文モデルに変換する。
 * - ルビ：<ruby>親文字<rp>(</rp><rt>よみ</rt><rp>)</rp></ruby>（rb で囲まれていることもある）
 * - 傍点：読みが点（「・」「、」など）だけのルビ、em.emphasisDots（カクヨムの傍点記法）
 * - 挿絵：img、ハーメルンの「【挿絵表示】」リンク（どちらも段落を分けて置く。http:// は https:// に直す）
 */
object HtmlConverter {

    private const val DOT_CHARS = "・﹅﹆•●･、"

    /**
     * 前書き・本文・後書きのように分かれた本文をつなぐ。それぞれの頭と終わりの空行・区切り線は落とし、
     * 間に区切り線を1本だけ入れる
     */
    fun convertSections(boxes: List<Element>, out: MutableList<ContentBlock>) {
        var first = true
        for (box in boxes) {
            val part = mutableListOf<ContentBlock>()
            convertBody(box, part)
            val isEdge = { b: ContentBlock -> b is ContentBlock.BlankLine || b is ContentBlock.Divider }
            while (part.isNotEmpty() && isEdge(part.first())) part.removeAt(0)
            while (part.isNotEmpty() && isEdge(part.last())) part.removeAt(part.lastIndex)
            if (part.isEmpty()) continue
            if (!first) out += ContentBlock.Divider()
            out += part
            first = false
        }
    }

    /**
     * 本文の入れ物の中身を順に変換する（p・div は段落、hr は区切り線）。
     * p で囲まれず br で改行しているだけの文（前書き・後書きに多い）は、br ごとに1段落にする
     */
    fun convertBody(box: Element, out: MutableList<ContentBlock>) {
        // 行をまとめる入れ物。挿絵の相対 URL を解決できるよう、元のページの URL を引き継ぐ
        fun newLine() = Element(Tag.valueOf("p"), box.baseUri())
        var line = newLine()
        fun flushLine() {
            if (line.childNodeSize() > 0) {
                // 行の頭と終わりの半角の空白・改行（HTML の字下げ）は落とす。全角の空白は字下げなので残す
                (line.childNode(0) as? TextNode)?.let { it.text(it.wholeText.trimStart(' ', '\t', '\n', '\r')) }
                (line.childNode(line.childNodeSize() - 1) as? TextNode)?.let { it.text(it.wholeText.trimEnd(' ', '\t', '\n', '\r')) }
                convertParagraph(line, out)
            }
            line = newLine()
        }
        for (node in box.childNodes().toList()) {
            when {
                node is Element && node.normalName() == "hr" -> { flushLine(); out += ContentBlock.Divider() }
                // 中に改行（br）や段落を含む div（青空文庫の字下げのまとまりなど）は、中身を同じように変換する
                node is Element && node.normalName() == "div" && node.children().any { it.normalName() in setOf("br", "p", "div") } -> { flushLine(); convertBody(node, out) }
                node is Element && node.normalName() in setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li") -> {
                    flushLine(); convertParagraph(node, out)
                }
                node is Element && node.normalName() in setOf("ul", "ol", "section", "article") -> { flushLine(); convertBody(node, out) }
                node is Element && node.normalName() == "br" -> {
                    // 改行だけの行は空行
                    if (line.childNodeSize() == 0) out += ContentBlock.BlankLine else flushLine()
                }
                node is TextNode && node.isBlank && line.childNodeSize() == 0 -> Unit // 要素の間の改行・空白
                else -> line.appendChild(node.clone())
            }
        }
        flushLine()
    }

    fun convertParagraph(p: Element, out: MutableList<ContentBlock>) {
        val spans = mutableListOf<TextSpan>()
        var hadImage = false
        // 挿絵の手前までの文を段落にする（空白だけなら捨てる）
        fun flushSpans() {
            if (spans.any { it !is TextSpan.Plain || it.text.isNotBlank() }) out += ContentBlock.Paragraph(spans.toList())
            spans.clear()
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    val t = joinLines(node.wholeText)
                    if (t.isNotEmpty()) spans += TextSpan.Plain(t)
                }
                is Element -> when {
                    node.normalName() == "ruby" -> spans += convertRuby(node)
                    // 青空文庫の外字（画像の文字）は「※」に置き換える。入力者の注記は出さない
                    node.normalName() == "img" && node.hasClass("gaiji") -> spans += TextSpan.Plain("※")
                    node.normalName() == "span" && node.hasClass("notes") -> Unit
                    // Project Gutenberg の原本のページ番号は出さない
                    node.normalName() == "span" && node.hasClass("pagenum") -> Unit
                    node.normalName() == "em" && (node.hasClass("emphasisDots") || node.hasClass("sesame_dot")) -> {
                        val t = node.text()
                        if (t.isNotEmpty()) spans += TextSpan.Emphasis(t)
                    }
                    node.normalName() == "img" -> {
                        flushSpans()
                        val src = node.absUrl("src").ifEmpty { node.attr("src") }
                        if (src.isNotEmpty()) {
                            out += ContentBlock.Image(secure(src), node.attr("alt").ifBlank { null })
                            hadImage = true
                        }
                    }
                    // ハーメルンの挿絵：<a href="http://syosetu.org/img/user/….png" alt="挿絵" name="img">【挿絵表示】</a>
                    // 押さないと出ない作りだが、本文の中にそのまま挿絵として置く（表示設定で隠せる）
                    isIllustrationLink(node) -> {
                        flushSpans()
                        val href = node.absUrl("href").ifEmpty { node.attr("href") }
                        out += ContentBlock.Image(secure(href), node.attr("alt").ifBlank { null })
                        hadImage = true
                    }
                    node.normalName() in setOf("br", "rp", "rt", "script", "style") -> Unit
                    else -> node.childNodes().forEach { walk(it) }
                }
                else -> Unit
            }
        }
        p.childNodes().forEach { walk(it) }

        // 段落の頭と終わりの半角の空白（HTML の改行の名残）は落とす。全角の空白は字下げなので残す
        (spans.firstOrNull() as? TextSpan.Plain)?.let { spans[0] = TextSpan.Plain(it.text.trimStart(' ')) }
        (spans.lastOrNull() as? TextSpan.Plain)?.let { spans[spans.lastIndex] = TextSpan.Plain(it.text.trimEnd(' ')) }
        if (spans.any { it !is TextSpan.Plain || it.text.isNotBlank() }) {
            flushSpans()
        } else if (!hadImage) {
            // 中身のない段落（全角空白だけの段落を含む）は空行。挿絵だけの段落は挿絵だけを置く
            out += ContentBlock.BlankLine
        }
    }

    /**
     * 改行（\n）で行を分けている本文（ノベルアップ＋など）を、br で分けた形に直してから変換する
     */
    fun convertLines(el: Element, out: MutableList<ContentBlock>) {
        val copy = el.clone()
        for (t in copy.select("*").flatMap { it.textNodes() }.toList()) {
            val text = t.wholeText
            if (!text.contains('\n')) continue
            val parts = text.replace("\r\n", "\n").split('\n')
            var anchor: Node = t
            parts.forEachIndexed { i, part ->
                if (i > 0) {
                    val br = Element("br")
                    anchor.after(br)
                    anchor = br
                }
                if (part.isNotEmpty()) {
                    val tn = TextNode(part)
                    anchor.after(tn)
                    anchor = tn
                }
            }
            t.remove()
        }
        convertBody(copy, out)
    }

    /**
     * HTML の中の改行をどう扱うか。日本語どうしの間なら消し（「あ\nい」→「あい」）、
     * 英語などの単語の間なら空白にする（「the\nend」→「the end」。Project Gutenberg は行を折り返して書いてある）
     */
    private fun joinLines(text: String): String {
        if (!text.contains('\n')) return text
        return Regex("""[ \t\r]*\n[ \t\r\n]*""").replace(text) { m ->
            val before = text.getOrNull(m.range.first - 1)
            val after = text.getOrNull(m.range.last + 1)
            fun cjk(c: Char?) = c != null && c.code >= 0x2E80
            if ((before == null || cjk(before)) && (after == null || cjk(after))) "" else " "
        }
    }

    private val IMAGE_EXT = Regex("""\.(jpe?g|png|gif|webp)$""", RegexOption.IGNORE_CASE)

    /** 挿絵を開くためのリンク（画像そのものへのリンク）か */
    private fun isIllustrationLink(a: Element): Boolean {
        if (a.normalName() != "a") return false
        val href = a.attr("href").substringBefore('?')
        if (!IMAGE_EXT.containsMatchIn(href)) return false
        return a.attr("name") == "img" || a.text().contains("挿絵")
    }

    /** 暗号化なし（http://）の画像は Android の WebView が読み込まないので https:// に直す */
    private fun secure(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    fun convertRuby(ruby: Element): TextSpan {
        val reading = ruby.select("rt").joinToString("") { it.text() }
        val base = buildString {
            ruby.childNodes().forEach { n ->
                when {
                    n is TextNode -> append(n.text())
                    n is Element && n.normalName() !in setOf("rt", "rp") -> append(n.text())
                }
            }
        }.trim()
        // 読みが点だけのルビは傍点（なろう・ハーメルンは「・」、カクヨムは「、」で書く作品が多い）
        val dots = reading.isNotEmpty() && reading.all { it in DOT_CHARS }
        return if (dots) TextSpan.Emphasis(base) else TextSpan.Ruby(base, reading)
    }
}
