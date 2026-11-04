package com.example.wintreader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 文庫タブに並べるサイト（WEB-10） */
data class BunkoSite(
    /** 並び順・表示の設定を保存するときの名前 */
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    /** 本棚への追加に対応しているか（未対応のサイトは閲覧だけ） */
    val canAdd: Boolean,
)

val BunkoSites = listOf(
    BunkoSite("narou", "小説家になろうグループ", "国内最大級の投稿サイト。異世界ファンタジーを中心に長編が豊富で、書籍化作品も多い", "https://syosetu.com/site/group/", true),
    BunkoSite("kakuyomu", "カクヨム", "KADOKAWA運営。書籍化・コンテスト作品が多く、現代ドラマやラブコメも充実", "https://kakuyomu.jp/", true),
    BunkoSite("hameln", "ハーメルン", "二次創作が中心の投稿サイト。アニメ・ゲームの原作別に探せて、オリジナルも多い", "https://syosetu.org/", true),
    BunkoSite("alphapolis", "アルファポリス", "書籍化・漫画化が盛ん。男性向け・女性向けともファンタジーが強い（レンタルの話は読めない）", "https://www.alphapolis.co.jp/novel/", true),
    BunkoSite("novelup", "ノベルアップ＋", "応援ポイントで作品を後押しできる投稿サイト。ジャンルが幅広く新作も多い", "https://novelup.plus/", true),
    BunkoSite("pixiv", "pixiv 小説", "イラストSNSの小説。二次創作の短編が多い。シリーズは1作品として本棚に入る", "https://www.pixiv.net/novel/", true),
    BunkoSite("aozora", "青空文庫", "著作権の切れた日本の名作を無料公開。漱石・太宰など近代文学を読める", "https://www.aozora.gr.jp/", true),
    BunkoSite("gutenberg", "Project Gutenberg", "著作権の切れた海外の古典を無料公開する電子図書館。主に英語の原文で読める", "https://www.gutenberg.org/", true),
)

/** 設定の並び順・非表示を当てはめる。設定にないサイト（あとから増えたもの）は最後に並べる */
fun arrangeSites(order: List<String>, hidden: Set<String>, includeHidden: Boolean): List<BunkoSite> {
    val byId = BunkoSites.associateBy { it.id }
    val ordered = order.mapNotNull { byId[it] } + BunkoSites.filter { it.id !in order }
    return if (includeHidden) ordered else ordered.filter { it.id !in hidden }
}

/** 文庫タブ：対応サイトの一覧。右上の「編集」で並び順と表示・非表示を変えられる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BunkoScreen(onOpenSite: (String) -> Unit) {
    val store = LocalContext.current.appContainer.settingsStore
    val scope = rememberCoroutineScope()
    val layoutFlow = remember { store.bunkoLayout }
    val layout by layoutFlow.collectAsState(initial = BunkoLayout())
    var editing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("文庫") },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = { TextButton(onClick = { editing = !editing }) { Text(if (editing) "完了" else "編集") } },
        )
        val sites = arrangeSites(layout.order, layout.hidden, includeHidden = editing)
        if (!editing && sites.isEmpty()) {
            Text(
                "表示するサイトがありません。右上の「編集」から表示するサイトを選んでください。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sites, key = { it.id }) { site ->
                if (editing) {
                    val visible = site.id !in layout.hidden
                    fun save(order: List<BunkoSite>, hidden: Set<String>) =
                        scope.launch { store.saveBunkoLayout(BunkoLayout(order.map { it.id }, hidden)) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Checkbox(
                            checked = visible,
                            onCheckedChange = { on -> save(sites, if (on) layout.hidden - site.id else layout.hidden + site.id) },
                        )
                        Text(
                            site.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        val i = sites.indexOf(site)
                        TextButton(
                            enabled = i > 0,
                            onClick = { save(sites.toMutableList().apply { add(i - 1, removeAt(i)) }, layout.hidden) },
                        ) { Text("▲") }
                        TextButton(
                            enabled = i < sites.lastIndex,
                            onClick = { save(sites.toMutableList().apply { add(i + 1, removeAt(i)) }, layout.hidden) },
                        ) { Text("▼") }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSite(site.url) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(site.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            site.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * アプリ内ブラウザ（WEB-01〜09, 11〜14）。
 * サイトの検索・ランキングはそのまま使い、作品のページを開いていると「本棚に追加」を出す。
 * 追加しても画面はサイトに残る（WEB-16）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    startUrl: String,
    onClose: () -> Unit,
    /** 今のページの URL（作品を読んで戻ってきたとき、同じページから再開するため） */
    onUrlChanged: (String) -> Unit,
    onOpenNovel: (String) -> Unit,
    /** 追加後の動作が「本棚に戻る」のとき（WEB-15）。リーダーの中から開いたときはブラウザを閉じるだけ */
    onBackToLibrary: () -> Unit = onClose,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val repo = container.repository
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val urlCallback by rememberUpdatedState(onUrlChanged)
    LaunchedEffect(currentUrl) { urlCallback(currentUrl) }

    val novelsFlow = remember { repo.observeNovels() }
    val novels by novelsFlow.collectAsState(initial = emptyList())

    val webView = remember {
        WebView(context).apply {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                // 暗号化なし（http://）のページは Android が読み込ませないので、https:// に直して開く
                // （ハーメルンの挿絵のリンクなどが http:// で書かれている）
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (request.isForMainFrame && url.startsWith("http://")) {
                        view.loadUrl("https://" + url.removePrefix("http://"))
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    currentUrl = url
                    canGoBack = view.canGoBack()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    currentUrl = url
                    canGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    canGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress / 100f
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title ?: ""
                }
            }
            loadUrl(startUrl)
        }
    }

    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    // 戻る：ブラウザの履歴があれば戻り、なければ文庫へ
    BackHandler { if (webView.canGoBack()) webView.goBack() else onClose() }

    // 今のページが作品のページか（WEB-04）。本棚にすでにあるか（WEB-07）
    val ref = container.sites.parseUrl(currentUrl)
    val novelId = ref?.let { NovelEntity.idOf(it.siteId, it.workId) }
    val inLibrary = novelId != null && novels.any { it.id == novelId }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = onClose) { Text("✕ 閉じる") }
                Text(
                    pageTitle.ifBlank { currentUrl },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { webView.goBack() }, enabled = canGoBack) { Text("‹ 戻る") }
                // 外部のブラウザ（Chrome など）で開く（READ-23）
                IconButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                    }.onFailure { Toast.makeText(context, "開けるブラウザがありません", Toast.LENGTH_SHORT).show() }
                }) {
                    Icon(WIcons.OpenPage, contentDescription = "外部のブラウザで開く", modifier = Modifier.size(22.dp))
                }
            }
            if (progress < 1f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }

            AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())

            // 作品のページを開いているときだけ出る操作（WEB-05）
            if (ref != null) {
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (inLibrary) "この作品は本棚にあります" else "この作品を本棚に追加できます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (inLibrary && novelId != null) {
                        OutlinedButton(onClick = { onOpenNovel(novelId) }) { Text("読む") }
                    } else {
                        Button(
                            enabled = !adding,
                            onClick = {
                                adding = true
                                scope.launch {
                                    try {
                                        val id = repo.addWork(ref)
                                        Toast.makeText(context, "本棚に追加しました", Toast.LENGTH_SHORT).show()
                                        // 追加したあとの動き（設定で選ぶ。WEB-15）
                                        when (container.settingsStore.appPrefs.first().afterAdd) {
                                            AfterAddAction.STAY_ON_SITE -> Unit
                                            AfterAddAction.BACK_TO_LIBRARY -> onBackToLibrary()
                                            AfterAddAction.OPEN_NOVEL -> onOpenNovel(id)
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "追加できませんでした：${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        adding = false
                                    }
                                }
                            },
                        ) { Text(if (adding) "目次を取得中…" else "本棚に追加") }
                    }
                }
            }
        }
    }
}
