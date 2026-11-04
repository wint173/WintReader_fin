package com.example.wintreader

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

private val looseJson = Json { ignoreUnknownKeys = true }

private fun JsonElement?.o(key: String): JsonElement? = (this as? JsonObject)?.get(key)
private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull // JsonNull は null になる
private fun JsonElement?.b(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

// ---------------------------------------------------------------- ノベルアップ＋

/**
 * ノベルアップ＋（novelup.plus）。
 * ページの構造（2026年11月時点で確認）：
 *   作品ページ https://novelup.plus/story/{作品番号}（目次は1ページ100話。?p=2 … で続き）
 *     作品名 h1.storyTitle・作者 a.authorName（または a.storyAuthor）
 *     目次 div.episodeList の中に .episodeListItem.chapter（章）と .episodeListItem の a.episodeTitle（話）
 *   話のページ https://novelup.plus/story/{作品番号}/{話番号}
 *     本文 p#episode_content（改行 \n で行を分けた文。ルビは ruby）
 *   ボット対策（AWS WAF）の確認が出ることがある。そのときはアプリ内ブラウザで一度サイトを開いてもらう
 */
class NovelupAdapter(private val http: SiteHttp) : SiteAdapter {

    companion object {
        const val SITE = "novelup"
        private val WORK_URL = Regex("""^https?://novelup\.plus/story/(\d+)(?:[/?#].*)?$""")
    }

    override val siteIds = setOf(SITE)

    override fun parseUrl(url: String): WorkRef? = WORK_URL.find(url)?.let { refOf(SITE, it.groupValues[1]) }

    override fun refOf(siteId: String, workId: String): WorkRef? =
        if (siteId == SITE) WorkRef(SITE, workId, "https://novelup.plus/story/$workId") else null

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val first = Jsoup.parse(http.get(ref.workUrl), ref.workUrl)
        val title = first.selectFirst("h1.storyTitle")?.text()?.trim()
            ?: throw IllegalStateException("作品名が見つかりません（ページの構造が変わった可能性があります）")
        val author = (first.selectFirst("a.authorName") ?: first.selectFirst("a.storyAuthor"))?.text()?.trim() ?: ""
        val lastPage = first.select("a[href]").mapNotNull { a ->
            Regex("""/story/${ref.workId}\?p=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 1

        val chapters = mutableListOf<FetchedChapter>()
        var section: String? = null
        fun parse(doc: org.jsoup.nodes.Document) {
            val list = doc.selectFirst("div.episodeList") ?: return
            for (item in list.select(".episodeListItem")) {
                if (item.hasClass("chapter")) { section = item.text().trim(); continue }
                val a = item.selectFirst("a.episodeTitle") ?: continue
                val href = a.absUrl("href").ifEmpty { a.attr("href") }
                val id = Regex("""/story/\d+/(\d+)""").find(href)?.groupValues?.get(1) ?: continue
                chapters += FetchedChapter(id, a.text().trim(), "https://novelup.plus/story/${ref.workId}/$id", section, item.selectFirst(".publishDate")?.text())
            }
        }
        parse(first)
        for (p in 2..lastPage) {
            delay(300)
            val url = "${ref.workUrl}?p=$p"
            parse(Jsoup.parse(http.get(url), url))
        }
        if (chapters.isEmpty()) throw IllegalStateException("公開されている話がありません")
        return FetchedWork(SITE, ref.workId, title, author, "", ref.workUrl, chapters)
    }

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val doc = Jsoup.parse(http.get(chapter.url), chapter.url)
        val body = doc.selectFirst("#episode_content")
            ?: throw IllegalStateException("本文が見つかりません（ページの構造が変わった可能性があります）")
        val blocks = mutableListOf<ContentBlock>()
        HtmlConverter.convertLines(body, blocks)
        return NovelDocument(ref.workId, chapter.chapterId, chapter.title, blocks)
    }
}

// ---------------------------------------------------------------- アルファポリス

/**
 * アルファポリス（www.alphapolis.co.jp）。
 * ページの構造（2026年11月時点で確認）：
 *   作品ページ https://www.alphapolis.co.jp/novel/{作者番号}/{作品番号}
 *     目次は script#app-cover-data の JSON：content.title・content.user.name、
 *     chapterEpisodes[]（title = 章の名前。空なら章なし）の episodes[]（episodeNo・mainTitle・url・isPublic）
 *     書籍化でレンタルになった話（isPublic = false）は読めないので目次に入れない
 *   話のページ …/episode/{話番号}：本文 #novelBody（br で改行した文）
 */
class AlphapolisAdapter(private val http: SiteHttp) : SiteAdapter {

    companion object {
        const val SITE = "alphapolis"
        private val WORK_URL = Regex("""^https?://www\.alphapolis\.co\.jp/novel/(\d+)/(\d+)(?:[/?#].*)?$""")
    }

    override val siteIds = setOf(SITE)

    override fun parseUrl(url: String): WorkRef? =
        WORK_URL.find(url)?.let { refOf(SITE, "${it.groupValues[1]}-${it.groupValues[2]}") }

    override fun refOf(siteId: String, workId: String): WorkRef? {
        if (siteId != SITE) return null
        val (user, work) = workId.split("-").takeIf { it.size == 2 } ?: return null
        return WorkRef(SITE, workId, "https://www.alphapolis.co.jp/novel/$user/$work")
    }

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val doc = Jsoup.parse(http.get(ref.workUrl), ref.workUrl)
        val data = doc.selectFirst("script#app-cover-data")?.data()
            ?: throw IllegalStateException("目次のデータが見つかりません（ページの構造が変わった可能性があります）")
        val root = looseJson.parseToJsonElement(data)
        val title = root.o("content").o("title").s() ?: throw IllegalStateException("作品名が見つかりません")
        val author = root.o("content").o("user").o("name").s() ?: ""
        val chapters = mutableListOf<FetchedChapter>()
        for (ch in (root.o("chapterEpisodes") as? JsonArray).orEmpty()) {
            val section = ch.o("title").s()?.trim()?.ifEmpty { null }
            for (ep in (ch.o("episodes") as? JsonArray).orEmpty()) {
                if (ep.o("isPublic").b() == false) continue
                val no = ep.o("episodeNo").s() ?: continue
                val url = ep.o("url").s()?.let { if (it.startsWith("http")) it else "https://www.alphapolis.co.jp$it" }
                    ?: "${ref.workUrl}/episode/$no"
                chapters += FetchedChapter(no, ep.o("mainTitle").s() ?: "第${chapters.size + 1}話", url, section, ep.o("upTime").s())
            }
        }
        if (chapters.isEmpty()) throw IllegalStateException("公開されている話がありません")
        return FetchedWork(SITE, ref.workId, title, author, "", ref.workUrl, chapters)
    }

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val doc = Jsoup.parse(http.get(chapter.url), chapter.url)
        val body = doc.selectFirst("#novelBody")
            ?: throw IllegalStateException("本文が見つかりません。レンタルの話か、ページの構造が変わった可能性があります")
        val blocks = mutableListOf<ContentBlock>()
        HtmlConverter.convertSections(listOf(body), blocks)
        return NovelDocument(ref.workId, chapter.chapterId, chapter.title, blocks)
    }
}

// ---------------------------------------------------------------- pixiv

/**
 * pixiv の小説（www.pixiv.net）。シリーズは1作品、シリーズでない小説は1話だけの作品として扱う。
 * ページは JavaScript で作られるので、ページの裏で使われている JSON（ajax）を読む（2026年11月時点で確認）：
 *   /ajax/novel/series/{シリーズ番号} … title・userName
 *   /ajax/novel/series/{シリーズ番号}/content_titles … 話の一覧 [{id, title, available}]
 *   /ajax/novel/{小説番号} … title・userName・content（本文）・seriesNavData.seriesId
 * 本文の記法：[newpage]（改ページ）・[chapter:見出し]・[[rb:親文字 > よみ]]・[[jumpuri:文字 > URL]]・[pixivimage:…]
 * R-18 の作品は、アプリ内ブラウザで pixiv にログインし、R-18 の表示を有効にしたときだけ読める（回避はしない）
 */
class PixivAdapter(private val http: SiteHttp) : SiteAdapter {

    companion object {
        const val SITE = "pixiv"
        private val SERIES_URL = Regex("""^https?://www\.pixiv\.net/(?:en/)?novel/series/(\d+)""")
        private val NOVEL_URL = Regex("""^https?://www\.pixiv\.net/(?:en/)?novel/show\.php\?(?:.*&)?id=(\d+)""")
        private val HEADERS = mapOf("Referer" to "https://www.pixiv.net/", "Accept" to "application/json")
    }

    override val siteIds = setOf(SITE)

    /** workId は「s{シリーズ番号}」か「n{小説番号}」 */
    override fun parseUrl(url: String): WorkRef? {
        SERIES_URL.find(url)?.let { return refOf(SITE, "s${it.groupValues[1]}") }
        NOVEL_URL.find(url)?.let { return refOf(SITE, "n${it.groupValues[1]}") }
        return null
    }

    override fun refOf(siteId: String, workId: String): WorkRef? {
        if (siteId != SITE) return null
        val id = workId.drop(1)
        return when (workId.firstOrNull()) {
            's' -> WorkRef(SITE, workId, "https://www.pixiv.net/novel/series/$id")
            'n' -> WorkRef(SITE, workId, "https://www.pixiv.net/novel/show.php?id=$id")
            else -> null
        }
    }

    private fun novelUrl(id: String) = "https://www.pixiv.net/novel/show.php?id=$id"

    /** ajax の応答の body を返す。エラーならその理由で止める */
    private suspend fun api(path: String): JsonElement {
        val root = looseJson.parseToJsonElement(http.get("https://www.pixiv.net$path", HEADERS))
        if (root.o("error").b() == true) {
            val msg = root.o("message").s().orEmpty()
            throw IllegalStateException(
                "pixiv から読めませんでした" + (if (msg.isNotBlank()) "（$msg）" else "") +
                    "。R-18 や非公開の作品は、アプリ内ブラウザで pixiv にログインしてからお試しください"
            )
        }
        return root.o("body") ?: throw IllegalStateException("pixiv の応答が読めません")
    }

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val id = ref.workId.drop(1)
        if (ref.workId.startsWith("n")) {
            val novel = api("/ajax/novel/$id")
            // シリーズの1話なら、シリーズとして取り込む
            novel.o("seriesNavData").o("seriesId").s()?.let { sid -> return fetchWork(refOf(SITE, "s$sid")!!) }
            val title = novel.o("title").s() ?: "（無題）"
            return FetchedWork(
                SITE, ref.workId, title, novel.o("userName").s() ?: "", novel.o("description").s().orEmpty(), ref.workUrl,
                listOf(FetchedChapter(id, title, novelUrl(id), null, novel.o("uploadDate").s())),
            )
        }
        val series = api("/ajax/novel/series/$id")
        val titles = api("/ajax/novel/series/$id/content_titles") as? JsonArray ?: JsonArray(emptyList())
        val chapters = titles.mapNotNull { t ->
            if (t.o("available").b() == false) return@mapNotNull null
            val nid = t.o("id").s() ?: return@mapNotNull null
            FetchedChapter(nid, t.o("title").s() ?: "", novelUrl(nid), null, null)
        }
        if (chapters.isEmpty()) throw IllegalStateException("読める話がありません（R-18 の作品は、ログインと R-18 表示の設定が必要です）")
        return FetchedWork(
            SITE, ref.workId, series.o("title").s() ?: "（無題）", series.o("userName").s() ?: "",
            series.o("caption").s().orEmpty(), ref.workUrl, chapters,
        )
    }

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val novel = api("/ajax/novel/${chapter.chapterId}")
        val content = novel.o("content").s() ?: throw IllegalStateException("本文がありません")
        return NovelDocument(ref.workId, chapter.chapterId, novel.o("title").s() ?: chapter.title, convert(content))
    }

    /** pixiv の本文の記法を共通本文モデルにする。挿絵（pixiv の画像は直接読み込めない）は省く */
    private fun convert(content: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val inline = Regex("""\[\[rb:(.+?)\s*>\s*(.+?)]]|\[\[jumpuri:(.+?)\s*>\s*(.+?)]]|\[jump:\d+]|\[(?:pixivimage|uploadedimage):[^\]]*]""")
        for (raw in content.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            when {
                line.trim() == "[newpage]" -> blocks += ContentBlock.Divider()
                line.trim().startsWith("[chapter:") && line.trim().endsWith("]") -> {
                    blocks += ContentBlock.BlankLine
                    blocks += ContentBlock.Paragraph(listOf(TextSpan.Plain(line.trim().removePrefix("[chapter:").removeSuffix("]"))))
                    blocks += ContentBlock.BlankLine
                }
                line.isBlank() -> blocks += ContentBlock.BlankLine
                else -> {
                    val spans = mutableListOf<TextSpan>()
                    var pos = 0
                    for (m in inline.findAll(line)) {
                        if (m.range.first > pos) spans += TextSpan.Plain(line.substring(pos, m.range.first))
                        when {
                            m.groupValues[1].isNotEmpty() -> spans += TextSpan.Ruby(m.groupValues[1], m.groupValues[2])
                            m.groupValues[3].isNotEmpty() -> spans += TextSpan.Plain(m.groupValues[3])
                        }
                        pos = m.range.last + 1
                    }
                    if (pos < line.length) spans += TextSpan.Plain(line.substring(pos))
                    if (spans.any { it !is TextSpan.Plain || it.text.isNotBlank() }) blocks += ContentBlock.Paragraph(spans)
                }
            }
        }
        // 頭と終わりの空行・区切りは落とす
        while (blocks.firstOrNull().let { it is ContentBlock.BlankLine || it is ContentBlock.Divider }) blocks.removeAt(0)
        while (blocks.lastOrNull().let { it is ContentBlock.BlankLine || it is ContentBlock.Divider }) blocks.removeAt(blocks.lastIndex)
        return blocks
    }
}
