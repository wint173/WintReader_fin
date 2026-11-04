package com.example.wintreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.wintreader.ui.theme.WintReaderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            WintReaderTheme {
                // M2 の途中：最後に読んだ作品をリーダーで開く（本棚画面は次の段階で作る）
                LastReadNovel()
            }
        }
    }

    /** READ-27：全画面表示。画面端からスワイプすると一時的にシステムバーが出る */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** リーダーを開くのに必要なものをまとめて読み込んだ状態 */
private data class OpenedChapter(
    val novelId: String,
    val chapterId: String,
    val document: NovelDocument,
    val position: ReadingPosition?,
    val prefs: ReaderPrefs,
)

@Composable
private fun LastReadNovel() {
    val container = LocalContext.current.appContainer
    val repo = container.repository
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenedChapter?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        repo.ensureSampleData()
        val novel = repo.observeNovels().first().maxByOrNull { it.lastReadAt ?: it.addedAt }
        if (novel == null) { error = "本棚に作品がありません"; return@LaunchedEffect }
        val chapterId = novel.lastChapterId ?: repo.listChapters(novel.id).firstOrNull()?.chapterId
        val doc = chapterId?.let { repo.loadDocument(novel.id, it) }
        if (chapterId == null || doc == null) { error = "本文が見つかりません"; return@LaunchedEffect }
        // 前回の位置は、同じ話を開くときだけ使う
        val position = if (novel.lastChapterId == chapterId) ReadingPosition(novel.lastBlock, novel.lastOffset) else null
        opened = OpenedChapter(novel.id, chapterId, doc, position, container.settingsStore.readerPrefs.first())
    }

    val o = opened
    when {
        o != null -> ReaderScreen(
            document = o.document,
            initialPosition = o.position,
            prefs = o.prefs,
            onPrefsChange = { p -> scope.launch { container.settingsStore.save(p) } },
            onPositionChanged = { pos -> scope.launch { repo.savePosition(o.novelId, o.chapterId, pos) } },
        )
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!) }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
