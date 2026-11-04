package com.example.wintreader

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 確かめてから実行する操作 */
private enum class Confirm { RESTORE, DELETE_SAVED, RESET_STATS, DELETE_ALL }

/**
 * アプリの設定（SET / BAK）。ヘルプタブの右上から開く。
 * 表示・本棚・ダウンロード・バックアップ・読書記録・その他の順に並べる。
 */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val store = container.settingsStore
    val repo = container.repository
    val scope = rememberCoroutineScope()

    val prefsFlow = remember { store.appPrefs }
    val statsFlow = remember { store.stats }
    val prefs by prefsFlow.collectAsState(initial = AppPrefs())
    val stats by statsFlow.collectAsState(initial = ReadingStats())
    var savedBytes by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    // 復元するファイルの中身（確認ダイアログのあとで使う）
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) { savedBytes = repo.savedBytes() }

    fun update(p: AppPrefs) = scope.launch { store.saveAppPrefs(p) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    // バックアップの書き出し先を選んでもらう（BAK-01）
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = repo.exportBackup()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                        ?: error("ファイルを開けません")
                }
            }.onSuccess { toast("バックアップを書き出しました") }
                .onFailure { toast("書き出せませんでした（${it.message}）") }
        }
    }

    // 復元するファイルを選んでもらう（BAK-02）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("ファイルを開けません")
                }
            }.onSuccess {
                pendingRestore = it
                confirm = Confirm.RESTORE
            }.onFailure { toast("読み込めませんでした（${it.message}）") }
        }
    }

    BackHandler(onBack = onClose)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onClose) {
                    Icon(WIcons.Back, contentDescription = "戻る", modifier = Modifier.size(24.dp))
                }
                Text("設定", style = MaterialTheme.typography.titleLarge)
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section("表示")
                ThemeMode.entries.forEach { m ->
                    RadioRow(m.label, prefs.theme == m) { update(prefs.copy(theme = m)) }
                }
                Note("読書画面の色は、読書中のメニューの表示設定で選びます。")

                Section("本棚に追加したあと")
                AfterAddAction.entries.forEach { a ->
                    RadioRow(a.label, prefs.afterAdd == a) { update(prefs.copy(afterAdd = a)) }
                }

                Section("ダウンロード")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("先読みする話数")
                        Text(
                            "読んでいる話の次を、何話先まで取っておくか",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { if (prefs.prefetchCount > 0) update(prefs.copy(prefetchCount = prefs.prefetchCount - 1)) }) { Text("−") }
                    Text("${prefs.prefetchCount}話", modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { if (prefs.prefetchCount < 5) update(prefs.copy(prefetchCount = prefs.prefetchCount + 1)) }) { Text("＋") }
                }
                ActionRow(
                    title = "保存した本文を削除",
                    subtitle = "使用中：${savedBytes?.let { formatBytes(it) } ?: "計算中…"}。本棚・栞はそのまま残り、開いたときに取り直します",
                    onClick = { confirm = Confirm.DELETE_SAVED },
                )

                Section("バックアップ")
                ActionRow(
                    title = "バックアップを書き出す",
                    subtitle = "本棚・カテゴリ・既読の印・栞・読書位置をファイルに保存します（本文は含みません）",
                    onClick = {
                        val name = "wintreader-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date()) + ".json"
                        exportLauncher.launch(name)
                    },
                )
                ActionRow(
                    title = "バックアップから復元",
                    subtitle = "今の本棚は、ファイルの内容に置き換わります",
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )

                Section("読書記録")
                Note("読書時間 ${formatReadingTime(stats.readingMillis)}・使った日 ${stats.usedDays}日")
                ActionRow(title = "読書記録をリセット", subtitle = "読書時間と使った日を 0 に戻します", onClick = { confirm = Confirm.RESET_STATS })

                Section("その他")
                ActionRow(
                    title = "すべてのデータを削除",
                    subtitle = "本棚・カテゴリ・栞・保存した本文・設定・読書記録を消します",
                    danger = true,
                    onClick = { confirm = Confirm.DELETE_ALL },
                )
                // アプリ内アップデート（GitHub の Releases から）
                if (Updater.enabled) {
                    ActionRow(
                        title = if (checkingUpdate) "確認中…" else "アップデートを確認",
                        subtitle = "GitHub に新しい版があれば、ダウンロードしてインストールします",
                        onClick = {
                            if (!checkingUpdate) {
                                checkingUpdate = true
                                scope.launch {
                                    runCatching { Updater.check() }
                                        .onSuccess { found -> if (found != null) update = found else toast("最新の版です（${Updater.currentVersionName}）") }
                                        .onFailure { toast("確認できませんでした（${it.message}）") }
                                    checkingUpdate = false
                                }
                            }
                        },
                    )
                }
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
                }
                Note("WintReader バージョン $version\n個人利用の小説リーダー。作品の著作権は各作者・各サイトにあります。")
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    update?.let { UpdateDialog(it, onDismiss = { update = null }) }

    confirm?.let { c ->
        val (title, text, ok) = when (c) {
            Confirm.RESTORE -> Triple("バックアップから復元", "今の本棚・カテゴリ・栞を、ファイルの内容に置き換えます。", "復元する")
            Confirm.DELETE_SAVED -> Triple("保存した本文を削除", "端末に保存した本文をすべて消します。本棚・栞はそのまま残ります。", "削除")
            Confirm.RESET_STATS -> Triple("読書記録をリセット", "読書時間と使った日を 0 に戻します。", "リセット")
            Confirm.DELETE_ALL -> Triple("すべてのデータを削除", "本棚・カテゴリ・栞・保存した本文・設定・読書記録をすべて消します。元には戻せません。", "すべて削除")
        }
        AlertDialog(
            onDismissRequest = { confirm = null; pendingRestore = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        when (c) {
                            Confirm.RESTORE -> {
                                val json = pendingRestore ?: return@launch
                                pendingRestore = null
                                runCatching { repo.importBackup(json) }
                                    .onSuccess { n -> toast("${n}作品を復元しました") }
                                    .onFailure { toast("復元できませんでした（${it.message}）") }
                            }
                            Confirm.DELETE_SAVED -> {
                                repo.deleteSavedDocuments()
                                savedBytes = repo.savedBytes()
                                toast("保存した本文を削除しました")
                            }
                            Confirm.RESET_STATS -> {
                                store.resetStats()
                                store.markUsedToday()
                            }
                            Confirm.DELETE_ALL -> {
                                repo.deleteAllData()
                                store.clearAll()
                                savedBytes = repo.savedBytes()
                                toast("すべてのデータを削除しました")
                            }
                        }
                    }
                }) {
                    Text(ok, color = if (c == Confirm.RESTORE || c == Confirm.RESET_STATS) Color.Unspecified else MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null; pendingRestore = null }) { Text("やめる") } },
        )
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit, danger: Boolean = false) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
