# Wint Reader

iOSアプリ「携帯小説⑩」の読書体験を再現する、個人利用のAndroid小説リーダー。
仕様の正本は claude.ai の要件定義書「Android版「携帯小説⑩」再現アプリ 要件定義書」（v1.1以降）。

## 決定事項（要約）

- 対応サイト：小説家になろう系（ノクターン等含む）・カクヨム・ハーメルン・アルファポリス・ノベルアップ＋・pixiv 小説・
  青空文庫・Project Gutenberg（ManyBooks は会員登録が要り意味がないので外した）。文庫タブは並び順・表示を「編集」で変えられる
- 本文はサイトから取得 → 共通本文モデル（NovelDocument）に変換 → 端末に保存 → アプリが生成したHTMLをWebViewで描画
- **横書きのみ**（縦書きは対応しない）
- タップ判定はKotlin側（GestureDetector）。左タップ＝次ページ、右タップ＝前ページ、中央＝メニュー
- スクロールモードではスクロールをWebViewに任せ、中央タップだけ拾う
- 読書位置はページ番号ではなく {block, offset}（ルビの読みは数えず親文字だけ数える）
- 栞はページ単位（リーダーのツールバーから付け外し）
- ページめくりのアニメーション：折り返し（初期値）／スライド／なし。折り返しは transform だけで動かす
  （clip-path を毎フレーム変えると実機で描画が欠けるため。切り抜きは回転した overflow:hidden の箱で行う）
- スワイプモード：指の追従だけは reader.js（JavaScript）で行う例外。上1/3は右上の角、下1/3は右下の角、中央は縦の折り目
- 話の切り替え：話の端で「引っ張って離す」（スクロール・スワイプ）か「2回タップ」。本文をずらして矢印＋話のタイトルを薄く表示
- 話を変えても ReaderScreen（WebView）は作り直さず、Reader.switchChapter で演出付きに差し替える（メニューは開いたまま）
- 中央タップのメニュー：最初はページのスライダーと前後の話。表示設定は一段下
- ツールは図のアイコン（Icons.kt の WIcons。アイコンのライブラリは使わない）。上：本棚へ・自動スクロール・月（夜の配色）・ルーペ（本文内検索）・地球儀（元サイトのこの話）・栞（タイトルは置かない）。
  下はシンプルに：目次（左下）・前後の話・表示設定、いちばん下に小さく「話のタイトル　30/81話」。表示設定の選択肢も図（明るさ・自動スクロールの速さ・画面の向きもここ）
- 最初の話の前・最終話の次：設定で「更新を確認」か「作品のページへ」。案内は話の端の矢印の場所に印と言葉で出し、
  結果も同じ場所に出す（Reader.showNotice）
- 本棚：作品を左へずらすと移動・削除、右へずらして離すと更新確認

## 構成

- `app/src/main/assets/reader/` … 本文表示エンジン（reader.html / reader.css / reader.js）。Kotlinからは `window.Reader`、JSからは `ReaderBridge` で通信
- `app/src/main/java/com/example/wintreader/` … Kotlin。**ファイルはこの階層に平置きする**
  （Claudeの作業環境が、接続フォルダから7階層より深いファイルを読み書きできないため。サブパッケージのフォルダは作らない）

## 技術スタック

Kotlin / Jetpack Compose / WebView（androidx.webkit の WebViewAssetLoader）/ kotlinx.serialization。
M2以降：Room、DataStore、OkHttp、Jsoup、WorkManager。minSdk 29。

## マイルストーン

- M1 独自リーダー（サンプル本文）… 完了
- M2 本棚・目次・栞 … 完了（Room 2.8.5 / DataStore 1.3.0 / KSP 2.3.11。画面移動は自前、Navigation Compose は不使用）
- M3 文庫タブ・なろう系 … 完了
- （M3のあとの UI 改善：図のアイコン・話の端の動作・ルーペ・本棚の右スライド）
- M4 カクヨム・ハーメルン … 完了
  （Sites.kt に SiteAdapter / SiteRegistry / HtmlConverter。サイトごとに NarouAdapter・KakuyomuAdapter・HamelnAdapter。
  カクヨムの目次は __NEXT_DATA__ の JSON、ハーメルンは .episode-list。年齢確認はアプリ内ブラウザで本人が行い、回避はしない）
- M5 仕上げ ← 進行中
  - 1：ヘルプ（読書メーター・使い方）・設定画面（テーマ・追加後の動作・先読み話数・保存した本文・バックアップ・読書記録・全削除）
    … 実装済み・実機確認待ち。テーマは AppTheme.kt の WintTheme（ui/theme は深すぎて触れないため使わない）
  - 追加サイト（M5 中に追加）：MoreAdapters.kt（ノベルアップ＋・アルファポリス・pixiv）、BookAdapters.kt（青空文庫・Gutenberg。
    1冊の HTML を見出しで話に分ける）。ノベルアップ＋は AWS WAF の確認が出ることがあり、そのときは SiteChallengeException で
    「アプリ内ブラウザで一度開いて」と案内する（回避はしない）… 実装済み・実機確認待ち
  - 2：長文対応 … 折り返しめくりの複製を「めくる2ページ分」だけにした（partialClone。上に空きを入れて段組みを元と揃える）。
    CPU 1/6 の模擬で 20万字：最初のめくり 7秒→0.1秒、組み直し 9秒→2.5秒。読み込みは約4秒（列組みの都合）
  - 3：上のツールに月（夜の配色 UI-11）、表示設定に画面の向き（UI-06）と明るさ（UI-04）、
    目次に全話の保存（CACHE-04/10/13/15。止めても続きから）。アプリアイコン（開いた本＋栞）… 実装済み・実機確認待ち
  - 4：本棚に「43/120話」と「未読N」（LIB-09。ChapterDao.observeStats）… 実装済み・実機確認待ち
  - 5：自動スクロール（READ-12。動いている間だけスクロール操作に切り替え、止めたら元の操作に戻す。速さ 1〜10 は毎秒約0.15〜4.2行）、本文内検索（READ-15。今の話だけ。Reader.search/showHit、
    .search-hit の帯は partialClone から除く）、外部ブラウザ（READ-23。アプリ内ブラウザの右上）… 実装済み・実機確認待ち
  - 6：サンプル作品とカテゴリ（ファンタジー・日常）を初回起動時に一度だけ片付ける（removeSampleData）。
    目次で既読・未読をまとめて付け替え（話の長押し：ここまで既読・ここから未読・この話だけ、右上「…」：すべて既読・未読）… 実装済み・実機確認待ち
  - 残り候補（P1）：LIB-08 一括操作、LIB-05 読了の印、UPD-06 消えた作品の表示、CACHE-09 改稿の取り直し、WEB-06 追加前の作品情報

## GitHub・配布

- 公開リポジトリ。main への push で GitHub Actions（.github/workflows/build-apk.yml）が debug 版 APK を作り、Releases に WintReader.apk を置く
  （最新版：releases/latest/download/WintReader.apk）。versionCode = 実行番号 + 1、versionName = 1.0.<実行番号>
- 署名：PC は keystore.properties ＋ signing/wint.jks、GitHub は Secrets（SIGNING_KEYSTORE_BASE64 ほか3つ）。同じ鍵なので上書き更新できる。
  鍵ファイル・パスワードは .gitignore 済みで、絶対にコミットしない
- アプリ内アップデート（Updater.kt）：GitHub API の releases/latest を見て、タグ build-<N> の N+1 が今の versionCode より大きければ
  ダイアログを出す（起動時は半日に1回、設定からも確認可）。APK は cache/updates/ にダウンロードし、FileProvider（<applicationId>.updates）で
  インストール画面へ渡す。リポジトリ名は BuildConfig.GITHUB_REPO（CI は GITHUB_REPOSITORY、PC は .git/config の origin から読む。分からなければ空で確認しない）。
  PC ビルドは versionCode 1 なので、GitHub の版があれば必ず「新しい版」と出る（同じ鍵なので、そのまま GitHub の版に移れる）
- .github フォルダは Claude のファイル操作では書けない（保護されている）。ワークフローを直すときは ci/ に置いて移してもらう

## Claude 向けメモ

- reader.js / reader.css はブラウザ（Playwright + Chromium）で検証してから書き込む。実機の描画は遅いので、
  アニメーション中に大きな要素を描き直す作りは避ける
- PC へのファイル書き込みは、毎回新しい置き場所（例：outputs/revN/）から送る。同じ置き場所から送り直すと
  最初の内容が使い回されて更新されないことがあった
