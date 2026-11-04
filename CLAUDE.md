# Wint Reader

iOSアプリ「携帯小説⑩」の読書体験を再現する、個人利用のAndroid小説リーダー。
仕様の正本は claude.ai の要件定義書「Android版「携帯小説⑩」再現アプリ 要件定義書」（v1.1以降）。

## 決定事項（要約）

- 対応サイト：小説家になろう系（ノクターン等含む）・カクヨム・ハーメルン
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
- M3 文庫タブ・なろう系 ← 次
- M4 カクヨム・ハーメルン
- M5 仕上げ

## Claude 向けメモ

- reader.js / reader.css はブラウザ（Playwright + Chromium）で検証してから書き込む。実機の描画は遅いので、
  アニメーション中に大きな要素を描き直す作りは避ける
- PC へのファイル書き込みは、毎回新しい置き場所（例：outputs/revN/）から送る。同じ置き場所から送り直すと
  最初の内容が使い回されて更新されないことがあった
