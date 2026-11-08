package com.example.wintreader

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 累計読書時間の表示（「12時間34分」など） */
fun formatReadingTime(millis: Long): String {
    val minutes = millis / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}時間${m}分" else "${m}分"
}

/** 操作ガイドの1項目 */
private data class Guide(val title: String, val lines: List<String>)

private val Guides = listOf(
    Guide(
        "作品を本棚に追加する",
        listOf(
            "文庫タブからサイトを開き、作品のページ（または話のページ）を開くと、下に「本棚に追加」が出ます。",
            "追加したあとの動き（サイトに残る・本棚に戻る・その作品を開く）は設定で選べます。",
            "すでに本棚にある作品のページでは、「本棚に追加」の代わりに「読む」が出ます。",
        ),
    ),
    Guide(
        "文庫タブのサイトを並べ替える",
        listOf(
            "文庫タブ右上の「編集」で、サイトの並び順（▲▼）と、表示するかどうか（チェック）を変えられます。",
            "使わないサイトは非表示にしておくと、一覧がすっきりします。",
        ),
    ),
    Guide(
        "本棚の操作",
        listOf(
            "作品を左へずらすと「移動」「削除」が出ます。削除すると、目次・栞・保存した本文もまとめて消えます（カテゴリは残ります）。",
            "作品を右へずらして離すと、その作品の更新を確認します。",
            "作品の下の段には、最後に読んだ時間・今読んでいる話（43/120話 など）・未読の話数・最新話のタイトルが出ます。",
            "「＋カテゴリ」でカテゴリを作り、カテゴリの「…」から名前の変更・並び順・削除ができます。",
            "右上で並び順（最近読んだ順・更新順・追加順・タイトル順）を切り替えます。",
            "右上の線のアイコンで、長い作品名を折り返して全部表示するか、1行にまとめるかを切り替えます。",
        ),
    ),
    Guide(
        "読書中の操作",
        listOf(
            "タップ操作：左をタップで次のページ、右をタップで前のページ（表示設定で逆にできます）。中央をタップするとメニューが出ます。",
            "スワイプ操作：ページの端をつまんでめくります。スクロール操作：上下にスクロールして読みます。スクロール操作では、画面のどこをタップしてもメニューが出ます。",
            "話の最後でさらに進むと、端に次の話のタイトルが出ます。引っ張って離す（タップ操作なら、もう一度タップ）と次の話へ移ります。",
            "最終話の次でさらに進むと、新しい話がないか確かめます。新しい話があれば、そのまま次の話へ移ります。",
        ),
    ),
    Guide(
        "メニュー（中央をタップ）",
        listOf(
            "上：本棚へ戻る・自動スクロール（三角で開始、四角で停止）・月（夜の配色）・ルーペ（この話の中を検索）・地球儀（元のサイトのこの話を開く）・栞。",
            "下：ページのスライダー、目次（左下）、前の話・次の話、表示設定。いちばん下に今の話のタイトルと「30/81話」のような話数が出ます。",
            "画面の向き（自動・縦・横）と自動スクロールの速さは、表示設定で選びます。",
            "表示設定のいちばん上の太陽のスライダーで、読書画面だけの明るさを変えられます（「自動」で端末の明るさに戻ります）。",
            "表示設定では、文字の大きさ・行の高さ・操作・ページ送りの向き・めくり方・挿絵・画面の向き・色を選べます。設定は全作品で共通です。",
        ),
    ),
    Guide(
        "目次の見かた",
        listOf(
            "まだ読んでいない話は太字、読み終えた話は薄い文字で表示し、右端に「未読」「既読」「読書中」と出ます。",
            "話を長押しすると「ここまで既読にする」「ここから未読にする」「この話だけ既読（未読）にする」を選べます。ほかで読んだ作品を追加したときに便利です。",
            "右上の縦の三点から、すべての話をまとめて既読・未読にできます。",
            "栞のある話には赤い「栞」の印が付きます。章の見出しがある作品は、章ごとに区切って表示します。",
            "右上の矢印の円で、目次を取り直して新しい話を確かめます（読書位置や既読の印はそのまま残ります）。",
            "元のサイトで消えた話は、保存してあれば「［元サイトで削除］」の印を付けて目次の最後に残します。",
        ),
    ),
    Guide(
        "元のサイトで読む",
        listOf(
            "メニューの地球儀で、今読んでいる話を元のサイトのページで開きます。挿絵やコメントを見たいときに使えます。",
            "サイトのページを閉じると、読んでいた所からそのまま続きを読めます。右上の矢印付きの四角で、Chrome などのブラウザでも開けます。",
        ),
    ),
    Guide(
        "自動スクロール",
        listOf(
            "メニュー上の三角を押すと始まり、四角を押すと止まります。話の終わりまで来ると自動で止まります。",
            "動いている間だけスクロール操作に切り替わり、一定の速さで流れます。止めると元の操作（タップ・スワイプ）に戻ります。",
            "指で触れている間は止まり、離すと少しして再開します。",
            "速さは表示設定の二重矢印のスライダーで変えられます（1 はゆっくり、10 は流し読みできるくらい速い）。",
        ),
    ),
    Guide(
        "この話の中を検索する",
        listOf(
            "メニュー上のルーペで検索欄が開きます。言葉を入れて決定すると、今読んでいる所より後で最初に見つかった所へ移ります。",
            "見つかった所には薄い色の帯が付きます。右の山形の矢印で前後の候補へ移れます。探すのは今の話の中だけです。",
        ),
    ),
    Guide(
        "全話を保存する",
        listOf(
            "目次の画面の右上の下向き矢印で、まだ保存していない話をすべて保存します。通信のない所でも読めるようになります。",
            "サイトに負担をかけないよう、1話ずつ間を空けて取ります。途中で止めても、もう一度押せば続きから保存します。",
        ),
    ),
    Guide(
        "栞",
        listOf(
            "読書中にメニューの栞のアイコンを押すと、そのページに栞が付きます（もう一度押すと外れます）。",
            "目次の画面の「栞」タブから、栞の場所へ移れます。長押しで削除できます。",
        ),
    ),
    Guide(
        "見た目を変える",
        listOf(
            "読書画面の色は表示設定の丸から選びます。メニュー上の月を押すと、すぐに夜の配色に切り替わります。",
            "本棚やヘルプなど、アプリ全体の明るい・暗い見た目は、設定の「表示」で選べます。",
            "アプリの基調の色は設定の「テーマの色」で選べます（端末の色・ホワイト・クリムゾン・オーシャンなど）。鉛筆の丸を押すと、色相・彩度・明るさのつまみや 16進数で好きな色を作れます。",
            "「AMOLED ブラック」をオンにすると、ダークのときに背景が真っ黒になります。",
            "英語の作品（Project Gutenberg など）は、読みやすいよう両端をそろえ、欧文の書体で表示します。",
        ),
    ),
    Guide(
        "保存した本文と容量",
        listOf(
            "読んだ話と、その先の数話（先読み）は端末に保存され、通信のない所でも読めます。先読みする話数は設定で変えられます。",
            "容量が気になるときは、設定の「保存した本文を削除」で本文だけを消せます。本棚・栞・読書位置は残り、開いたときに取り直します。",
        ),
    ),
    Guide(
        "本文が読めないとき",
        listOf(
            "通信できないときは、保存してある話だけ読めます。電波のある所でもう一度開いてください。",
            "「サイトの確認が必要です」と出たときは、文庫からそのサイトのページを一度開いてから、もう一度お試しください。",
            "ログインが必要な作品は、文庫からサイトを開いてログインしておくと読めるようになります。",
            "アルファポリスのレンタルの話など、サイトで有料・限定になっている話は読めません。",
        ),
    ),
    Guide(
        "読書メーター",
        listOf(
            "読書時間は、読書画面を開いていた時間の合計です（同じ画面のまま長く放置した分は数えすぎないようにしています）。",
            "使った日は、アプリを開いた日の合計です。設定の「読書記録をリセット」で 0 に戻せます。",
        ),
    ),
    Guide(
        "アプリのアップデート",
        listOf(
            "GitHub に新しい版が出ると、アプリを開いたときに知らせます（半日に1回確かめます）。設定の「アップデートを確認」からも確かめられます。",
            "「アップデート」を押すとダウンロードして、Android のインストール画面が開きます。本棚や設定はそのまま残ります。",
            "初めてのときは「この提供元のアプリを許可」をオンにしてから、もう一度「インストール」を押してください。",
        ),
    ),
    Guide(
        "本棚・栞を別の端末へ移す",
        listOf(
            "設定の「バックアップ」で、本棚・カテゴリ・既読の印・栞・読書位置をファイルに書き出せます。",
            "別の端末でそのファイルを読み込むと、本棚がその内容に置き換わります。本文はファイルに含まれず、開いたときにサイトから取り直します。",
        ),
    ),
)

/**
 * ヘルプタブ（HELP-01〜05）。上に読書メーター、下に操作ガイド。右上の歯車から設定へ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onOpenSettings: () -> Unit) {
    val container = LocalContext.current.appContainer
    val statsFlow = remember { container.settingsStore.stats }
    val novelsFlow = remember { container.repository.observeNovels() }
    val stats by statsFlow.collectAsState(initial = ReadingStats())
    val novels by novelsFlow.collectAsState(initial = emptyList())
    var opened by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("ヘルプ") },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(WIcons.Settings, contentDescription = "設定", modifier = Modifier.size(24.dp))
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                // 読書メーター
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    MeterCard("読書時間", formatReadingTime(stats.readingMillis), Modifier.weight(1f))
                    MeterCard("本棚の作品", "${novels.size}作品", Modifier.weight(1f))
                    MeterCard("使った日", "${stats.usedDays}日", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "使い方",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
            }
            items(Guides) { g ->
                val isOpen = opened == g.title
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { opened = if (isOpen) null else g.title }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (isOpen) "−" else "＋", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isOpen) {
                        Spacer(Modifier.height(8.dp))
                        g.lines.forEach { line ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text("・", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MeterCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
