package com.example.wintreader

import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 小説家になろうグループ（なろう・ノクターン・ムーンライト・ミッドナイト）。
 * 全年齢は ncode.syosetu.com、R18 は novel18.syosetu.com に作品ページがある。
 * ページの構造（2026年11月時点で確認）：
 *   作品名 h1.p-novel__title / 作者 .p-novel__author a / あらすじ #novel_ex
 *   目次 .p-eplist の中に .p-eplist__chapter-title（章）と .p-eplist__sublist（話：a.p-eplist__subtitle, .p-eplist__update）
 *   目次は1ページ100話。.c-pager__item--last のリンクで最後のページ番号が分かる
 *   本文 .js-novel-text.p-novel__text の中に p#L1, p#L2 …（空行は中身が空の p）
 */
class NarouAdapter(private val http: SiteHttp) : SiteAdapter {

    companion object {
        const val SITE = "narou"
        const val SITE_R18 = "narou18"
        private val HOSTS = setOf("ncode.syosetu.com", "novel18.syosetu.com")
        private val WORK_URL = Regex("""^https?://(ncode|novel18)\.syosetu\.com/(n\d{4}[a-z]{1,3})(?:/.*)?$""", RegexOption.IGNORE_CASE)

        private fun hostOf(siteId: String) = if (siteId == SITE_R18) "novel18.syosetu.com" else "ncode.syosetu.com"
    }

    override val siteIds = setOf(SITE, SITE_R18)

    override fun parseUrl(url: String): WorkRef? {
        val m = WORK_URL.find(url.substringBefore('?').substringBefore('#')) ?: return null
        val host = "${m.groupValues[1].lowercase()}.syosetu.com"
        if (host !in HOSTS) return null
        return refOf(if (host.startsWith("novel18")) SITE_R18 else SITE, m.groupValues[2].lowercase())
    }

    override fun refOf(siteId: String, workId: String): WorkRef? =
        if (siteId in siteIds) WorkRef(siteId, workId, "https://${hostOf(siteId)}/$workId/") else null

    // ------------------------------------------------ 作品の情報と目次

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val first = Jsoup.parse(http.get(ref.workUrl), ref.workUrl)
        val title = first.selectFirst("h1.p-novel__title")?.text()?.trim()
            ?: throw IllegalStateException("作品名が見つかりません（ページの構造が変わった可能性があります）")
        val author = first.selectFirst(".p-novel__author a")?.text()?.trim()
            ?: first.selectFirst(".p-novel__author")?.text()?.removePrefix("作者：")?.trim()
            ?: ""
        val summary = first.selectFirst("#novel_ex")?.wholeText()?.trim() ?: ""

        val chapters = mutableListOf<FetchedChapter>()
        if (first.selectFirst(".p-eplist") == null) {
            // 短編：目次がなく、作品ページに本文がある
            chapters += FetchedChapter("1", title, ref.workUrl, null, null)
        } else {
            var section: String? = null
            section = parseTocPage(first, chapters, section)
            val lastPage = lastPageNumber(first)
            for (p in 2..lastPage) {
                delay(300) // SiteHttp の間隔に加えて、目次の連続取得は少し余計に空ける
                val url = "${ref.workUrl}?p=$p"
                section = parseTocPage(Jsoup.parse(http.get(url), url), chapters, section)
            }
        }
        return FetchedWork(ref.siteId, ref.workId, title, author, summary, ref.workUrl, chapters)
    }

    /** 目次の1ページ分を読む。章のタイトルはページをまたいで引き継ぐ */
    private fun parseTocPage(doc: Document, out: MutableList<FetchedChapter>, sectionIn: String?): String? {
        var section = sectionIn
        val list = doc.selectFirst(".p-eplist") ?: return section
        for (child in list.children()) {
            when {
                child.hasClass("p-eplist__chapter-title") -> section = child.text().trim()
                child.hasClass("p-eplist__sublist") -> {
                    val a = child.selectFirst("a.p-eplist__subtitle") ?: continue
                    val href = a.absUrl("href").ifEmpty { a.attr("href") }
                    // 話の番号は URL の最後の数字（タイトルの「第○話」とは一致しないことがある）
                    val number = Regex("""/(\d+)/?$""").find(href.substringBefore('?'))?.groupValues?.get(1) ?: continue
                    out += FetchedChapter(
                        chapterId = number,
                        title = a.text().trim(),
                        url = href,
                        sectionTitle = section,
                        updatedText = child.selectFirst(".p-eplist__update")?.ownText()?.trim(),
                    )
                }
            }
        }
        return section
    }

    private fun lastPageNumber(doc: Document): Int {
        val href = doc.selectFirst("a.c-pager__item--last")?.attr("href") ?: return 1
        return Regex("""[?&]p=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    // ------------------------------------------------ 更新確認（UPD-01）

    /**
     * なろうの公式API（なろう小説API / なろうR18小説API）で、作品の話数だけを軽く調べる。
     * 本棚の作品を開くたびに目次のページを読み込まずに済む。取れなければ null
     */
    override suspend fun fetchEpisodeCount(ref: WorkRef): Int? {
        val api = if (ref.siteId == SITE_R18) "novel18api" else "novelapi"
        val url = "https://api.syosetu.com/$api/api/?out=json&of=ga&ncode=${ref.workId}"
        return runCatching {
            // 応答は [{"allcount":1},{"general_all_no":286}] の形
            Regex(""""general_all_no"\s*:\s*(\d+)""").find(http.get(url))?.groupValues?.get(1)?.toInt()
        }.getOrNull()
    }

    // ------------------------------------------------ 本文

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val doc = Jsoup.parse(http.get(chapter.url), chapter.url)
        val texts = doc.select(".js-novel-text.p-novel__text")
        if (texts.isEmpty()) throw IllegalStateException("本文が見つかりません（ページの構造が変わった可能性があります）")

        // 前書き・本文・後書きの間に区切り線を入れる
        val blocks = mutableListOf<ContentBlock>()
        HtmlConverter.convertSections(texts.toList(), blocks)
        val title = doc.selectFirst("h1.p-novel__title")?.text()?.trim() ?: chapter.title
        return NovelDocument(workId = ref.workId, chapterId = chapter.chapterId, title = title, blocks = blocks)
    }
}
