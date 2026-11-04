package com.example.wintreader

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun parseColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/** 読書位置の簡易保存（M2 で Room に置き換える） */
private class PositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader_position", Context.MODE_PRIVATE)

    fun load(chapterKey: String): ReadingPosition? {
        if (!prefs.contains("$chapterKey.block")) return null
        return ReadingPosition(prefs.getInt("$chapterKey.block", 0), prefs.getInt("$chapterKey.offset", 0))
    }

    fun save(chapterKey: String, pos: ReadingPosition) {
        prefs.edit()
            .putInt("$chapterKey.block", pos.block)
            .putInt("$chapterKey.offset", pos.offset)
            .apply()
    }
}

@Composable
fun ReaderScreen(document: NovelDocument) {
    val context = LocalContext.current
    val store = remember { PositionStore(context) }
    val chapterKey = "${document.workId}/${document.chapterId}"

    var settings by remember { mutableStateOf(ReaderSettings()) }
    var mode by remember { mutableStateOf(ReadingMode.TAP) }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    var menuVisible by remember { mutableStateOf(false) }
    // ページ送りの方向：true = 左タップで次ページ（初期値）、false = 右タップで次ページ
    var leftTapNext by remember { mutableStateOf(true) }

    val controller = remember { ReaderController() }
    val currentMode by rememberUpdatedState(mode)

    val bridge = remember {
        ReaderBridge(
            onReadyCallback = { total -> totalPages = total },
            onPageChangedCallback = { p, total, pos ->
                page = p
                totalPages = total
                store.save(chapterKey, pos)
            },
            onEdgeCallback = { /* 前後の話への移動は M2 で実装 */ },
        )
    }

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
                            menuVisible -> menuVisible = false
                            zone == TapZone.CENTER -> menuVisible = true
                            currentMode == ReadingMode.SCROLL -> Unit // スクロール時は中央タップのみ
                            // 初期値は左タップで次ページ。設定パネルで逆にできる
                            (zone == TapZone.LEFT) == leftTapNext -> controller.next()
                            else -> controller.prev()
                        }
                    },
                    onPageLoaded = {
                        controller.load(document, settings, store.load(chapterKey))
                    },
                ).also { controller.webView = it }
            },
        )

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

        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SettingsPanel(
                settings = settings,
                mode = mode,
                page = page,
                totalPages = totalPages,
                leftTapNext = leftTapNext,
                onTapDirectionChange = { leftTapNext = it },
                onSettingsChange = {
                    settings = it
                    controller.applySettings(it)
                },
                onModeChange = {
                    mode = it
                    controller.setMode(it)
                },
                onPageSelected = { controller.goToPage(it - 1) },
            )
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

@Composable
private fun SettingsPanel(
    settings: ReaderSettings,
    mode: ReadingMode,
    page: Int,
    totalPages: Int,
    leftTapNext: Boolean,
    onTapDirectionChange: (Boolean) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onModeChange: (ReadingMode) -> Unit,
    onPageSelected: (Int) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ページ位置スライダー（UI-08）
            if (mode != ReadingMode.SCROLL && totalPages > 1) {
                var sliderValue by remember(page) { mutableFloatStateOf(page.toFloat()) }
                Text("ページ ${sliderValue.toInt()} / $totalPages", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onPageSelected(sliderValue.toInt()) },
                    valueRange = 1f..totalPages.toFloat(),
                )
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
