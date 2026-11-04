package com.example.wintreader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup

/**
 * カクヨム（kakuyomu.jp）。
 * ページの構造（2026年11月時点で確認）：
 *   作品ページ https://kakuyomu.jp/works/{作品ID}
 *     目次は HTML ではなく script#__NEXT_DATA__ の JSON（props.pageProps.__APOLLO_STATE__）にある。
 *     "Work:{作品ID}" … title, introduction, author → "UserAccount:…".activityName,
 *       tableOfContentsV2 → "TableOfContentsChapter:…"（episodeUnions → "Episode:…" の並び、chapter → "Chapter:…" か null）
 *     "Chapter:…" … title, level（1 = 章、2 = 節）／ "Episode:…" … id, title
 *     全話が1ページに入っている（ページ分けなし）
 *   話のページ https://kakuyomu.jp/works/{作品ID}/episodes/{話ID}
 *     話のタイトル .widget-episodeTitle / 本文 .widget-episodeBody の中に p#p1, p#p2 …（空行は p.blank）
 *     傍点は em.emphasisDots
 */
class KakuyomuAdapter(private val http: SiteHttp) : SiteAdapter {

    companion object {
        const val SITE = "kakuyomu"
        private val WORK_URL = Regex("""^https?://kakuyomu\.jp/works/(\d+)(?:/.*)?$""")
        private val json = Json { ignoreUnknownKeys = true }
    }

    override val siteIds = setOf(SITE)

    override fun parseUrl(url: String): WorkRef? {
        val m = WORK_URL.find(url.substringBefore('?').substringBefore('#')) ?: return null
        return refOf(SITE, m.groupValues[1])
    }

    override fun refOf(siteId: String, workId: String): WorkRef? =
        if (siteId == SITE) WorkRef(SITE, workId, "https://kakuyomu.jp/works/$workId") else null

    // ------------------------------------------------ 作品の情報と目次

    override suspend fun fetchWork(ref: WorkRef): FetchedWork {
        val doc = Jsoup.parse(http.get(ref.workUrl), ref.workUrl)
        val data = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw IllegalStateException("目次のデータが見つかりません（ページの構造が変わった可能性があります）")
        val state = json.parseToJsonElement(data).obj("props")?.obj("pageProps")?.obj("__APOLLO_STATE__")
            ?: throw IllegalStateException("目次のデータを読めません（ページの構造が変わった可能性があります）")

        fun deref(e: JsonElement?): JsonObject? {
            val key = (e as? JsonObject)?.str("__ref") ?: return null
            return state[key] as? JsonObject
        }

        val work = state["Work:${ref.workId}"] as? JsonObject
            ?: throw IllegalStateException("作品が見つかりません（非公開になったか、ページの構造が変わった可能性があります）")
        val title = work.str("title") ?: throw IllegalStateException("作品名が見つかりません")
        val author = work.str("alternateAuthorName") ?: deref(work["author"])?.str("activityName") ?: ""
        val summary = listOfNotNull(work.str("catchphrase"), work.str("introduction")).joinToString("\n\n")

        val chapters = mutableListOf<FetchedChapter>()
        var chapterTitle: String? = null // 章（level 1）
        val toc = work["tableOfContentsV2"] as? JsonArray ?: JsonArray(emptyList())
        for (entry in toc) {
            val tc = deref(entry) ?: continue
            // 章・節の見出し。節（level 2）は「章　節」とまとめて見せる
            val section = deref(tc["chapter"])?.let { ch ->
                val t = ch.str("title") ?: return@let null
                val level = (ch["level"] as? JsonPrimitive)?.intOrNull ?: 1
                if (level <= 1) { chapterTitle = t; t } else listOfNotNull(chapterTitle, t).joinToString("　")
            }
            val episodes = tc["episodeUnions"] as? JsonArray ?: continue
            for (e in episodes) {
                val ep = deref(e) ?: continue
                val id = ep.str("id") ?: continue
                chapters += FetchedChapter(
                    chapterId = id,
                    title = ep.str("title") ?: "",
                    url = "https://kakuyomu.jp/works/${ref.workId}/episodes/$id",
                    sectionTitle = section,
                    updatedText = ep.str("publishedAt"),
                )
            }
        }
        if (chapters.isEmpty()) throw IllegalStateException("公開されている話がありません")
        return FetchedWork(SITE, ref.workId, title, author, summary, ref.workUrl, chapters)
    }

    // ------------------------------------------------ 本文

    override suspend fun fetchChapter(ref: WorkRef, chapter: ChapterEntity): NovelDocument {
        val doc = Jsoup.parse(http.get(chapter.url), chapter.url)
        val body = doc.selectFirst(".widget-episodeBody")
            ?: throw IllegalStateException("本文が見つかりません（ページの構造が変わった可能性があります）")
        val blocks = mutableListOf<ContentBlock>()
        HtmlConverter.convertBody(body, blocks)
        val title = doc.selectFirst(".widget-episodeTitle")?.text()?.trim() ?: chapter.title
        return NovelDocument(workId = ref.workId, chapterId = chapter.chapterId, title = title, blocks = blocks)
    }
}

private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

private fun JsonObject.str(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull || e !is JsonPrimitive) return null
    return e.jsonPrimitive.content
}
