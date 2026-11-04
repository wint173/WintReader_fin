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

## 構成

- `app/src/main/assets/reader/` … 本文表示エンジン（reader.html / reader.css / reader.js）。Kotlinからは `window.Reader`、JSからは `ReaderBridge` で通信
- `app/src/main/java/com/example/wintreader/` … Kotlin。**ファイルはこの階層に平置きする**
  （Claudeの作業環境が、接続フォルダから7階層より深いファイルを読み書きできないため。サブパッケージのフォルダは作らない）

## 技術スタック

Kotlin / Jetpack Compose / WebView（androidx.webkit の WebViewAssetLoader）/ kotlinx.serialization。
M2以降：Room、DataStore、OkHttp、Jsoup、WorkManager。minSdk 29。

## マイルストーン

- M1 独自リーダー（サンプル本文）← 現在
- M2 本棚・目次・栞
- M3 文庫タブ・なろう系
- M4 カクヨム・ハーメルン
- M5 仕上げ
