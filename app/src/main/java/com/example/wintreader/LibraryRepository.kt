package com.example.wintreader

import kotlinx.coroutines.flow.Flow

/**
 * 本棚・目次・栞・本文をまとめて扱う窓口。
 * 画面はデータベースやファイルを直接触らず、ここを通す。
 */
/** 元サイトの目次から消えた話のタイトルの頭に付ける印 */
const val REMOVED_MARK = "［元サイトで削除］"

class LibraryRepository(
    private val db: AppDatabase,
    private val chapterStore: ChapterStore,
    private val sites: SiteRegistry,
) {
    // ------------------------------------------------ 本棚

    fun observeNovels(): Flow<List<NovelEntity>> = db.novels().observeAll()
    fun observeCategories(): Flow<List<CategoryEntity>> = db.categories().observeAll()
    /** 作品ごとの「何話まで読んだか」「未読の話数」（LIB-09） */
    fun observeChapterStats(): Flow<List<ChapterStats>> = db.chapters().observeStats()

    suspend fun getNovel(id: String): NovelEntity? = db.novels().get(id)

    /** 作品の削除（LIB-39）：本棚・目次・栞・保存した本文をまとめて消す。カテゴリは残す（LIB-41） */
    suspend fun deleteNovel(id: String) {
        db.bookmarks().deleteForNovel(id)
        db.chapters().deleteForNovel(id)
        db.novels().delete(id)
        chapterStore.deleteNovel(id)
    }

    suspend fun setCategory(novelId: String, categoryId: Long?) = db.novels().setCategory(novelId, categoryId)

    // ------------------------------------------------ カテゴリ

    suspend fun addCategory(name: String): Long {
        val order = (db.categories().list().maxOfOrNull { it.displayOrder } ?: -1) + 1
        return db.categories().insert(CategoryEntity(name = name, displayOrder = order))
    }

    suspend fun updateCategory(category: CategoryEntity) = db.categories().update(category)

    /** カテゴリを消す。中の作品は未分類に戻す */
    suspend fun deleteCategory(id: Long) {
        db.novels().clearCategory(id)
        db.categories().delete(id)
    }

    /** 手動の並び替え結果を保存する（LIB-31, 34） */
    suspend fun reorderCategories(ordered: List<CategoryEntity>) {
        db.categories().updateAll(ordered.mapIndexed { i, c -> c.copy(displayOrder = i) })
    }

    // ------------------------------------------------ 目次・本文

    fun observeChapters(novelId: String): Flow<List<ChapterEntity>> = db.chapters().observe(novelId)
    suspend fun listChapters(novelId: String): List<ChapterEntity> = db.chapters().list(novelId)

    /** 保存済みの本文を読む。まだ取得していなければ null（取得は M3 で実装） */
    suspend fun loadDocument(novelId: String, chapterId: String): NovelDocument? =
        chapterStore.load(novelId, chapterId)

    suspend fun saveDocument(novelId: String, doc: NovelDocument) {
        chapterStore.save(novelId, doc)
        db.chapters().setCached(novelId, doc.chapterId, true)
    }

    suspend fun markRead(novelId: String, chapterId: String) = db.chapters().markRead(novelId, chapterId)

    // ------------------------------------------------ サイトからの取り込み（M3）

    /**
     * サイトから作品の情報と目次を取って本棚に入れる（WEB-12 / TOC-01）。
     * 本文はまだ取らない（開いたときに取る）。すでに本棚にある作品なら目次を更新する。
     */
    suspend fun addFetchedWork(work: FetchedWork): String {
        val novelId = NovelEntity.idOf(work.siteId, work.workId)
        val existing = db.novels().get(novelId)
        val chapters = work.chapters.mapIndexed { i, c ->
            ChapterEntity(
                id = "$novelId/${c.chapterId}",
                novelId = novelId,
                chapterId = c.chapterId,
                title = c.title,
                url = c.url,
                order = i,
                sectionTitle = c.sectionTitle,
            )
        }
        // 既読・保存済みの印は引き継ぐ（TOC-07）
        val old = db.chapters().list(novelId).associateBy { it.chapterId }
        db.chapters().insertAll(chapters.map { c ->
            old[c.chapterId]?.let { o -> c.copy(isRead = o.isRead, cached = o.cached) } ?: c
        })
        // 元サイトの目次から消えた話（UPD-07）：保存済みなら印を付けて最後に残し、そうでなければ消す
        val newIds = chapters.map { it.chapterId }.toSet()
        val removed = old.values.filter { it.chapterId !in newIds }
        db.chapters().deleteByIds(removed.filter { !it.cached }.map { it.id })
        db.chapters().insertAll(removed.filter { it.cached }.mapIndexed { i, c ->
            c.copy(
                order = chapters.size + i,
                title = if (c.title.startsWith(REMOVED_MARK)) c.title else REMOVED_MARK + c.title,
            )
        })
        val now = System.currentTimeMillis()
        db.novels().upsert(
            (existing ?: NovelEntity(
                id = novelId,
                siteId = work.siteId,
                workId = work.workId,
                title = work.title,
                author = work.author,
                sourceUrl = work.url,
                addedAt = now,
            )).copy(
                title = work.title,
                author = work.author,
                latestChapterTitle = chapters.lastOrNull()?.title,
                updatedAt = if (existing == null || chapters.size != old.size) now else existing.updatedAt,
            )
        )
        return novelId
    }

    /** 目次の話の数（元サイトで消えた話は数えない） */
    private suspend fun liveCount(novelId: String) =
        db.chapters().list(novelId).count { !it.title.startsWith(REMOVED_MARK) }

    /**
     * 作品の更新を確かめる（UPD-01〜05）。増えた話の数を返す（このサイトでは確かめられなければ null）。
     * 話数だけを軽く調べる方法があるサイト（なろうの公式API）は、変わっていたときだけ目次を取り直す。
     * ないサイト（カクヨム・ハーメルンは目次が1ページなので、取り直しても1回の通信）は目次を取り直して比べる。
     * 読書位置は変えない
     */
    suspend fun checkForUpdates(novelId: String): Int? {
        val novel = db.novels().get(novelId) ?: return null
        val adapter = sites.adapterFor(novel.siteId)?.takeIf { it.canCheckUpdates } ?: return null
        val ref = adapter.refOf(novel.siteId, novel.workId) ?: return null
        val remote = adapter.fetchEpisodeCount(ref)
        val local = liveCount(novelId)
        if (remote != null) {
            if (remote == local) return 0
            addWork(ref)
            return remote - local
        }
        addWork(ref)
        return liveCount(novelId) - local
    }

    /** 目次を取り直す（目次画面の「更新確認」・話の端の更新確認から。LIB-15） */
    suspend fun refreshToc(novelId: String): Int? {
        val novel = db.novels().get(novelId) ?: return null
        if (sites.adapterFor(novel.siteId)?.canCheckUpdates != true) return null
        val ref = sites.refOf(novel.siteId, novel.workId) ?: return null
        val before = liveCount(novelId)
        addWork(ref)
        return liveCount(novelId) - before
    }

    /** サイトの作品を取り込む（すでに本棚にあれば目次を更新する） */
    suspend fun addWork(ref: WorkRef): String {
        val adapter = sites.adapterFor(ref.siteId) ?: throw IllegalStateException("このサイトには対応していません")
        return addFetchedWork(adapter.fetchWork(ref))
    }

    /**
     * 話の本文を返す。保存済みならそれを、なければサイトから取って保存する（CACHE-01, 03）。
     * 取れなかったときは例外を投げる
     */
    suspend fun getDocument(novelId: String, chapterId: String): NovelDocument {
        loadDocument(novelId, chapterId)?.let { return it }
        val novel = db.novels().get(novelId) ?: throw IllegalStateException("作品が見つかりません")
        val chapter = db.chapters().list(novelId).firstOrNull { it.chapterId == chapterId }
            ?: throw IllegalStateException("目次にない話です")
        val adapter = sites.adapterFor(novel.siteId)
            ?: throw IllegalStateException("このサイトの本文取得には対応していません")
        val ref = adapter.refOf(novel.siteId, novel.workId) ?: throw IllegalStateException("作品を指せません")
        val doc = adapter.fetchChapter(ref, chapter)
        saveDocument(novelId, doc)
        return doc
    }

    /**
     * 全話を保存する（CACHE-04 / 10 / 13 / 15）。保存済みの話は飛ばすので、止めたあとにもう一度呼べば続きから再開できる。
     * サイトに負担をかけないよう、1話ごとに間を空ける。混雑・制限・確認が必要と言われたら止める（SiteBusyException などを投げる）。
     * 取れなかった話の数を返す（もう一度実行すると、その話だけ取り直す）
     */
    suspend fun downloadAll(novelId: String, onProgress: (done: Int, total: Int) -> Unit): Int {
        val targets = db.chapters().list(novelId).filter { !it.title.startsWith(REMOVED_MARK) }
        val total = targets.size
        var done = targets.count { it.cached }
        var failed = 0
        onProgress(done, total)
        for (c in targets) {
            if (c.cached) continue
            try {
                getDocument(novelId, c.chapterId)
                done++
            } catch (e: SiteBusyException) {
                throw e
            } catch (e: SiteChallengeException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
            onProgress(done, total)
            kotlinx.coroutines.delay(1000)
        }
        return failed
    }

    /** 次の数話を先に取っておく（CACHE-02）。失敗しても読書は続けられるので、ここでは無視する */
    suspend fun prefetch(novelId: String, afterChapterId: String, count: Int = 2) {
        val list = db.chapters().list(novelId)
        val index = list.indexOfFirst { it.chapterId == afterChapterId }
        if (index < 0) return
        for (c in list.drop(index + 1).take(count)) {
            if (c.cached) continue
            try {
                getDocument(novelId, c.chapterId)
            } catch (e: SiteBusyException) {
                return // 混雑・制限中はそれ以上取りに行かない
            } catch (e: Exception) {
                return
            }
        }
    }

    // ------------------------------------------------ 読書位置・栞

    suspend fun savePosition(novelId: String, chapterId: String, pos: ReadingPosition) {
        db.novels().updatePosition(novelId, chapterId, pos.block, pos.offset, System.currentTimeMillis())
    }

    fun observeBookmarks(novelId: String): Flow<List<BookmarkEntity>> = db.bookmarks().observe(novelId)

    suspend fun addBookmark(novelId: String, chapterId: String, pos: ReadingPosition, excerpt: String): Long =
        db.bookmarks().insert(
            BookmarkEntity(
                novelId = novelId,
                chapterId = chapterId,
                block = pos.block,
                offset = pos.offset,
                excerpt = excerpt,
                createdAt = System.currentTimeMillis(),
            )
        )

    suspend fun deleteBookmark(id: Long) = db.bookmarks().delete(id)

    // ------------------------------------------------ バックアップ・データの管理（BAK-01〜02 / SET-07）

    /** 本棚・カテゴリ・目次（既読の印）・栞・読書位置を JSON にする。本文は含めない */
    suspend fun exportBackup(): String {
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            novels = db.novels().listAll(),
            categories = db.categories().list(),
            chapters = db.chapters().listAll().map { it.copy(cached = false) },
            bookmarks = db.bookmarks().listAll(),
        )
        return BackupJson.encodeToString(BackupData.serializer(), data)
    }

    /**
     * バックアップから復元する。今の本棚・カテゴリ・目次・栞は、バックアップの内容に置き換える。
     * 保存済みの本文はそのまま残し、同じ話なら引き続き使う。復元した作品の数を返す
     */
    suspend fun importBackup(json: String): Int {
        val data = BackupJson.decodeFromString(BackupData.serializer(), json)
        if (data.format != BackupData.FORMAT) throw IllegalArgumentException("WintReader のバックアップファイルではありません")
        // 先に読めることを確かめてから消す
        db.bookmarks().deleteAll()
        db.chapters().deleteAll()
        db.novels().deleteAll()
        db.categories().deleteAll()
        db.categories().insertAll(data.categories)
        db.novels().upsertAll(data.novels)
        // 本文が端末に残っている話は「保存済み」に戻す
        val chapters = data.chapters.map { c -> c.copy(cached = chapterStore.load(c.novelId, c.chapterId) != null) }
        db.chapters().insertAll(chapters)
        db.bookmarks().insertAll(data.bookmarks)
        return data.novels.size
    }

    suspend fun savedBytes(): Long = chapterStore.totalBytes()

    /** 保存した本文だけを消す（本棚・目次・栞は残す。開いたときに取り直す） */
    suspend fun deleteSavedDocuments() {
        chapterStore.deleteAll()
        db.chapters().clearAllCached()
    }

    /** 本棚・カテゴリ・目次・栞・保存した本文をすべて消す（SET-07） */
    suspend fun deleteAllData() {
        db.bookmarks().deleteAll()
        db.chapters().deleteAll()
        db.novels().deleteAll()
        db.categories().deleteAll()
        chapterStore.deleteAll()
    }

    // ------------------------------------------------ 既読・未読の一括管理

    /** 既読・未読の付け方 */
    enum class ReadMark { THIS_READ, THIS_UNREAD, UP_TO_READ, FROM_UNREAD, ALL_READ, ALL_UNREAD }

    /** 既読・未読をまとめて付け替える。chapter は「この話」「ここまで」「ここから」の基準（すべてのときは null でよい） */
    suspend fun setReadMark(novelId: String, chapter: ChapterEntity?, mark: ReadMark) {
        val dao = db.chapters()
        when (mark) {
            ReadMark.THIS_READ -> chapter?.let { dao.setRead(novelId, it.chapterId, true) }
            ReadMark.THIS_UNREAD -> chapter?.let { dao.setRead(novelId, it.chapterId, false) }
            ReadMark.UP_TO_READ -> chapter?.let { dao.setReadUpTo(novelId, it.order, true) }
            ReadMark.FROM_UNREAD -> chapter?.let { dao.setReadFrom(novelId, it.order, false) }
            ReadMark.ALL_READ -> dao.setReadAll(novelId, true)
            ReadMark.ALL_UNREAD -> dao.setReadAll(novelId, false)
        }
    }

    // ------------------------------------------------ サンプル作品の片付け

    /**
     * 開発中に入れていたサンプル作品と、そのとき作ったカテゴリ（ファンタジー・日常）を消す。
     * カテゴリは、中に作品が残っていないときだけ消す（自分で作品を入れていたら残す）
     */
    suspend fun removeSampleData() {
        db.novels().listAll().filter { it.siteId == "sample" }.forEach { deleteNovel(it.id) }
        val used = db.novels().listAll().mapNotNull { it.categoryId }.toSet()
        db.categories().list()
            .filter { it.name in setOf("ファンタジー", "日常") && it.id !in used }
            .forEach { db.categories().delete(it.id) }
    }
}
