package com.example.wintreader

import android.app.Application
import android.content.Context

/**
 * アプリ全体で1つだけ持つもの（データベース・保存先・設定）をまとめる。
 * 依存関係の管理ライブラリ（Hilt など）は使わず、ここで手作りする。
 */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.create(context)
    val chapterStore = ChapterStore(context)
    val settingsStore = SettingsStore(context)
    val http = SiteHttp(context)
    /** 本棚に追加できるサイト */
    val sites = SiteRegistry(
        listOf(
            NarouAdapter(http), KakuyomuAdapter(http), HamelnAdapter(http),
            AlphapolisAdapter(http), NovelupAdapter(http), PixivAdapter(http),
            AozoraAdapter(http), GutenbergAdapter(http),
        )
    )
    val repository = LibraryRepository(database, chapterStore, sites)
}

class WintApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Context からアプリ共通の入れ物を取り出す */
val Context.appContainer: AppContainer
    get() = (applicationContext as WintApp).container
