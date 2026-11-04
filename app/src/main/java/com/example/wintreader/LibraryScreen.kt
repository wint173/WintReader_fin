package com.example.wintreader

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 並び替え・表示用の小物

val SortMode.label: String
    get() = when (this) {
        SortMode.RECENT -> "最近読んだ順"
        SortMode.UPDATED -> "更新順"
        SortMode.ADDED -> "追加順"
        SortMode.TITLE -> "五十音順"
    }

private val japaneseCollator: Collator = Collator.getInstance(Locale.JAPANESE)

fun List<NovelEntity>.sortedByMode(mode: SortMode): List<NovelEntity> = when (mode) {
    SortMode.RECENT -> sortedByDescending { it.lastReadAt ?: it.addedAt }
    SortMode.UPDATED -> sortedByDescending { it.updatedAt ?: it.addedAt }
    SortMode.ADDED -> sortedByDescending { it.addedAt }
    SortMode.TITLE -> sortedWith { a, b -> japaneseCollator.compare(a.title, b.title) }
}

/** 最終閲覧からの経過時間（LIB-12） */
fun relativeTime(at: Long?, now: Long = System.currentTimeMillis()): String {
    if (at == null) return "未読"
    val min = (now - at) / 60_000
    return when {
        min < 1 -> "たった今"
        min < 60 -> "${min}分前"
        min < 60 * 24 -> "${min / 60}時間前"
        min < 60 * 24 * 30 -> "${min / (60 * 24)}日前"
        else -> SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date(at))
    }
}

/** 本棚で開いているダイアログ */
private sealed interface LibraryDialog {
    data class ConfirmDelete(val novel: NovelEntity) : LibraryDialog
    data class MoveNovel(val novel: NovelEntity) : LibraryDialog
    data object AddCategory : LibraryDialog
    data class RenameCategory(val category: CategoryEntity) : LibraryDialog
    data class ConfirmDeleteCategory(val category: CategoryEntity) : LibraryDialog
}

// ---------------------------------------------------------------- 本棚

/**
 * 本棚（LIB 要件）。
 * 上に手動で並べたカテゴリ（タップで開閉・複数同時に開ける）、その下に未分類の作品を直接並べる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenNovel: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val repo = container.repository
    val scope = rememberCoroutineScope()

    // 右へスライドしたときの更新確認（UPD-01〜05）。結果は短い知らせで出す
    suspend fun checkUpdate(novel: NovelEntity) {
        val result = runCatching { repo.checkForUpdates(novel.id) }
        val added = result.getOrNull()
        val msg = when {
            result.isFailure -> "更新を確認できませんでした（${result.exceptionOrNull()?.message}）"
            added == null -> "「${novel.title}」は更新を確認できません"
            added > 0 -> "「${novel.title}」に新しい話が${added}話あります"
            else -> "「${novel.title}」に新しい話はありません"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // Flow は remember しておく（毎回作り直すと、そのたびに読み込み直しになる）
    val novelsFlow = remember { repo.observeNovels() }
    val categoriesFlow = remember { repo.observeCategories() }
    val novels by novelsFlow.collectAsState(initial = emptyList())
    val categories by categoriesFlow.collectAsState(initial = emptyList())
    val statsFlow = remember { repo.observeChapterStats() }
    val statsList by statsFlow.collectAsState(initial = emptyList())
    val stats = remember(statsList) { statsList.associateBy { it.novelId } }
    val librarySort by container.settingsStore.librarySort.collectAsState(initial = SortMode.RECENT)

    var query by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    // 本棚内の検索（LIB-07）：タイトルと作者名
    val visible = if (query.isBlank()) novels else novels.filter {
        it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
    }
    val categoryIds = categories.map { it.id }.toSet()
    val uncategorized = visible.filter { it.categoryId == null || it.categoryId !in categoryIds }
        .sortedByMode(librarySort)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("本棚") },
            // 上の余白は外側の Scaffold が付けるので、ここでは付けない
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                TextButton(onClick = { dialog = LibraryDialog.AddCategory }) { Text("＋カテゴリ") }
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(librarySort.label) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                onClick = {
                                    sortMenu = false
                                    scope.launch { container.settingsStore.saveLibrarySort(m) }
                                },
                            )
                        }
                    }
                }
            },
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("作品を検索") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyColumn(Modifier.fillMaxSize()) {
            categories.forEachIndexed { index, category ->
                val inCategory = visible.filter { it.categoryId == category.id }
                    .sortedByMode(runCatching { SortMode.valueOf(category.sortMode) }.getOrDefault(SortMode.RECENT))
                // 検索中は、該当する作品があるカテゴリだけを開いて表示する
                if (query.isNotBlank() && inCategory.isEmpty()) return@forEachIndexed
                val expanded = category.isExpanded || query.isNotBlank()

                item(key = "cat-${category.id}") {
                    CategoryHeader(
                        category = category,
                        count = inCategory.size,
                        expanded = expanded,
                        canMoveUp = index > 0,
                        canMoveDown = index < categories.lastIndex,
                        onToggle = { scope.launch { repo.updateCategory(category.copy(isExpanded = !category.isExpanded)) } },
                        onRename = { dialog = LibraryDialog.RenameCategory(category) },
                        onSortChange = { m -> scope.launch { repo.updateCategory(category.copy(sortMode = m.name)) } },
                        onMove = { delta ->
                            val list = categories.toMutableList()
                            val target = index + delta
                            if (target in list.indices) {
                                list.add(target, list.removeAt(index))
                                scope.launch { repo.reorderCategories(list) }
                            }
                        },
                        onDelete = { dialog = LibraryDialog.ConfirmDeleteCategory(category) },
                    )
                }
                if (expanded) {
                    items(inCategory, key = { "novel-${it.id}" }) { novel ->
                        NovelRow(
                            novel = novel,
                            stats = stats[novel.id],
                            indent = true,
                            onOpen = { onOpenNovel(novel.id) },
                            onMove = { dialog = LibraryDialog.MoveNovel(novel) },
                            onDelete = { dialog = LibraryDialog.ConfirmDelete(novel) },
                            onCheckUpdate = { checkUpdate(novel) },
                        )
                    }
                }
            }

            // 未分類の作品はフォルダに入れず、すべてのカテゴリより下に直接並べる（LIB-36, 37）
            items(uncategorized, key = { "novel-${it.id}" }) { novel ->
                NovelRow(
                    novel = novel,
                    stats = stats[novel.id],
                    indent = false,
                    onOpen = { onOpenNovel(novel.id) },
                    onMove = { dialog = LibraryDialog.MoveNovel(novel) },
                    onDelete = { dialog = LibraryDialog.ConfirmDelete(novel) },
                    onCheckUpdate = { checkUpdate(novel) },
                )
            }

            if (novels.isEmpty()) {
                item {
                    Text(
                        "本棚に作品がありません。文庫タブから作品を探して追加できます（M3）。",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- ダイアログ
    when (val d = dialog) {
        is LibraryDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("作品を削除") },
            text = { Text("「${d.novel.title}」を本棚から削除します。保存した本文・読書位置・栞もまとめて消えます。元のサイトの作品には影響しません。") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch { repo.deleteNovel(d.novel.id) }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("やめる") } },
        )

        is LibraryDialog.MoveNovel -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("カテゴリを選ぶ") },
            text = {
                Column {
                    (listOf<CategoryEntity?>(null) + categories).forEach { c ->
                        val selected = d.novel.categoryId == c?.id
                        Text(
                            text = (if (selected) "● " else "○ ") + (c?.name ?: "未分類"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dialog = null
                                    scope.launch { repo.setCategory(d.novel.id, c?.id) }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } },
        )

        LibraryDialog.AddCategory -> NameDialog(
            title = "カテゴリを追加",
            initial = "",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                scope.launch { repo.addCategory(name) }
            },
        )

        is LibraryDialog.RenameCategory -> NameDialog(
            title = "カテゴリ名を変更",
            initial = d.category.name,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                scope.launch { repo.updateCategory(d.category.copy(name = name)) }
            },
        )

        is LibraryDialog.ConfirmDeleteCategory -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("カテゴリを削除") },
            text = { Text("「${d.category.name}」を削除します。中の作品は未分類に戻ります（作品は消えません）。") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch { repo.deleteCategory(d.category.id) }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("やめる") } },
        )

        null -> Unit
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("カテゴリ名") })
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

// ---------------------------------------------------------------- カテゴリの見出し

@Composable
private fun CategoryHeader(
    category: CategoryEntity,
    count: Int,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onSortChange: (SortMode) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(if (expanded) "▼" else "▶", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            "${category.name}（$count）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menu = true }) { Text("…") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menu = false; onRename() })
                SortMode.entries.forEach { m ->
                    val current = category.sortMode == m.name
                    DropdownMenuItem(
                        text = { Text((if (current) "● " else "　 ") + "並び順：" + m.label) },
                        onClick = { menu = false; onSortChange(m) },
                    )
                }
                if (canMoveUp) DropdownMenuItem(text = { Text("上へ移動") }, onClick = { menu = false; onMove(-1) })
                if (canMoveDown) DropdownMenuItem(text = { Text("下へ移動") }, onClick = { menu = false; onMove(1) })
                DropdownMenuItem(
                    text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
    HorizontalDivider()
}

// ---------------------------------------------------------------- 作品の行（左スワイプで操作メニュー LIB-42）

@Composable
private fun NovelRow(
    novel: NovelEntity,
    stats: ChapterStats?,
    indent: Boolean,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    /** 右へスライドして離したときの更新確認 */
    onCheckUpdate: suspend () -> Unit,
) {
    val density = LocalDensity.current
    val actionWidth = 160.dp
    val maxReveal = with(density) { actionWidth.toPx() }
    // 右へは最大 maxPull までずらせて、updateTrigger 以上ずらして離すと更新を確かめる
    val maxPull = with(density) { 120.dp.toPx() }
    val updateTrigger = with(density) { 80.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp),
    ) {
        // 右へずらすと左から見える「更新確認」（十分ずらすと濃くなる）
        if (offset.value > 0f) {
            val ready = offset.value >= updateTrigger
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (ready) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    )
                    .padding(start = 24.dp),
            ) {
                Icon(
                    WIcons.Refresh,
                    contentDescription = "更新を確認",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer { rotationZ = (offset.value / updateTrigger).coerceAtMost(1f) * 270f },
                )
            }
        }

        // 下に隠れている操作ボタン（右端）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .fillMaxHeight(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF7A7F87))
                    .clickable {
                        scope.launch { offset.animateTo(0f) }
                        onMove()
                    },
                contentAlignment = Alignment.Center,
            ) { Text("移動", color = Color.White) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFFD32F2F))
                    .clickable {
                        scope.launch { offset.animateTo(0f) }
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) { Text("削除", color = Color.White) }
        }

        // 作品の情報（左へずらすと、下の操作ボタンが見える）
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 右へは引くほど重くする
                        val next = offset.value + if (offset.value > 0f) delta * (1f - offset.value / maxPull).coerceAtLeast(0.15f) else delta
                        scope.launch { offset.snapTo(next.coerceIn(-maxReveal, if (checking) 0f else maxPull)) }
                    },
                    onDragStopped = {
                        if (offset.value > 0f) {
                            // 右：十分ずらして離したら更新を確かめる。行はすぐ元に戻す
                            val go = offset.value >= updateTrigger
                            scope.launch { offset.animateTo(0f) }
                            if (go && !checking) scope.launch {
                                checking = true
                                try { onCheckUpdate() } finally { checking = false }
                            }
                        } else {
                            // 左：半分以上ずらしていたら開いたまま、そうでなければ閉じる
                            val target = if (offset.value < -maxReveal / 2) -maxReveal else 0f
                            scope.launch { offset.animateTo(target) }
                        }
                    },
                )
                .clickable {
                    if (offset.value != 0f) scope.launch { offset.animateTo(0f) } else onOpen()
                },
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp),
            ) {
                Text(
                    novel.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        relativeTime(novel.lastReadAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // どこまで読んでいるか（43/120話）と、未読の話数（LIB-09）
                    if (stats != null && stats.total > 0) {
                        Spacer(Modifier.width(10.dp))
                        val current = stats.currentOrder?.let { (it + 1).coerceAtMost(stats.total) }
                        Text(
                            if (current != null) "$current/${stats.total}話" else "${stats.total}話",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        val unread = stats.total - stats.readCount
                        if (unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "未読$unread",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (checking) "更新を確認中…" else "最新：${novel.latestChapterTitle ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}
