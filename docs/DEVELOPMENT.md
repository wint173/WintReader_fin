# 開発の流れ・ビルド・配布

## ローカル

- PC（Windows）のプロジェクト：`C:\Users\a0906\AndroidStudioProjects\WintReader`。Android Studio で開いてビルド・実機に入れる
- ビルド：`./gradlew assembleDebug`（Windows は `gradlew.bat assembleDebug`）
- debug ビルドも専用の鍵で署名する（signingConfig "wint"）。PC は `keystore.properties` ＋ `signing/wint.jks`、
  CI は環境変数（Secrets）から。どちらもリポジトリには入れない
- `BuildConfig.GITHUB_REPO`：CI は `GITHUB_REPOSITORY`、PC は `.git/config` の origin から読む
- `versionCode = GITHUB_RUN_NUMBER + 1`（PC は 1）、`versionName = 1.0.<実行番号>`

## GitHub・配布

- 公開リポジトリ。main に push すると GitHub Actions（`.github/workflows/build-apk.yml`）が APK を作り、
  Releases（タグ `build-<実行番号>`）に `WintReader.apk` を置く。最新版：`releases/latest/download/WintReader.apk`
- Secrets：SIGNING_KEYSTORE_BASE64 ほか 3 つ（鍵のパスワード・別名など）
- アプリ内アップデート：Updater.check が `api.github.com/repos/<repo>/releases/latest` を見て、
  タグの N+1 が今の versionCode より大きければダイアログ（起動時は半日に 1 回、設定からも）
- オーナーは Android Studio の Git（Commit / Push）で push している。merge や revert で衝突したときは、
  先にコミットせず中止し、ファイルが消えていないか確かめる

## 確かめ方

- reader.js / reader.css：Playwright + Chromium で reader.html を開き、`window.Reader` を呼んで確かめる
  （CPU を遅くした模擬で長文の速さも見る）
- サイトの HTML が変わったら、そのサイトのアダプターだけを直す（HtmlConverter は共通）
- UI の変更はオーナーが実機で確かめる。動画やスクリーンショットで不具合が届くことが多い

## Cowork 時代の制約（Claude Code では不要）

- 以前は Claude がクラウドからファイルを書き込んでいたため、「7 階層より深いフォルダに触れない」
  「.github に書けない（ci/ に置いて移してもらう）」「書き込みは毎回新しい置き場所から」という制約があった。
  Claude Code ではリポジトリを直接編集・ビルドできる。ただしファイルの平置きの決まりはそのまま守る
