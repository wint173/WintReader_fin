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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun parseColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

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
    // 栞ごとの「何ページ目にあるか」（0始まり）。組版が変わるたびに reader.js に問い合わせる
    var bookmarkPages by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }

    val controller = remember { ReaderController() }
    val currentMode by rememberUpdatedState(mode)
    val positionCallback by rememberUpdatedState(onPositionChanged)
    val edgeCallback by rememberUpdatedState(onEdge)

    fun persist() = onPrefsChange(ReaderPrefs(settings, mode, leftTapNext))

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
        )
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
    LaunchedEffect(prevTitle, nextTitle) { controller.setEdgeLabels(prevTitle, nextTitle) }

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
            .background(parseColor(settings.bg)),
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
                            menuVisible -> { menuVisible = false; settingsOpen = false }
                            zone == TapZone.CENTER -> menuVisible = true
                            currentMode == ReadingMode.SCROLL -> Unit // スクロール時は中央タップのみ
                            // 初期値は左タップで次ページ。設定パネルで逆にできる
                            (zone == TapZone.LEFT) == leftTapNext -> controller.next()
                            else -> controller.prev()
                        }
                    },
                    onPageLoaded = {
                        controller.load(document, settings, initialPosition)
                        if (mode != ReadingMode.TAP) controller.setMode(mode)
                        controller.setEdgeLabels(prevTitle, nextTitle)
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
            color = parseColor(settings.fg).copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = settings.padLeft.dp, vertical = 10.dp),
        )

        // 上部のツールバー（UI-01）：戻る・話のタイトル・目次・栞
        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderToolbar(
                title = document.title,
                hasBookmark = bookmarksOnPage.isNotEmpty(),
                onBack = onBack,
                onOpenToc = onOpenToc,
                onToggleBookmark = {
                    if (bookmarksOnPage.isNotEmpty()) onDeleteBookmarks(bookmarksOnPage)
                    else onAddBookmark(position)
                },
            )
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (settingsOpen) {
                SettingsPanel(
                    settings = settings,
                    mode = mode,
                    leftTapNext = leftTapNext,
                    onBack = { settingsOpen = false },
                    onTapDirectionChange = {
                        leftTapNext = it
                        persist()
                    },
                    onSettingsChange = {
                        settings = it
                        controller.applySettings(it)
                        persist()
                    },
                    onModeChange = {
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
                    onOpenSettings = { settingsOpen = true },
                )
            }
        }
    }
}

@Composable
private fun ReaderToolbar(
    title: String,
    hasBookmark: Boolean,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
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
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text("← 本棚") }
            Text(
                title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenToc) { Text("目次") }
            TextButton(onClick = onToggleBookmark) { Text(if (hasBookmark) "栞を外す" else "栞を付ける") }
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

/**
 * 中央タップで最初に出す操作パネル。よく使う「ページの移動」と「前後の話」を先に置き、
 * 表示設定は一段下（「表示設定」ボタンの先）にまとめる。
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
    onOpenSettings: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ページ位置スライダー（UI-08）。スクロールモードでは画面何枚分の位置かで動かす
            if (totalPages > 1) {
                var sliderValue by remember(page, totalPages) { mutableFloatStateOf(page.toFloat()) }
                val unit = if (mode == ReadingMode.SCROLL) "位置" else "ページ"
                Text("$unit ${sliderValue.toInt()} / $totalPages", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onPageSelected(sliderValue.toInt()) },
                    valueRange = 1f..totalPages.toFloat(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onJumpChapter(false) },
                    enabled = prevTitle != null,
                    modifier = Modifier.weight(1f),
                ) { Text("◀ 前の話", maxLines = 1) }
                OutlinedButton(
                    onClick = { onJumpChapter(true) },
                    enabled = nextTitle != null,
                    modifier = Modifier.weight(1f),
                ) { Text("次の話 ▶", maxLines = 1) }
            }
            Spacer(Modifier.size(4.dp))
            TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) {
                Text("表示設定（文字・背景・操作）›")
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    settings: ReaderSettings,
    mode: ReadingMode,
    leftTapNext: Boolean,
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 戻る") }
                Text("表示設定", style = MaterialTheme.typography.titleSmall)
            }

            StepperRow(
                label = "文字の大きさ",
                value = settings.fontSize.toString(),
                onMinus = { if (settings.fontSize > 12) onSettingsChange(settings.copy(fontSize = settings.fontSize - 1)) },
                onPlus = { if (settings.fontSize < 36) onSettingsChange(settings.copy(fontSize = settings.fontSize + 1)) },
            )
            StepperRow(
                label = "行の高さ",
                value = "%.1f".format(settings.lineHeight),
                onMinus = { if (settings.lineHeight > 1.3f) onSettingsChange(settings.copy(lineHeight = settings.lineHeight - 0.1f)) },
                onPlus = { if (settings.lineHeight < 2.6f) onSettingsChange(settings.copy(lineHeight = settings.lineHeight + 0.1f)) },
            )

            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ReadingMode.TAP to "タップ",
                    ReadingMode.SWIPE to "スワイプ",
                    ReadingMode.SCROLL to "スクロール",
                ).forEach { (m, label) ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = leftTapNext,
                    onClick = { onTapDirectionChange(true) },
                    label = { Text("左タップで次へ") },
                )
                FilterChip(
                    selected = !leftTapNext,
                    onClick = { onTapDirectionChange(false) },
                    label = { Text("右タップで次へ") },
                )
            }

            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("めくり", modifier = Modifier.align(Alignment.CenterVertically))
                listOf("fold" to "折り返し", "slide" to "スライド", "none" to "なし").forEach { (key, label) ->
                    FilterChip(
                        selected = settings.animation == key,
                        onClick = { onSettingsChange(settings.copy(animation = key)) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorPresets.forEach { (bg, fg) ->
                    val selected = settings.bg == bg
                    Box(
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
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onPlus) { Text("＋") }
    }
}
