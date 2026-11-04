package com.example.wintreader

import org.jsoup.Jsoup

/**
 * ハーメルン（syosetu.org。R-18 の作品は h.syosetu.org。ページの作りは全年齢と同じと想定）。
 * ページの構造（2026年11月時点で確認）：
 *   作品ページ https://syosetu.org/novel/{作品番号}/
 *     #maind の div.ss の1つ目に 作品名 span[itemprop=name]・作者 span[itemprop=author]、2つ目があらすじ
 *     目次 .episode-list__items の中に li.episode-list__chapter（.episode-list__chapter-title が章）と
 *       li.episode-list__item（a.episode-list__link の href が ./{話番号}.html、.episode-list__title が話のタイトル）
 *     スマホ向けのページ（アプリの User-Agent）では、目次は ul.entry の中に li（strong が章）と
 *       li.episode-list__item（a の href が ./{話番号}.html、span#sio{話番号} が話のタイトル）。あらすじは取りにくいので空にする
 *     全話が1ページに入っている。一話だけの作品（短編）は目次がなく、作品ページに本文 #honbun がある
 *   話のページ https://syosetu.org/novel/{作品番号}/{話番号}.html
 *     前書き #maegaki・本文 #honbun・後書き #atogaki。本文は p#0, p#1 …（全角空白だけの p は空行）と hr
 * 年齢確認が必要な作品は、アプリ内ブラウザで確認を済ませてから取り込む（確認を回避する処理はしない）。
 */
class HamelnAdapter(private val http: SiteHttp) : SiteAdapter {

    companion object {
        const val SITE = "hameln"
        /** R-18 の作品（h.syosetu.org） */
        const val SITE_R18 = "hameln18"
        private val WORK_URL = Regex("""^https?://(h\.)?syosetu\.org/novel/(\d+)(?:/.*)?$""")

        private fun hostOf(siteId: String) = if (siteId == SITE_R18) "h.syosetu.org" else "syosetu.org"
    }

    override val siteIds = setOf(SITE, SITE_R18)

    override fun parseUrl(url: String): WorkRef? {
        val m = WORK_URL.find(url.substringBefore('?').substringBefore('#')) ?: return null
        return refOf(if (m.groupValues[1].isNotEmpty()) SITE_R18 else SITE, m.groupValues[2])
    }

    override fun refOf(siteId: String, workId: String): WorkRef? =
        if (siteId in siteIds) WorkRef(siteId, workId, "https://${hostOf(siteId)}/novel/$workId/") else null

    // ------------------------------------------------ 作品の情報と目次

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val doc = Jsoup.parse(http.get(ref.workUrl), ref.workUrl)
        // 作者の欄の中にも span[itemprop=name] があるので、作者の欄の外のものを使う。
        // 一話だけの短編のページには作品名の欄がないので、ページのタイトル（「作品名 - 話のタイトル - ハーメルン」）から取る
        val title = doc.select("span[itemprop=name]")
            .firstOrNull { el -> el.parents().none { it.attr("itemprop") == "author" } }
            ?.text()?.trim()?.ifEmpty { null }
            ?: titleFromPageTitle(doc)
            ?: throw IllegalStateException(
                "作品名が見つかりません。年齢確認が必要な作品は、アプリ内ブラウザで作品ページを開いて確認を済ませてから追加してください"
            )
        // 作者：作者の欄、なければ「作：」の後ろの作者ページへのリンク
        val author = doc.selectFirst("span[itemprop=author]")?.text()?.trim()?.ifEmpty { null }
            ?: doc.select("a[href*=/user/]").firstOrNull { a ->
                (a.previousSibling() as? org.jsoup.nodes.TextNode)?.text()?.trimEnd()?.endsWith("作：") == true
            }?.text()?.trim()
            ?: ""
        val summary = doc.select("#maind > div.ss").getOrNull(1)?.wholeText()?.trim() ?: "" // PC 向けのページのみ

        val chapters = mutableListOf<FetchedChapter>()
        // 目次：PC 向けは .episode-list__items、スマホ向け（アプリの User-Agent ではこちらが返る）は ul.entry
        val list = doc.selectFirst(".episode-list__items")
            ?: doc.select("ul.entry").firstOrNull { it.selectFirst("li.episode-list__item") != null }
        if (list == null) {
            // 一話だけの作品：作品ページそのものが本文
            if (doc.selectFirst("#honbun") == null) throw IllegalStateException("目次が見つかりません（ページの構造が変わった可能性があります）")
            chapters += FetchedChapter("1", title, ref.workUrl, null, null)
        } else {
            var section: String? = null
            for (li in list.children()) {
                val a = li.selectFirst("a[href]")
                val href = a?.let { it.absUrl("href").ifEmpty { it.attr("href") } }
                val number = href?.let { Regex("""(?:^|/)(\d+)\.html""").find(it)?.groupValues?.get(1) }
                when {
                    // 章の見出し（PC：.episode-list__chapter-title、スマホ：リンクのない li の strong）
                    li.hasClass("episode-list__chapter") ->
                        section = li.selectFirst(".episode-list__chapter-title")?.text()?.trim()
                    number == null -> li.selectFirst("strong")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { section = it }
                    else -> {
                        // 話のタイトル（PC：.episode-list__title、スマホ：span#sio{番号}）
                        val title2 = li.selectFirst(".episode-list__title")?.text()?.trim()
                            ?: li.selectFirst("span[id^=sio]")?.text()?.trim()
                            ?: a?.ownText()?.trim()
                        chapters += FetchedChapter(
                            chapterId = number,
                            title = title2?.takeIf { it.isNotEmpty() } ?: "第${number}話",
                            url = "${ref.workUrl}$number.html",
                            sectionTitle = section,
                            updatedText = li.selectFirst("time")?.attr("datetime"),
                        )
                    }
                }
            }
        }
        if (chapters.isEmpty()) throw IllegalStateException("公開されている話がありません")
        return FetchedWork(ref.siteId, ref.workId, title, author, summary, ref.workUrl, chapters)
    }

    /** ページのタイトル（og:title か title）「作品名 - 話のタイトル - ハーメルン」から作品名を取る */
    private fun titleFromPageTitle(doc: org.jsoup.nodes.Document): String? {
        val raw = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null } ?: doc.title()
        val parts = raw.trim().split(" - ").toMutableList()
        if (parts.lastOrNull()?.trim()?.startsWith("ハーメルン") == true) parts.removeAt(parts.lastIndex)
        // 残りが2つ以上なら、最後は話のタイトル（短編では空白だけ）
        if (parts.size >= 2) parts.removeAt(parts.lastIndex)
        val name = parts.joinToString(" - ").trim()
        return name.ifEmpty { null }
    }

    // ------------------------------------------------ 本文

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val doc = Jsoup.parse(http.get(chapter.url), chapter.url)
        val honbun = doc.selectFirst("#honbun")
            ?: throw IllegalStateException(
                "本文が見つかりません。年齢確認が必要な作品は、アプリ内ブラウザで確認を済ませてください"
            )
        // 前書き・本文・後書きの間に区切り線を入れる（前書き・後書きは br 区切りの文で、頭と終わりに空行と hr がある）
        val blocks = mutableListOf<ContentBlock>()
        HtmlConverter.convertSections(listOfNotNull(doc.selectFirst("#maegaki"), honbun, doc.selectFirst("#atogaki")), blocks)
        return NovelDocument(workId = ref.workId, chapterId = chapter.chapterId, title = chapter.title, blocks = blocks)
    }
}
