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

    fun applySettings(settings: ReaderSettings) {
        exec("Reader.applySettings(${ReaderJson.encodeToString(ReaderSettings.serializer(), settings)})")
    }

    fun setMode(mode: ReadingMode) {
        exec("Reader.setMode('${mode.jsName}')")
        exec("Reader.setSwipeEnabled(${mode == ReadingMode.SWIPE})")
    }
    fun next() = exec("Reader.next()")
    fun prev() = exec("Reader.prev()")
    fun goToPage(index: Int) = exec("Reader.goToPage($index)")
    fun goToPosition(pos: ReadingPosition) = exec("Reader.goToPosition(${pos.block}, ${pos.offset})")
}

/** reader.js からの通知を受け取る。JavaScript のスレッドから呼ばれるのでメインスレッドへ渡す */
class ReaderBridge(
    private val onReadyCallback: (totalPages: Int) -> Unit,
    private val onPageChangedCallback: (page: Int, totalPages: Int, position: ReadingPosition) -> Unit,
    private val onEdgeCallback: (direction: String) -> Unit,
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
