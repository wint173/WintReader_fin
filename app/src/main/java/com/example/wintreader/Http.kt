package com.example.wintreader

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * サイトへの通信（WEB-19 / 要件定義書 8.1）。
 * - Cookie はアプリ内ブラウザ（WebView）と共有する。年齢確認やログインの状態をそのまま使うため
 * - User-Agent もアプリ内ブラウザと同じものにする
 * - 同じサイトへのアクセスは間隔を空ける（サイトに負荷をかけない）
 */
class SiteHttp(context: Context) {

    /** WebView の CookieManager をそのまま読み書きする */
    private val webViewCookies = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val manager = CookieManager.getInstance()
            cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
            manager.flush()
        }
    }

    private val userAgent: String = runCatching { WebSettings.getDefaultUserAgent(context) }
        .getOrDefault("Mozilla/5.0 (Linux; Android) WintReader")

    private val client = OkHttpClient.Builder()
        .cookieJar(webViewCookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // サイトごとに、最後にアクセスした時刻
    private val lastAccess = mutableMapOf<String, Long>()
    private val lock = Mutex()

    /** 同じホストへは最低 MIN_INTERVAL_MS 空けてからアクセスする。本文は応答の文字コードで文字列にする */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        fetch(url, headers) { res -> res.body?.string() ?: throw IOException("空の応答: $url") }

    /**
     * 応答をバイト列のまま返す。文字コードが UTF-8 とは限らず、HTTP の応答に書かれていないページ用
     * （青空文庫の Shift_JIS など。Jsoup に渡して、ページ内の meta から文字コードを判断させる）
     */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        fetch(url, headers) { res -> res.body?.bytes() ?: throw IOException("空の応答: $url") }

    private suspend fun <T> fetch(url: String, headers: Map<String, String>, read: (okhttp3.Response) -> T): T {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: ""
        lock.withLock {
            val wait = (lastAccess[host] ?: 0L) + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastAccess[host] = System.currentTimeMillis()
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "ja,en;q=0.8")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(request).execute().use { res ->
                // ボット対策の確認（AWS WAF の challenge など）。確認はブラウザでしか通せないので、利用者に頼む
                if (res.header("x-amzn-waf-action") == "challenge" || (res.code == 202 && (res.body?.contentLength() ?: 0L) == 0L)) {
                    throw SiteChallengeException(host)
                }
                if (res.code == 429 || res.code == 503) throw SiteBusyException(res.code)
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}: $url")
                read(res)
            }
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 1_000L
    }
}

/** サイトから「混雑・制限中」と返されたとき。取得を止める */
class SiteBusyException(code: Int) : IOException("サイトが混雑しているか、アクセスが制限されています（HTTP $code）")

/**
 * サイトのボット対策の確認が必要なとき。アプリ内ブラウザでそのサイトを開くと確認が済み（Cookie が共有される）、
 * そのあとは取得できる。確認を回避する処理はしない
 */
class SiteChallengeException(host: String) :
    IOException("サイトの確認が必要です。文庫からこのサイト（$host）のページを一度開いてから、もう一度お試しください")
