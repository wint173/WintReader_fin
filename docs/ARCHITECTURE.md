# 構成

## 技術スタック

- Kotlin 2.2.10（AGP 9.5.0 の組み込み Kotlin）、Jetpack Compose（BOM 2026.02.01）、Material3
- compileSdk / targetSdk 37、minSdk 29、applicationId `com.example.wintreader`
- Room 2.8.5（KSP 2.3.11）、DataStore Preferences 1.3.0、OkHttp 4.12.0、Jsoup 1.18.3、kotlinx.serialization 1.9.0、androidx.webkit 1.12.1
- バージョンは `gradle/libs.versions.toml`

## データの流れ

```
サイトの HTML ──SiteAdapter──▶ NovelDocument（共通本文モデル、JSON で保存）
                                  │
                    ReaderController.load()  ── WebView（assets/reader/reader.html）
                                  │                 window.Reader（JS の API）
                    ReaderBridge ◀────────────────  onReady / onPageChanged / onEdge / onAutoStop
```

## Kotlin ファイル（app/src/main/java/com/example/wintreader/、平置き）

| ファイル | 役割 |
|---|---|
| MainActivity.kt | `WintTheme(prefs) { AppRoot() }` |
| WintApp.kt | Application。appContainer（repository・settingsStore・sites など） |
| AppRoot.kt | 画面の切り替え（本棚・文庫・ヘルプ・目次・リーダー・ブラウザ・設定）、ReaderRoute（話の読み込み・先読み・端の更新確認）、起動時のアップデート確認、読書時間の記録 |
| AppTheme.kt | WintTheme、AccentPreset、seededScheme（1色から配色を作る）、AMOLED、contentColorOn |
| ThemePicker.kt | 設定の色の丸（ThemeColorGrid）、AmoledRow、CustomColorDialog（見本・色見本・色相/彩度/明るさ・16進数） |
| LibraryScreen.kt | 本棚（カテゴリ・並び替え・検索・スワイプ・「N/M話」「未読N」・題名の折り返し） |
| BunkoScreen.kt | 文庫タブ（サイト一覧と編集）、BrowserScreen（アプリ内ブラウザ・本棚に追加・外部ブラウザ） |
| TocScreen.kt | 目次（更新・全話保存・既読/未読の一括変更） |
| ReaderScreen.kt | リーダーの Compose 部分（ツール・メニュー・表示設定・検索バー・自動スクロール・夜の配色・明るさ・向き） |
| ReaderWebView.kt | ReaderSettings、ColorPresets、ReaderBridge、ReaderController（Kotlin → JS）、createReaderWebView（GestureDetector） |
| NovelDocument.kt | 共通本文モデル（ブロック：段落・ルビ・傍点・挿絵・区切り、lang） |
| Sites.kt | SiteAdapter、SiteRegistry、FetchedWork/FetchedChapter、WorkRef、HtmlConverter |
| NarouAdapter.kt / KakuyomuAdapter.kt / HamelnAdapter.kt | 各サイト。カクヨムは `__NEXT_DATA__` の JSON、ハーメルンは .episode-list / ul.entry |
| MoreAdapters.kt | ノベルアップ＋・アルファポリス・pixiv |
| BookAdapters.kt | 青空文庫・Gutenberg（`canCheckUpdates = false`、1冊を見出しで話に分ける） |
| Http.kt | SiteHttp（WebView と Cookie・UA を共有、ホストごとの間隔、getBytes）、SiteChallengeException |
| Database.kt | Room：novels / chapters / categories / bookmarks、各 DAO、ChapterStats |
| LibraryRepository.kt | 取得・保存・更新確認・全話保存・バックアップ・既読管理・全削除・サンプル削除 |
| Storage.kt | DataStore：ReaderPrefs、AppPrefs（theme・afterAdd・prefetchCount・accent・customColor・amoled）、読書記録、文庫の並び、本棚の並び など |
| Backup.kt | バックアップの形式 |
| SettingsScreen.kt | 設定画面 |
| HelpScreen.kt | ヘルプ（読書メーターと使い方） |
| Updater.kt | GitHub Releases からのアップデート（UpdateInfo・check・download・install・UpdateDialog） |
| Icons.kt | WIcons（自前の線画アイコン） |
| SampleDocument.kt | ほぼ空（旧サンプル） |

## リーダーのエンジン（app/src/main/assets/reader/）

- reader.html / reader.css / reader.js。WebViewAssetLoader で読み込む
- `window.Reader` の API：
  `load, applySettings, setMode, next, prev, goToPosition(b,o), goToPage(i), getCurrentPosition, relayout, pagesOf,
  switchChapter, setEdgeLabels(prev,next,action), showNotice(text,ms,dir), setSwipeEnabled,
  search(q), showHit(k), clearSearch, startAuto(speed), stopAuto`
- JS → Kotlin（ReaderBridge）：`onReady(totalPages)`, `onPageChanged(page,total,block,offset)`, `onEdge(direction)`, `onAutoStop()`
- 設定（DEFAULT_SETTINGS）：文字の大きさ・行の高さ・余白・色・書体・めくり方・showImages・showTitle（常に true）
- 英語の作品は `.page-content.latin`（欧文書体・両端揃え・ハイフネーション・字下げ）
- 折り返しめくりは partialClone で必要なブロックだけ複製（.search-hit は除く）

## リソース

- `res/drawable/ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml`（開いた本＋栞）、背景 #E8DCC4
- `res/values/themes.xml`（Light）、`res/values-night/themes.xml`（ダーク端末用の起動時の下地）
- `res/xml/update_paths.xml`（FileProvider：cache/updates/）
- AndroidManifest.xml：REQUEST_INSTALL_PACKAGES、FileProvider（authority `${applicationId}.updates`）
