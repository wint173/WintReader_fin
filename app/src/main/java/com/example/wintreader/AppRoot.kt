package com.example.wintreader

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 下部のタブ（本棚・文庫・ヘルプ） */
private enum class Tab(val label: String) { LIBRARY("本棚"), BUNKO("文庫"), HELP("ヘルプ") }

/**
 * アプリ全体の画面の切り替え。
 * ライブラリ（Navigation Compose）は使わず、「今どのタブか」「リーダーで開いている作品はどれか」だけを持つ。
 */
@Composable
fun AppRoot() {
    val container = LocalContext.current.appContainer
    var tab by rememberSaveable { mutableIntStateOf(Tab.LIBRARY.ordinal) }
    var readingNovelId by rememberSaveable { mutableStateOf<String?>(null) }
    // 文庫から開いたサイトのページ（null = 開いていない）
    var browsingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // ヘルプタブの右上から開く設定画面
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // サンプル作品は初めて起動したときだけ入れる（消したあとにまた入らないように）
        if (!container.settingsStore.sampleInserted()) {
            container.repository.ensureSampleData()
            container.settingsStore.markSampleInserted()
        }
        container.settingsStore.markUsedToday()
        ready = true
    }

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val novelId = readingNovelId
    if (novelId != null) {
        ReaderRoute(novelId = novelId, onClose = { readingNovelId = null }, onOpenNovel = { readingNovelId = it })
        return
    }

    if (showSettings) {
        SettingsScreen(onClose = { showSettings = false })
        return
    }

    val url = browsingUrl
    if (url != null) {
        BrowserScreen(
            startUrl = url,
            onClose = { browsingUrl = null },
            onUrlChanged = { browsingUrl = it },
            onOpenNovel = { readingNovelId = it },
            onBackToLibrary = {
                browsingUrl = null
                tab = Tab.LIBRARY.ordinal
            },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = {},
                        label = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            when (Tab.entries[tab]) {
                Tab.LIBRARY -> LibraryScreen(onOpenNovel = { readingNovelId = it })
                Tab.BUNKO -> BunkoScreen(onOpenSite = { browsingUrl = it })
                Tab.HELP -> HelpScreen(onOpenSettings = { showSettings = true })
            }
        }
    }
}

/** リーダーで開いている話 */
private data class OpenedChapter(
    val chapterId: String,
    val document: NovelDocument,
    /** 開くときの位置（null = 話の先頭） */
    val startPosition: ReadingPosition?,
    /** 切り替えの向き（1 = 次の話、-1 = 前の話、0 = その他） */
    val direction: Int = 0,
)

/**
 * 作品を読む画面（リーダーと目次・栞の画面を含む）。
 * 本棚からは前回の話の前回の位置で開く（READ-35）。読書中だけシステムバーを隠す（READ-27）。
 */
@Composable
private fun ReaderRoute(novelId: String, onClose: () -> Unit, onOpenNovel: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val repo = container.repository
    val scope = rememberCoroutineScope()

    var novel by remember(novelId) { mutableStateOf<NovelEntity?>(null) }
    var prefs by remember { mutableStateOf<ReaderPrefs?>(null) }
    var opened by remember(novelId) { mutableStateOf<OpenedChapter?>(null) }
    var error by remember(novelId) { mutableStateOf<String?>(null) }
    var showToc by rememberSaveable { mutableStateOf(false) }
    // 最後に確定した読書位置（目次から戻ったときに同じ所から再開するため）
    var lastPosition by remember(novelId) { mutableStateOf<ReadingPosition?>(null) }
    // リーダーの中で開いたサイトのページ（ルーペ・作品のページ）。null = 開いていない
    var webUrl by rememberSaveable(novelId) { mutableStateOf<String?>(null) }
    // 話の端に出す知らせ
    var notice by remember(novelId) { mutableStateOf<ReaderNotice?>(null) }
    var edgeBusy by remember(novelId) { mutableStateOf(false) }
    // 全話の保存（CACHE-04）。作品を閉じると止まる（保存済みの話は残り、次は続きから）
    var download by remember(novelId) { mutableStateOf<DownloadProgress?>(null) }
    var downloadJob by remember(novelId) { mutableStateOf<Job?>(null) }

    // Flow は remember しておく（毎回作り直すと、そのたびに読み込み直しになる）
    val chaptersFlow = remember(novelId) { repo.observeChapters(novelId) }
    val bookmarksFlow = remember(novelId) { repo.observeBookmarks(novelId) }
    val chapters by chaptersFlow.collectAsState(initial = emptyList())
    val bookmarks by bookmarksFlow.collectAsState(initial = emptyList())

    // 話を開く。position = null なら先頭から
    suspend fun open(chapterId: String, position: ReadingPosition?, direction: Int = 0) {
        // 保存済みならそれを、なければサイトから取る
        val doc = try {
            repo.getDocument(novelId, chapterId)
        } catch (e: Exception) {
            val message = "本文を取得できませんでした（${e.message}）"
            if (opened == null) error = message
            else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        error = null
        opened = OpenedChapter(chapterId, doc, position, direction)
        lastPosition = position
        repo.savePosition(novelId, chapterId, position ?: ReadingPosition())
        // 次の2話を先に取っておく（CACHE-02）
        scope.launch {
            val count = container.settingsStore.appPrefs.first().prefetchCount
            if (count > 0) repo.prefetch(novelId, chapterId, count)
        }
    }

    LaunchedEffect(novelId) {
        prefs = container.settingsStore.readerPrefs.first()
        val n = repo.getNovel(novelId)
        if (n == null) { error = "作品が見つかりません"; return@LaunchedEffect }
        novel = n
        val chapterId = n.lastChapterId ?: repo.listChapters(novelId).firstOrNull()?.chapterId
        if (chapterId == null) { error = "目次がありません"; return@LaunchedEffect }
        // 前回の位置は、同じ話を開くときだけ使う
        open(chapterId, if (n.lastChapterId == chapterId) ReadingPosition(n.lastBlock, n.lastOffset) else null)
        // 開いたあとで、作品の更新を確かめる（UPD-01）。失敗しても読書は続けられる
        val added = runCatching { repo.checkForUpdates(novelId) }.getOrNull()
        if (added != null && added > 0) {
            Toast.makeText(context, "新しい話が${added}話あります", Toast.LENGTH_SHORT).show()
        }
    }

    // サイトのページをリーダーの中で開く。戻ったときに読んでいた所から再開できるようにしておく
    fun openWeb(url: String) {
        opened?.let { opened = it.copy(startPosition = lastPosition) }
        webUrl = url
    }

    // 最初の話の前・最終話の次へ進もうとしたとき：設定した動作をする
    suspend fun edgeAction(direction: String) {
        when (prefs?.edgeAction ?: EdgeAction.UPDATE) {
            EdgeAction.WORK_PAGE -> {
                notice = ReaderNotice(null, direction)
                novel?.sourceUrl?.let { openWeb(it) }
            }
            EdgeAction.UPDATE -> {
                if (edgeBusy) return
                edgeBusy = true
                notice = ReaderNotice("更新を確認中…", direction, ms = 0)
                try {
                    val result = runCatching { repo.refreshToc(novelId) }
                    val added = result.getOrNull()
                    when {
                        result.isFailure -> notice = ReaderNotice("更新を確認できませんでした", direction)
                        added == null -> notice = ReaderNotice("この作品は更新を確認できません", direction)
                        added <= 0 -> notice = ReaderNotice("新しい話はありません", direction)
                        direction == "end" -> {
                            // 新しい話があれば、そのまま次の話へ
                            val list = repo.listChapters(novelId)
                            val o = opened
                            val next = o?.let { list.getOrNull(list.indexOfFirst { c -> c.chapterId == it.chapterId } + 1) }
                            if (next != null) open(next.chapterId, null, 1)
                            else notice = ReaderNotice("新しい話が${added}話あります", direction)
                        }
                        else -> notice = ReaderNotice("新しい話が${added}話あります", direction)
                    }
                } finally {
                    edgeBusy = false
                }
            }
        }
    }

    // 前後の話へ移る。次の話は先頭から、前の話は最後のページから。話がなければ設定した動作
    fun moveChapter(direction: String) {
        val o = opened ?: return
        val index = chapters.indexOfFirst { it.chapterId == o.chapterId }
        if (index < 0) return
        scope.launch {
            if (direction == "end") {
                repo.markRead(novelId, o.chapterId)
                val next = chapters.getOrNull(index + 1)
                if (next == null) edgeAction(direction) else open(next.chapterId, null, 1)
            } else {
                val prev = chapters.getOrNull(index - 1)
                if (prev == null) { edgeAction(direction); return@launch }
                val doc = runCatching { repo.getDocument(novelId, prev.chapterId) }.getOrNull() ?: return@launch
                open(prev.chapterId, doc.endPosition(), -1)
            }
        }
    }

    // 戻る：サイトのページ・目次を開いていればそれを閉じる。リーダーなら本棚へ
    BackHandler {
        when {
            webUrl != null -> webUrl = null
            showToc -> showToc = false
            else -> onClose()
        }
    }

    val o = opened
    val p = prefs
    when {
        error != null && o == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "", textAlign = TextAlign.Center)
        }

        webUrl != null -> BrowserScreen(
            startUrl = webUrl ?: "",
            onClose = { webUrl = null },
            onUrlChanged = { webUrl = it },
            onOpenNovel = { id ->
                webUrl = null
                if (id != novelId) onOpenNovel(id)
            },
        )

        showToc && o != null -> TocScreen(
            novelTitle = novel?.title ?: "",
            currentChapterId = o.chapterId,
            chapters = chapters,
            bookmarks = bookmarks,
            onClose = { showToc = false },
            onOpenChapter = { id ->
                showToc = false
                // 今読んでいる話を選んだときは、読んでいた位置に戻る
                if (id == o.chapterId) opened = o.copy(startPosition = lastPosition)
                else scope.launch { open(id, null) }
            },
            onOpenBookmark = { b ->
                showToc = false
                scope.launch { open(b.chapterId, ReadingPosition(b.block, b.offset)) }
            },
            onDeleteBookmark = { id -> scope.launch { repo.deleteBookmark(id) } },
            download = download,
            onDownload = novel?.let { n -> container.sites.adapterFor(n.siteId) }?.let {
                {
                    downloadJob = scope.launch {
                        download = DownloadProgress(0, 0, running = true)
                        try {
                            val failed = repo.downloadAll(novelId) { done, total ->
                                download = DownloadProgress(done, total, running = true)
                            }
                            val d = download
                            download = d?.copy(
                                running = false,
                                message = if (failed > 0) "${failed}話を取得できませんでした。もう一度押すと、その話だけ取り直します"
                                else "全${d.total}話を保存しました",
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            download = download?.copy(running = false, message = "止めました（${download?.done} / ${download?.total} 話を保存済み）")
                            throw e
                        } catch (e: Exception) {
                            download = download?.copy(running = false, message = "途中で止まりました：${e.message}")
                        }
                    }
                }
            },
            onStopDownload = { downloadJob?.cancel() },
            onRefresh = if (novel?.let { container.sites.adapterFor(it.siteId)?.canCheckUpdates } != true) null else suspend {
                val added = runCatching { repo.refreshToc(novelId) }
                    .onFailure { Toast.makeText(context, "更新を確認できませんでした（${it.message}）", Toast.LENGTH_LONG).show() }
                    .getOrNull()
                if (added != null) {
                    val msg = if (added > 0) "新しい話が${added}話あります" else "新しい話はありません"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )

        o != null && p != null -> {
            FullScreenWhileVisible()
            CountReadingTime()
            // 話が変わっても ReaderScreen は作り直さない（メニューを開いたまま、演出付きで本文を差し替える）
            run {
                ReaderScreen(
                    document = o.document,
                    initialPosition = o.startPosition,
                    transitionDirection = o.direction,
                    prefs = p,
                    bookmarks = bookmarks.filter { it.chapterId == o.chapterId },
                    prevTitle = chapters.getOrNull(chapters.indexOfFirst { it.chapterId == o.chapterId } - 1)?.title,
                    nextTitle = chapters.indexOfFirst { it.chapterId == o.chapterId }
                        .takeIf { it >= 0 }?.let { chapters.getOrNull(it + 1)?.title },
                    onPrefsChange = { newPrefs ->
                        prefs = newPrefs
                        scope.launch { container.settingsStore.save(newPrefs) }
                    },
                    onPositionChanged = { pos, page, total ->
                        lastPosition = pos
                        scope.launch {
                            repo.savePosition(novelId, o.chapterId, pos)
                            // 最後のページまで読んだら既読にする
                            if (page >= total) repo.markRead(novelId, o.chapterId)
                        }
                    },
                    onEdge = { direction -> moveChapter(direction) },
                    onBack = onClose,
                    onOpenToc = {
                        // 目次から戻ったときに、読んでいた所から再開できるようにしておく
                        opened = o.copy(startPosition = lastPosition)
                        showToc = true
                    },
                    onAddBookmark = { pos ->
                        scope.launch { repo.addBookmark(novelId, o.chapterId, pos, o.document.excerptAt(pos)) }
                    },
                    onDeleteBookmarks = { ids -> scope.launch { ids.forEach { repo.deleteBookmark(it) } } },
                    notice = notice,
                    chapterNumber = chapters.filter { !it.title.startsWith(REMOVED_MARK) }
                        .indexOfFirst { it.chapterId == o.chapterId }.takeIf { it >= 0 }?.plus(1),
                    chapterCount = chapters.count { !it.title.startsWith(REMOVED_MARK) },
                    onOpenSource = {
                        chapters.firstOrNull { it.chapterId == o.chapterId }?.url?.let { openWeb(it) }
                    },
                    onJumpChapter = { next ->
                        // ツールから直接移るときは、どちらの話も先頭から開く（既読にはしない）
                        val index = chapters.indexOfFirst { it.chapterId == o.chapterId }
                        val target = chapters.getOrNull(if (next) index + 1 else index - 1)
                        if (index >= 0 && target != null) scope.launch { open(target.chapterId, null, if (next) 1 else -1) }
                    },
                )
            }
        }

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/**
 * 読書画面が前面に出ている間の時間を、読書メーターの読書時間に足す（HELP-01）。
 * アプリを裏に回したとき・画面を離れたときに、それまでの時間をまとめて記録する
 */
@Composable
private fun CountReadingTime() {
    val context = LocalContext.current
    val store = context.appContainer.settingsStore
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // 1回で数える上限（画面を付けたまま放置したときに増えすぎないように）
        val maxChunk = 30 * 60_000L
        var since: Long? = if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) System.currentTimeMillis() else null
        fun flush() {
            val start = since ?: return
            since = null
            val ms = (System.currentTimeMillis() - start).coerceAtMost(maxChunk)
            appScope.launch { store.addReadingTime(ms) }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    since = System.currentTimeMillis()
                    appScope.launch { store.markUsedToday() }
                }
                Lifecycle.Event.ON_PAUSE -> flush()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            flush()
        }
    }
}

/** 画面が消えたあとも続ける短い保存処理用 */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** この画面が出ている間だけ、ステータスバーとナビゲーションバーを隠す */
@Composable
private fun FullScreenWhileVisible() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
