package com.example.wintreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun parseColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/**
 * 話の端の案内の場所に出す知らせ（「確認中…」「新しい話はありません」など）。
 * 同じ文でも出し直せるよう、出すたびに新しい id を付ける。text = null で消す
 */
data class ReaderNotice(
    val text: String?,
    val direction: String,
    /** 表示する時間。0 = 出したまま */
    val ms: Int = 2400,
    val id: Long = System.nanoTime(),
)

/**
 * 1話分の本文を表示する画面。
 * 表示設定は prefs から受け取り、変えたら onPrefsChange で保存してもらう。
 * 読書位置は initialPosition から始め、ページが変わるたびに onPositionChanged で知らせる。
 * 話の最初・最後でさらにページを送ろうとすると onEdge（"start" / "end"）を呼ぶ。
 */
@Composable
fun ReaderScreen(
    document: NovelDocument,
    initialPosition: ReadingPosition?,
    /** document が変わったときの切り替えの向き（1 = 次の話、-1 = 前の話、0 = その他） */
    transitionDirection: Int = 0,
    prefs: ReaderPrefs,
    bookmarks: List<BookmarkEntity>,
    /** 前後の話のタイトル（null = その方向に話がない） */
    prevTitle: String?,
    nextTitle: String?,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onPositionChanged: (position: ReadingPosition, page: Int, totalPages: Int) -> Unit,
    onEdge: (String) -> Unit,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onAddBookmark: (ReadingPosition) -> Unit,
    onDeleteBookmarks: (List<Long>) -> Unit,
    onJumpChapter: (next: Boolean) -> Unit,
    /** 今の話の元サイトのページを開く（ルーペ） */
    onOpenSource: () -> Unit,
    /** 話の端に出す知らせ（null = なし） */
    notice: ReaderNotice? = null,
    /** 今の話が何話目か（1始まり。分からなければ null）と、全部の話数 */
    chapterNumber: Int? = null,
    chapterCount: Int = 0,
) {
    var settings by remember { mutableStateOf(prefs.settings) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    var position by remember { mutableStateOf(initialPosition ?: ReadingPosition()) }
    var menuVisible by remember { mutableStateOf(false) }
    // 操作パネルで「表示設定」を開いているか（メニューを閉じたら元に戻す）
    var settingsOpen by remember { mutableStateOf(false) }
    // ページ送りの方向：true = 左タップで次ページ（初期値）、false = 右タップで次ページ
    var leftTapNext by remember { mutableStateOf(prefs.leftTapNext) }
    // 最初の話の前・最終話の次へ進もうとしたときの動作
    // 最初の話の前・最終話の次では、いつも更新を確かめる（設定の選択肢はなくした）
    val edgeAction = EdgeAction.UPDATE
    // 夜の配色（UI-11）・画面の向き（UI-06）・読書画面の明るさ（UI-04）
    var nightMode by remember { mutableStateOf(prefs.nightMode) }
    var orientation by remember { mutableStateOf(prefs.orientation) }
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    // 栞ごとの「何ページ目にあるか」（0始まり）。組版が変わるたびに reader.js に問い合わせる
    var bookmarkPages by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }
    // 自動スクロール（READ-12）が動いているか
    var autoRunning by remember { mutableStateOf(false) }
    // 自動スクロールの間だけスクロール操作に切り替える。そのとき元の操作を覚えておく（終わったら戻す）
    var modeBeforeAuto by remember { mutableStateOf<ReadingMode?>(null) }
    // 本文内検索（READ-15）：検索欄を開いているか・言葉・見つかった所・今どれを見ているか
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ReadingPosition>?>(null) }
    var hitIndex by remember { mutableIntStateOf(0) }

    val controller = remember { ReaderController() }
    val currentMode by rememberUpdatedState(mode)
    val positionCallback by rememberUpdatedState(onPositionChanged)
    val edgeCallback by rememberUpdatedState(onEdge)

    fun persist() = onPrefsChange(
        // 自動スクロールで一時的にスクロール操作にしているときは、元の操作を保存する
        ReaderPrefs(settings, modeBeforeAuto ?: mode, leftTapNext, edgeAction, nightMode, orientation, brightness)
    )

    // 実際に表示する設定。夜の配色のときは色だけ黒の配色にする（元の色は settings に残る）
    // 話のタイトルはいつも出す（設定の選択肢はなくした。以前「出さない」にしていても出す）
    fun shown(s: ReaderSettings = settings) =
        (if (nightMode) s.copy(bg = NightColors.first, fg = NightColors.second) else s).copy(showTitle = true)

    // 画面の向き：読書画面にいる間だけ固定する
    val activity = LocalContext.current as? Activity
    DisposableEffect(orientation) {
        activity?.requestedOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // 明るさ：読書画面にいる間だけ変える
    DisposableEffect(brightness) {
        val window = activity?.window
        window?.attributes = window?.attributes?.apply {
            screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness.coerceIn(0.02f, 1f)
        }
        onDispose {
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    // reader.js から「話の終わりで止まった」と来たときの処理（下で中身を入れる）
    // （状態ではない入れ物にする。描画のたびに入れ直しても描き直しが起きないように）
    val onAutoStopped = remember { arrayOfNulls<() -> Unit>(1) }

    val bridge = remember {
        ReaderBridge(
            onReadyCallback = { total -> totalPages = total },
            onPageChangedCallback = { p, total, pos ->
                page = p
                totalPages = total
                position = pos
                positionCallback(pos, p, total)
            },
            onEdgeCallback = { direction -> edgeCallback(direction) },
            onAutoStopCallback = { onAutoStopped[0]?.invoke() },
        )
    }

    fun closeSearch() {
        searchOpen = false
        hits = null
        controller.clearSearch()
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        controller.search(q) { found ->
            hits = found
            // 今読んでいる所より後で最初に見つかった所から見せる
            val start = found.indexOfFirst { it.block > position.block || (it.block == position.block && it.offset >= position.offset) }
            hitIndex = if (start >= 0) start else 0
            if (found.isNotEmpty()) controller.showHit(hitIndex)
        }
    }

    // 自動スクロールが終わったとき：元の操作（タップ・スワイプ）に戻す
    fun restoreModeAfterAuto() {
        autoRunning = false
        val before = modeBeforeAuto ?: return
        modeBeforeAuto = null
        mode = before
        controller.setMode(before)
    }
    onAutoStopped[0] = { restoreModeAfterAuto() }

    fun toggleAuto() {
        if (autoRunning) {
            controller.stopAuto()
            restoreModeAfterAuto()
        } else {
            // スクロール操作でなければ、動いている間だけスクロール操作にする
            if (mode != ReadingMode.SCROLL) {
                modeBeforeAuto = mode
                mode = ReadingMode.SCROLL
                controller.setMode(ReadingMode.SCROLL)
            }
            controller.startAuto(settings.autoSpeed)
            autoRunning = true
            menuVisible = false // 読みやすいようにメニューは閉じる
            settingsOpen = false
        }
    }

    // 話が変わったら自動スクロールは止まる（reader.js 側で止める）。操作を元に戻し、検索も閉じる
    LaunchedEffect(document.chapterId) {
        if (autoRunning) restoreModeAfterAuto()
        if (searchOpen) closeSearch()
    }

    // 話が変わったら、WebView とメニューはそのままに本文だけ差し替える（切り替えの演出付き）
    var loadedKey by remember { mutableStateOf(document.chapterId to initialPosition) }
    LaunchedEffect(document.chapterId, initialPosition) {
        val key = document.chapterId to initialPosition
        if (key != loadedKey) {
            loadedKey = key
            position = initialPosition ?: ReadingPosition()
            controller.switchChapter(document, initialPosition, transitionDirection)
        }
    }

    // 前後の話のタイトルが分かったら（目次の読み込み後など）reader.js に伝える
    LaunchedEffect(prevTitle, nextTitle, edgeAction) { controller.setEdgeLabels(prevTitle, nextTitle, edgeAction) }

    // 画面を作り直したとき（目次から戻ったときなど）は、前の知らせを出し直さない
    val shownNoticeId = remember { notice?.id }
    LaunchedEffect(notice) {
        notice?.takeIf { it.id != shownNoticeId }?.let { controller.showNotice(it.text, it.ms, it.direction) }
    }

    // 栞のページを調べ直す（栞の増減・ページ数の変化・表示設定の変更のとき）
    LaunchedEffect(bookmarks, totalPages, settings, mode) {
        val list = bookmarks.map { ReadingPosition(it.block, it.offset) }
        controller.pagesOf(list) { pages ->
            bookmarkPages = bookmarks.map { it.id }.zip(pages)
        }
    }
    val bookmarksOnPage = bookmarkPages.filter { it.second == page - 1 }.map { it.first }

    DisposableEffect(Unit) {
        onDispose {
            controller.webView?.destroy()
            controller.webView = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(parseColor(shown().bg)),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout),
            factory = { ctx ->
                createReaderWebView(
                    context = ctx,
                    bridge = bridge,
                    currentMode = { currentMode },
                    onTap = { zone ->
                        when {
                            menuVisible -> {
                                menuVisible = false
                                settingsOpen = false
                                if (searchOpen) closeSearch()
                            }
                            zone == TapZone.CENTER -> menuVisible = true
                            // スクロール操作では、ページ送りに使う左右がないので、どこをタップしてもメニューを出す
                            currentMode == ReadingMode.SCROLL -> menuVisible = true
                            // 初期値は左タップで次ページ。設定パネルで逆にできる
                            (zone == TapZone.LEFT) == leftTapNext -> controller.next()
                            else -> controller.prev()
                        }
                    },
                    onPageLoaded = {
                        controller.load(document, shown(), initialPosition)
                        if (mode != ReadingMode.TAP) controller.setMode(mode)
                        controller.setEdgeLabels(prevTitle, nextTitle, edgeAction)
                    },
                ).also { controller.webView = it }
            },
        )

        // このページに栞があるときの印（右上）
        if (bookmarksOnPage.isNotEmpty() && !menuVisible) {
            Text(
                "栞",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(end = 12.dp)
                    .background(Color(0xFFC0392B))
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }

        // 左下の時刻（READ-24）・右下のページ（READ-25）
        StatusBar(
            page = page,
            totalPages = totalPages,
            color = parseColor(shown().fg).copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = settings.padLeft.dp, vertical = 10.dp),
        )

        // 上部のツールバー（UI-01）：本棚へ戻る・自動スクロール・月・検索（ルーペ）・元サイト（地球儀）・栞
        // 検索中は、ツールバーの代わりに検索欄を出す
        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (searchOpen) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { runSearch() },
                    hits = hits,
                    hitIndex = hitIndex,
                    onMove = { delta ->
                        val list = hits
                        if (!list.isNullOrEmpty()) {
                            hitIndex = (hitIndex + delta + list.size) % list.size
                            controller.showHit(hitIndex)
                        }
                    },
                    onClose = { closeSearch() },
                )
            } else ReaderToolbar(
                hasBookmark = bookmarksOnPage.isNotEmpty(),
                onBack = onBack,
                onOpenSource = onOpenSource,
                onOpenSearch = {
                    if (autoRunning) toggleAuto()
                    searchOpen = true
                    settingsOpen = false
                },
                autoRunning = autoRunning,
                onToggleAuto = { toggleAuto() },
                nightMode = nightMode,
                onToggleNight = {
                    nightMode = !nightMode
                    controller.applySettings(shown())
                    persist()
                },
                onToggleBookmark = {
                    if (bookmarksOnPage.isNotEmpty()) onDeleteBookmarks(bookmarksOnPage)
                    else onAddBookmark(position)
                },
            )
        }

        AnimatedVisibility(
            visible = menuVisible && !searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (settingsOpen) {
                SettingsPanel(
                    settings = settings,
                    mode = mode,
                    leftTapNext = leftTapNext,
                    orientation = orientation,
                    onOrientationChange = {
                        orientation = it
                        persist()
                    },
                    brightness = brightness,
                    onBrightnessChange = {
                        brightness = it
                        persist()
                    },
                    onBack = { settingsOpen = false },
                    onTapDirectionChange = {
                        leftTapNext = it
                        persist()
                    },
                    onSettingsChange = {
                        // 色を選び直したら、夜の配色は解除する
                        if (nightMode && (it.bg != settings.bg || it.fg != settings.fg)) nightMode = false
                        settings = it
                        controller.applySettings(shown(it))
                        persist()
                    },
                    onModeChange = {
                        if (autoRunning) {
                            controller.stopAuto()
                            autoRunning = false
                            modeBeforeAuto = null
                        }
                        mode = it
                        controller.setMode(it)
                        persist()
                    },
                )
            } else {
                MainPanel(
                    mode = mode,
                    page = page,
                    totalPages = totalPages,
                    prevTitle = prevTitle,
                    nextTitle = nextTitle,
                    onPageSelected = { controller.goToPage(it - 1) },
                    onJumpChapter = onJumpChapter,
                    onOpenToc = onOpenToc,
                    onOpenSettings = { settingsOpen = true },
                    chapterTitle = document.title,
                    chapterNumber = chapterNumber,
                    chapterCount = chapterCount,
                )
            }
        }
    }
}

@Composable
private fun StatusBar(page: Int, totalPages: Int, color: Color, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            // 次の「分」が変わるまで待つ
            delay((60 - now.second) * 1000L - now.nano / 1_000_000L + 50L)
        }
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = color, fontSize = 12.sp)
        Text("$page/$totalPages", color = color, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- ツールバー・操作パネル

/** 図だけのボタン。label は読み上げ用（画面には出さない） */
@Composable
private fun ToolIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ReaderToolbar(
    hasBookmark: Boolean,
    onBack: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenSearch: () -> Unit,
    autoRunning: Boolean,
    onToggleAuto: () -> Unit,
    nightMode: Boolean,
    onToggleNight: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            ToolIcon(WIcons.Back, "本棚へ戻る", onBack)
            Spacer(Modifier.weight(1f))
            ToolIcon(
                if (autoRunning) WIcons.AutoStop else WIcons.AutoPlay,
                if (autoRunning) "自動スクロールを止める" else "自動スクロールを始める",
                onToggleAuto,
                tint = if (autoRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            ToolIcon(
                if (nightMode) WIcons.MoonFilled else WIcons.Moon,
                if (nightMode) "夜の配色を解除" else "夜の配色にする",
                onToggleNight,
                tint = if (nightMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            ToolIcon(WIcons.Search, "この話の中を検索", onOpenSearch)
            ToolIcon(WIcons.Globe, "元サイトのこの話のページを開く", onOpenSource)
            ToolIcon(
                if (hasBookmark) WIcons.BookmarkFilled else WIcons.Bookmark,
                if (hasBookmark) "栞を外す" else "栞を付ける",
                onToggleBookmark,
                tint = if (hasBookmark) Color(0xFFC0392B) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 本文内検索の欄（READ-15）。今の話の中だけを探す */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    hits: List<ReadingPosition>?,
    hitIndex: Int,
    onMove: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            ToolIcon(WIcons.Close, "検索を閉じる", onClose)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("この話の中を検索", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .focusRequester(focus),
            )
            if (hits != null) {
                Text(
                    if (hits.isEmpty()) "なし" else "${hitIndex + 1}/${hits.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ToolIcon(WIcons.ChevronUp, "前の候補", { onMove(-1) }, enabled = !hits.isNullOrEmpty())
            ToolIcon(WIcons.ChevronDown, "次の候補", { onMove(1) }, enabled = !hits.isNullOrEmpty())
        }
    }
}

/**
 * 中央タップで最初に出す操作パネル。上にページのスライダー、下の段に
 * 目次（左下：いちばん押しやすい所）・前後の話・表示設定を図で並べる。
 */
@Composable
private fun MainPanel(
    mode: ReadingMode,
    page: Int,
    totalPages: Int,
    prevTitle: String?,
    nextTitle: String?,
    onPageSelected: (Int) -> Unit,
    onJumpChapter: (next: Boolean) -> Unit,
    onOpenToc: () -> Unit,
    onOpenSettings: () -> Unit,
    chapterTitle: String,
    chapterNumber: Int?,
    chapterCount: Int,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            // ページ位置スライダー（UI-08）。スクロールモードでは画面何枚分の位置かで動かす
            if (totalPages > 1) {
                var sliderValue by remember(page, totalPages) { mutableFloatStateOf(page.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { onPageSelected(sliderValue.toInt()) },
                        valueRange = 1f..totalPages.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${sliderValue.toInt()} / $totalPages",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(64.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ToolIcon(WIcons.Toc, "目次・栞の一覧", onOpenToc)
                Spacer(Modifier.weight(1f))
                ToolIcon(WIcons.PrevChapter, "前の話", { onJumpChapter(false) }, enabled = prevTitle != null)
                Spacer(Modifier.width(24.dp))
                ToolIcon(WIcons.NextChapter, "次の話", { onJumpChapter(true) }, enabled = nextTitle != null)
                Spacer(Modifier.weight(1f))
                ToolIcon(WIcons.Tune, "表示設定", onOpenSettings)
            }
            // いちばん下に、今読んでいる話のタイトルと話数を小さく（第30話 ○○… 30/81話）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 2.dp),
            ) {
                Text(
                    chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chapterNumber != null && chapterCount > 0) {
                    Text(
                        "$chapterNumber/${chapterCount}話",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** 図で選ぶ設定の1段。左に小さく項目名、右に図の選択肢を並べる */
@Composable
private fun <T> IconChoiceRow(
    label: String,
    options: List<Triple<T, ImageVector, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, icon, description) ->
                val on = value == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 52.dp, height = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(value) },
                ) {
                    Icon(
                        icon,
                        contentDescription = description,
                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    settings: ReaderSettings,
    mode: ReadingMode,
    leftTapNext: Boolean,
    orientation: String,
    onOrientationChange: (String) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    onBack: () -> Unit,
    onTapDirectionChange: (Boolean) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onModeChange: (ReadingMode) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolIcon(WIcons.Back, "操作パネルへ戻る", onBack)
                Text("表示設定", style = MaterialTheme.typography.titleSmall)
            }

            // 文字の大きさ・行の高さ（横に並べる）
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Stepper(
                    icon = WIcons.FontSize,
                    description = "文字の大きさ",
                    value = settings.fontSize.toString(),
                    onMinus = { if (settings.fontSize > 12) onSettingsChange(settings.copy(fontSize = settings.fontSize - 1)) },
                    onPlus = { if (settings.fontSize < 36) onSettingsChange(settings.copy(fontSize = settings.fontSize + 1)) },
                    modifier = Modifier.weight(1f),
                )
                Stepper(
                    icon = WIcons.LineHeight,
                    description = "行の高さ",
                    value = "%.1f".format(settings.lineHeight),
                    onMinus = { if (settings.lineHeight > 1.3f) onSettingsChange(settings.copy(lineHeight = settings.lineHeight - 0.1f)) },
                    onPlus = { if (settings.lineHeight < 2.6f) onSettingsChange(settings.copy(lineHeight = settings.lineHeight + 0.1f)) },
                    modifier = Modifier.weight(1f),
                )
            }

            // 読書画面だけの明るさ（UI-04）。「自動」で端末の明るさに戻す
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    WIcons.Sun,
                    contentDescription = "明るさ",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 8.dp)
                        .size(22.dp),
                )
                var level by remember(brightness) { mutableFloatStateOf(if (brightness < 0f) 0.5f else brightness) }
                Slider(
                    value = level,
                    onValueChange = { level = it; onBrightnessChange(it) },
                    valueRange = 0.02f..1f,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onBrightnessChange(-1f) }) {
                    Text(
                        "自動",
                        color = if (brightness < 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 自動スクロールの速さ（READ-12）。スクロール操作では流れる速さ、ページ送りではめくる間隔
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    WIcons.AutoSpeed,
                    contentDescription = "自動スクロールの速さ",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 8.dp)
                        .size(22.dp),
                )
                var speed by remember(settings.autoSpeed) { mutableFloatStateOf(settings.autoSpeed.toFloat()) }
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    onValueChangeFinished = { onSettingsChange(settings.copy(autoSpeed = speed.roundToInt())) },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${speed.roundToInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(48.dp),
                )
            }

            Spacer(Modifier.size(4.dp))
            IconChoiceRow(
                label = "操作",
                options = listOf(
                    Triple(ReadingMode.TAP, WIcons.ModeTap, "タップ"),
                    Triple(ReadingMode.SWIPE, WIcons.ModeSwipe, "スワイプ"),
                    Triple(ReadingMode.SCROLL, WIcons.ModeScroll, "スクロール"),
                ),
                selected = mode,
                onSelect = onModeChange,
            )
            IconChoiceRow(
                label = "次へ",
                options = listOf(
                    Triple(true, WIcons.TapLeftNext, "左をタップで次のページ"),
                    Triple(false, WIcons.TapRightNext, "右をタップで次のページ"),
                ),
                selected = leftTapNext,
                onSelect = onTapDirectionChange,
            )
            IconChoiceRow(
                label = "めくり",
                options = listOf(
                    Triple("fold", WIcons.AnimFold, "折り返し"),
                    Triple("slide", WIcons.AnimSlide, "スライド"),
                    Triple("none", WIcons.AnimNone, "なし"),
                ),
                selected = settings.animation,
                onSelect = { onSettingsChange(settings.copy(animation = it)) },
            )
            IconChoiceRow(
                label = "挿絵",
                options = listOf(
                    Triple(true, WIcons.Image, "挿絵を表示する"),
                    Triple(false, WIcons.ImageOff, "挿絵を表示しない"),
                ),
                selected = settings.showImages,
                onSelect = { onSettingsChange(settings.copy(showImages = it)) },
            )
            IconChoiceRow(
                label = "向き",
                options = listOf(
                    Triple("auto", WIcons.OrientAuto, "画面の向き：自動回転"),
                    Triple("portrait", WIcons.OrientPortrait, "画面の向き：縦に固定"),
                    Triple("landscape", WIcons.OrientLandscape, "画面の向き：横に固定"),
                ),
                selected = orientation,
                onSelect = onOrientationChange,
            )

            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 4.dp)) {
                ColorPresets.forEach { (bg, fg) ->
                    val selected = settings.bg == bg
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(parseColor(bg))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape,
                            )
                            .clickable { onSettingsChange(settings.copy(bg = bg, fg = fg)) },
                    ) {
                        // 文字の色の見本（「あ」）
                        Text("あ", color = parseColor(fg), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** 図・−・値・＋ の小さな調整つまみ */
@Composable
private fun Stepper(
    icon: ImageVector,
    description: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp, end = 2.dp)
                .size(22.dp),
        )
        TextButton(onClick = onMinus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−", fontSize = 18.sp)
        }
        Text(value, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        TextButton(onClick = onPlus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("＋", fontSize = 18.sp)
        }
    }
}
