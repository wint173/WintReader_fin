package com.example.wintreader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 操作モード（READ-30）。
 * TAP：タップでページ送り / SWIPE：指に合わせてめくる（タップでも送れる）/ SCROLL：上下スクロール
 * TAP と SWIPE は reader.js 側では同じページ表示で、スワイプを受け付けるかだけが違う
 */
enum class ReadingMode(val jsName: String) { TAP("paged"), SWIPE("paged"), SCROLL("scroll") }

/** タップした場所（14.3 の決定：判定は Kotlin 側で行う） */
enum class TapZone { LEFT, CENTER, RIGHT }

/** reader.js に渡す表示設定。名前は reader.js の DEFAULT_SETTINGS と対応 */
@Serializable
data class ReaderSettings(
    val fontSize: Int = 18,
    val lineHeight: Float = 1.8f,
    val padTop: Int = 24,
    val padBottom: Int = 40,
    val padLeft: Int = 20,
    val padRight: Int = 20,
    val bg: String = "#ffffff",
    val fg: String = "#222222",
    val fontFamily: String = "sans-serif",
    /** ページめくりのアニメーション（READ-13）："fold"（折り返し）| "slide" | "none" */
    val animation: String = "fold",
    /** 挿絵を表示するか（表示しないときは「［挿絵］」の印だけ出す） */
    val showImages: Boolean = true,
    /** 自動スクロールの速さ（READ-12）。1（遅い）〜10（速い） */
    val autoSpeed: Int = 5,
)

/** 背景色プリセット（UI-03：6種類） */
val ColorPresets: List<Pair<String, String>> = listOf(
    "#ffffff" to "#222222", // 白
    "#f6f0e1" to "#2b2620", // 生成り
    "#e8dcc4" to "#3a2f22", // セピア
    "#e2ecdc" to "#1f2a1c", // 薄緑
    "#d7dbe0" to "#1c1f24", // 青灰
    "#121212" to "#c8c8c8", // 黒
)

/** 月のアイコンで切り替える夜の配色（黒の配色と同じ） */
val NightColors: Pair<String, String> = "#121212" to "#c8c8c8"

val ReaderJson = Json { encodeDefaults = true }

/**
 * Kotlin から reader.js を呼ぶための窓口。
 * evaluateJavascript はメインスレッドから呼ぶ。
 */
class ReaderController {
    var webView: WebView? = null

    private fun exec(js: String) {
        webView?.evaluateJavascript(js, null)
    }

    fun load(doc: NovelDocument, settings: ReaderSettings, position: ReadingPosition?) {
        val docJson = ReaderJson.encodeToString(NovelDocument.serializer(), doc)
        val setJson = ReaderJson.encodeToString(ReaderSettings.serializer(), settings)
        val posJson = position?.let { ReaderJson.encodeToString(ReadingPosition.serializer(), it) } ?: "null"
        exec("Reader.load($docJson, $setJson, $posJson)")
    }

    /** WebView を作り直さずに話を切り替える。direction：1 = 次の話、-1 = 前の話、0 = その他 */
    fun switchChapter(doc: NovelDocument, position: ReadingPosition?, direction: Int) {
        val docJson = ReaderJson.encodeToString(NovelDocument.serializer(), doc)
        val posJson = position?.let { ReaderJson.encodeToString(ReadingPosition.serializer(), it) } ?: "null"
        exec("Reader.switchChapter($docJson, $posJson, $direction)")
    }

    fun applySettings(settings: ReaderSettings) {
        exec("Reader.applySettings(${ReaderJson.encodeToString(ReaderSettings.serializer(), settings)})")
    }

    /** 話の端で「次の話へ：○○」と案内するためのタイトル（null = その方向に話がない） */
    /** 前後の話のタイトルと、話がない方向でする動作（印と名前）を伝える */
    fun setEdgeLabels(prev: String?, next: String?, action: EdgeAction?) {
        val ser = String.serializer().nullable
        val actionJson = action?.let {
            "{\"glyph\":${ReaderJson.encodeToString(String.serializer(), it.glyph)},\"text\":${ReaderJson.encodeToString(String.serializer(), it.label)}}"
        } ?: "null"
        exec("window.Reader && Reader.setEdgeLabels(${ReaderJson.encodeToString(ser, prev)}, ${ReaderJson.encodeToString(ser, next)}, $actionJson)")
    }

    /** 話の端の案内の場所に知らせを出す。ms = 0 なら出したまま、text = null なら消す */
    fun showNotice(text: String?, ms: Int, direction: String) {
        val ser = String.serializer().nullable
        exec("window.Reader && Reader.showNotice(${ReaderJson.encodeToString(ser, text)}, $ms, '$direction')")
    }

    fun setMode(mode: ReadingMode) {
        exec("Reader.setMode('${mode.jsName}')")
        exec("Reader.setSwipeEnabled(${mode == ReadingMode.SWIPE})")
    }
    fun next() = exec("Reader.next()")
    fun prev() = exec("Reader.prev()")
    fun goToPage(index: Int) = exec("Reader.goToPage($index)")
    fun goToPosition(pos: ReadingPosition) = exec("Reader.goToPosition(${pos.block}, ${pos.offset})")

    /** 本文内の位置がそれぞれ何ページ目（0始まり）にあるかを調べる。結果は onResult に返す */
    fun pagesOf(positions: List<ReadingPosition>, onResult: (List<Int>) -> Unit) {
        val view = webView ?: return
        if (positions.isEmpty()) { onResult(emptyList()); return }
        val arg = ReaderJson.encodeToString(ListSerializer(ReadingPosition.serializer()), positions)
        view.evaluateJavascript("(window.Reader && Reader.pagesOf($arg)) || null") { raw ->
            // 戻り値は「JSON 文字列を JSON にしたもの」なので2回読む
            val pages = runCatching {
                val inner = ReaderJson.decodeFromString(String.serializer(), raw)
                ReaderJson.decodeFromString(ListSerializer(Int.serializer()), inner)
            }.getOrNull()
            if (pages != null && pages.size == positions.size) onResult(pages)
        }
    }

    // ------------------------------------------------ 本文内検索（READ-15）

    /** 今の話の中を探す。見つかった位置の一覧を返す */
    fun search(query: String, onResult: (List<ReadingPosition>) -> Unit) {
        val view = webView ?: return
        val arg = ReaderJson.encodeToString(String.serializer(), query)
        view.evaluateJavascript("(window.Reader && Reader.search($arg)) || \"[]\"") { raw ->
            val hits = runCatching {
                val inner = ReaderJson.decodeFromString(String.serializer(), raw)
                ReaderJson.decodeFromString(ListSerializer(ReadingPosition.serializer()), inner)
            }.getOrDefault(emptyList())
            onResult(hits)
        }
    }

    /** index 番目に見つかった所へ移って、印を付ける */
    fun showHit(index: Int) = exec("window.Reader && Reader.showHit($index)")
    fun clearSearch() = exec("window.Reader && Reader.clearSearch()")

    // ------------------------------------------------ 自動スクロール（READ-12）

    fun startAuto(speed: Int) = exec("window.Reader && Reader.startAuto($speed)")
    fun stopAuto() = exec("window.Reader && Reader.stopAuto()")
}

/** reader.js からの通知を受け取る。JavaScript のスレッドから呼ばれるのでメインスレッドへ渡す */
class ReaderBridge(
    private val onReadyCallback: (totalPages: Int) -> Unit,
    private val onPageChangedCallback: (page: Int, totalPages: Int, position: ReadingPosition) -> Unit,
    private val onEdgeCallback: (direction: String) -> Unit,
    /** 自動スクロールが話の終わりで止まったとき */
    private val onAutoStopCallback: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady(totalPages: Int) {
        main.post { onReadyCallback(totalPages) }
    }

    @JavascriptInterface
    fun onPageChanged(page: Int, totalPages: Int, block: Int, offset: Int) {
        main.post { onPageChangedCallback(page, totalPages, ReadingPosition(block, offset)) }
    }

    @JavascriptInterface
    fun onEdge(direction: String) {
        main.post { onEdgeCallback(direction) }
    }

    @JavascriptInterface
    fun onAutoStop() {
        main.post { onAutoStopCallback() }
    }
}

private const val READER_URL = "https://appassets.androidplatform.net/assets/reader/reader.html"

/**
 * 本文表示用の WebView を作る。
 * - アプリ内の reader.html を WebViewAssetLoader 経由で読み込む（ファイルアクセスは無効）
 * - タップの判定は GestureDetector で行い、ページ送りモードではタッチをすべて受け取る
 * - スクロールモードではスクロールを WebView に任せ、中央タップだけを拾う
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
fun createReaderWebView(
    context: Context,
    bridge: ReaderBridge,
    currentMode: () -> ReadingMode,
    onTap: (TapZone) -> Unit,
    onPageLoaded: () -> Unit,
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    val webView = WebView(context)
    webView.setBackgroundColor(Color.TRANSPARENT)
    webView.keepScreenOn = true // READ-48：読書中は画面を消灯させない
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER

    with(webView.settings) {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100 // 端末の文字サイズ設定で本文が勝手に拡大されないようにする
        // reader.html/css/js はアプリ内のファイルなのでキャッシュしない。
        // キャッシュすると、アプリを更新しても古い reader.js が使われ続けることがある
        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
    }
    webView.clearCache(true) // 以前のバージョンで残ったキャッシュを消す

    webView.addJavascriptInterface(bridge, "ReaderBridge")

    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        // 本文画面からほかのページへは移動させない
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (url == READER_URL) onPageLoaded()
        }
    }

    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = webView.width.toFloat()
            val zone = when {
                e.x < w / 3f -> TapZone.LEFT
                e.x > w * 2f / 3f -> TapZone.RIGHT
                else -> TapZone.CENTER
            }
            onTap(zone)
            return true
        }
    })

    webView.setOnTouchListener { _, event ->
        detector.onTouchEvent(event)
        // タップモード：WebView にタッチを渡さない（勝手にスクロールさせない）
        // スクロールモード：WebView にも渡してスクロールさせる
        // スワイプモード：指の動きは reader.js が追いかけるので WebView にも渡す（タップの判定はここで行う）
        currentMode() == ReadingMode.TAP
    }

    webView.loadUrl(READER_URL)
    return webView
}
