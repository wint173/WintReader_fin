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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.Job
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

/**
 * カテゴリの親子関係。top = いちばん上のカテゴリ、childrenOf = その中のサブカテゴリ（どちらも displayOrder 順）。
 * 親が消えている・2段より深いなど、想定外の形のものは top に入れて、作品が見えなくならないようにする
 */
class CategoryTree private constructor(
    val top: List<CategoryEntity>,
    private val children: Map<Long, List<CategoryEntity>>,
) {
    fun childrenOf(id: Long): List<CategoryEntity> = children[id].orEmpty()

    companion object {
        fun of(categories: List<CategoryEntity>): CategoryTree {
            val ids = categories.map { it.id }.toSet()
            val roots = categories.filter { it.parentId == null || it.parentId !in ids || it.parentId == it.id }
            val rootIds = roots.map { it.id }.toSet()
            val subs = categories.filter { it.parentId in rootIds && it !in roots }
            val rest = categories - roots.toSet() - subs.toSet()
            return CategoryTree(
                top = (roots + rest).sortedBy { it.displayOrder },
                children = subs.groupBy { it.parentId!! }.mapValues { (_, v) -> v.sortedBy { it.displayOrder } },
            )
        }
    }
}

/** 本棚で開いているダイアログ */
private sealed interface LibraryDialog {
    data class ConfirmDelete(val novel: NovelEntity) : LibraryDialog
    data class MoveNovel(val novel: NovelEntity) : LibraryDialog
    /** parent があれば、そのカテゴリの中にサブカテゴリを足す */
    data class AddCategory(val parent: CategoryEntity? = null) : LibraryDialog
    data class RenameCategory(val category: CategoryEntity) : LibraryDialog
    data class ConfirmDeleteCategory(val category: CategoryEntity) : LibraryDialog
    /** カテゴリごとの更新確認の結果 */
    data class UpdateResult(val categoryName: String, val result: UpdateCheckResult) : LibraryDialog
}

/** カテゴリごとの更新確認の進み具合 */
private data class BatchCheck(val categoryName: String, val done: Int, val total: Int)

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
    val fullTitlesFlow = remember { container.settingsStore.libraryFullTitles }
    val fullTitles by fullTitlesFlow.collectAsState(initial = false)

    var query by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    // カテゴリごとの更新確認（同時には1つだけ）。本棚の画面を離れると止まる
    var batch by remember { mutableStateOf<BatchCheck?>(null) }
    var batchJob by remember { mutableStateOf<Job?>(null) }

    /** カテゴリ（いちばん上なら、その中のサブカテゴリも）の作品の更新をまとめて確かめる */
    fun checkCategory(category: CategoryEntity, tree: CategoryTree) {
        if (batchJob?.isActive == true) return
        val ids = setOf(category.id) + tree.childrenOf(category.id).map { it.id }
        val targets = novels.filter { it.categoryId in ids }.map { it.id }
        if (targets.isEmpty()) {
            Toast.makeText(context, "「${category.name}」に作品がありません", Toast.LENGTH_SHORT).show()
            return
        }
        batch = BatchCheck(category.name, 0, targets.size)
        batchJob = scope.launch {
            // 「やめる」で取り消したときは結果を出さない（途中までに取り直した目次はそのまま残る）
            try {
                val result = repo.checkUpdatesFor(targets) { done, total -> batch = BatchCheck(category.name, done, total) }
                dialog = LibraryDialog.UpdateResult(category.name, result)
            } finally {
                batch = null
            }
        }
    }

    // 本棚内の検索（LIB-07）：タイトルと作者名
    val visible = if (query.isBlank()) novels else novels.filter {
        it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
    }
    val categoryIds = categories.map { it.id }.toSet()
    val uncategorized = visible.filter { it.categoryId == null || it.categoryId !in categoryIds }
        .sortedByMode(librarySort)
    // カテゴリを「いちばん上」と「その中のサブカテゴリ（1段だけ）」に分ける。
    // 親が見つからないもの・2段より深いものは、いちばん上として出す（作品が見えなくならないように）
    val tree = remember(categories) { CategoryTree.of(categories) }
    fun sortOf(c: CategoryEntity) = runCatching { SortMode.valueOf(c.sortMode) }.getOrDefault(SortMode.RECENT)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("本棚") },
            // 上の余白は外側の Scaffold が付けるので、ここでは付けない
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // 長い作品名を全部表示するか（1行で省略するか）を切り替える
                IconButton(onClick = { scope.launch { container.settingsStore.saveLibraryFullTitles(!fullTitles) } }) {
                    Icon(
                        if (fullTitles) WIcons.TitleWrap else WIcons.TitleOneLine,
                        contentDescription = if (fullTitles) "作品名を1行にまとめる" else "作品名を全部表示する",
                        tint = if (fullTitles) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                TextButton(onClick = { dialog = LibraryDialog.AddCategory() }) { Text("＋カテゴリ") }
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

        // カテゴリごとの更新確認の進み具合と「やめる」
        batch?.let { b ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "「${b.categoryName}」の更新を確認中　${b.done}/${b.total}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { batchJob?.cancel() }) { Text("やめる") }
                }
                LinearProgressIndicator(
                    progress = { if (b.total == 0) 0f else b.done.toFloat() / b.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // カテゴリの見出し1つ分。siblings は同じ親の中の並び（上へ・下へ移動はこの中で入れ替える）
            fun header(category: CategoryEntity, siblings: List<CategoryEntity>, count: Int, expanded: Boolean, level: Int) {
                val index = siblings.indexOf(category)
                item(key = "cat-${category.id}") {
                    CategoryHeader(
                        category = category,
                        count = count,
                        expanded = expanded,
                        level = level,
                        canMoveUp = index > 0,
                        canMoveDown = index < siblings.lastIndex,
                        onToggle = { scope.launch { repo.updateCategory(category.copy(isExpanded = !category.isExpanded)) } },
                        onRename = { dialog = LibraryDialog.RenameCategory(category) },
                        onSortChange = { m -> scope.launch { repo.updateCategory(category.copy(sortMode = m.name)) } },
                        onMove = { delta ->
                            val list = siblings.toMutableList()
                            val target = index + delta
                            if (target in list.indices) {
                                list.add(target, list.removeAt(index))
                                scope.launch { repo.reorderCategories(list) }
                            }
                        },
                        // サブカテゴリの中には、さらにサブカテゴリを作らない（1段だけ）
                        onAddSub = if (level == 0) ({ dialog = LibraryDialog.AddCategory(parent = category) }) else null,
                        checking = batch != null,
                        onCheckUpdates = { checkCategory(category, tree) },
                        onDelete = { dialog = LibraryDialog.ConfirmDeleteCategory(category) },
                    )
                }
            }

            fun novelRows(list: List<NovelEntity>, level: Int) {
                items(list, key = { "novel-${it.id}" }) { novel ->
                    NovelRow(
                        novel = novel,
                        stats = stats[novel.id],
                        fullTitle = fullTitles,
                        level = level,
                        onOpen = { onOpenNovel(novel.id) },
                        onMove = { dialog = LibraryDialog.MoveNovel(novel) },
                        onDelete = { dialog = LibraryDialog.ConfirmDelete(novel) },
                        onCheckUpdate = { checkUpdate(novel) },
                    )
                }
            }

            tree.top.forEach { category ->
                val subs = tree.childrenOf(category.id)
                val direct = visible.filter { it.categoryId == category.id }.sortedByMode(sortOf(category))
                val inSubs = subs.associate { s -> s.id to visible.filter { it.categoryId == s.id }.sortedByMode(sortOf(s)) }
                // 数はサブカテゴリの中の作品も含める
                val total = direct.size + inSubs.values.sumOf { it.size }
                // 検索中は、該当する作品があるカテゴリだけを開いて表示する
                if (query.isNotBlank() && total == 0) return@forEach
                val expanded = category.isExpanded || query.isNotBlank()

                header(category, tree.top, total, expanded, level = 0)
                if (expanded) {
                    // サブカテゴリを先に、そのあとにこのカテゴリに直接入っている作品を並べる
                    subs.forEach { sub ->
                        val list = inSubs[sub.id].orEmpty()
                        if (query.isNotBlank() && list.isEmpty()) return@forEach
                        val subExpanded = sub.isExpanded || query.isNotBlank()
                        header(sub, subs, list.size, subExpanded, level = 1)
                        if (subExpanded) novelRows(list, level = 2)
                    }
                    novelRows(direct, level = 1)
                }
            }

            // 未分類の作品はフォルダに入れず、すべてのカテゴリより下に直接並べる（LIB-36, 37）
            novelRows(uncategorized, level = 0)

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
                // カテゴリが多いと画面に収まらないので、中だけスクロールできるようにする
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 未分類 → カテゴリ → その中のサブカテゴリ（字下げ）の順
                    val choices = listOf<Pair<CategoryEntity?, Int>>(null to 0) +
                        tree.top.flatMap { top -> listOf(top to 0) + tree.childrenOf(top.id).map { it to 1 } }
                    choices.forEach { (c, level) ->
                        val selected = d.novel.categoryId == c?.id
                        Text(
                            text = (if (selected) "● " else "○ ") + (c?.name ?: "未分類"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dialog = null
                                    scope.launch { repo.setCategory(d.novel.id, c?.id) }
                                }
                                .padding(start = (level * 20).dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } },
        )

        is LibraryDialog.AddCategory -> NameDialog(
            title = if (d.parent == null) "カテゴリを追加" else "「${d.parent.name}」にサブカテゴリを追加",
            initial = "",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                scope.launch {
                    repo.addCategory(name, parentId = d.parent?.id)
                    // 足したサブカテゴリが見えるように、親を開いておく
                    if (d.parent != null && !d.parent.isExpanded) repo.updateCategory(d.parent.copy(isExpanded = true))
                }
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
            text = {
                val parent = categories.firstOrNull { it.id == d.category.parentId }
                val hasSubs = tree.childrenOf(d.category.id).isNotEmpty()
                Text(
                    when {
                        parent != null -> "「${d.category.name}」を削除します。中の作品は「${parent.name}」に移ります（作品は消えません）。"
                        hasSubs -> "「${d.category.name}」と、その中のサブカテゴリを削除します。中の作品はすべて未分類に戻ります（作品は消えません）。"
                        else -> "「${d.category.name}」を削除します。中の作品は未分類に戻ります（作品は消えません）。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch { repo.deleteCategory(d.category.id) }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("やめる") } },
        )

        is LibraryDialog.UpdateResult -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("「${d.categoryName}」の更新確認") },
            text = {
                val r = d.result
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (r.updated.isEmpty()) "${r.checked}作品を確かめました。新しい話はありません。"
                        else "${r.checked}作品を確かめました。${r.updated.size}作品に新しい話があります。",
                    )
                    r.updated.forEach { (novel, added) ->
                        Text(
                            "・${novel.title}（＋${added}話）",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (r.failed.isNotEmpty()) {
                        Text("確かめられなかった作品（${r.failed.size}）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        r.failed.forEach { (novel, reason) ->
                            Text(
                                "・${novel.title}：$reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    if (r.notSupported > 0) {
                        Text(
                            "青空文庫などの${r.notSupported}作品は、更新がないので確かめていません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } },
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
    /** 0 = いちばん上のカテゴリ、1 = サブカテゴリ（字下げして少し薄く出す） */
    level: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onSortChange: (SortMode) -> Unit,
    onMove: (Int) -> Unit,
    /** サブカテゴリを足す（サブカテゴリの見出しでは null） */
    onAddSub: (() -> Unit)?,
    /** どこかのカテゴリの更新確認が動いている間は、ほかを始めない */
    checking: Boolean,
    onCheckUpdates: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (level == 0) 0.5f else 0.25f))
            .padding(start = (16 + level * 16).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
                DropdownMenuItem(
                    text = { Text(if (level == 0) "更新を確認（サブカテゴリも）" else "更新を確認") },
                    enabled = !checking,
                    onClick = { menu = false; onCheckUpdates() },
                )
                DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menu = false; onRename() })
                if (onAddSub != null) DropdownMenuItem(text = { Text("サブカテゴリを追加") }, onClick = { menu = false; onAddSub() })
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
    /** 長い作品名を折り返して全部出すか */
    fullTitle: Boolean,
    /** 字下げの段数（0 = 未分類、1 = カテゴリの中、2 = サブカテゴリの中） */
    level: Int,
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

    // 作品名を全部出すときは行の高さが変わるので、高さは中身に合わせる（最低 76dp）
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
    ) {
        // 右へずらすと左から見える「更新確認」（十分ずらすと濃くなる）
        if (offset.value > 0f) {
            val ready = offset.value >= updateTrigger
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .matchParentSize()
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
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
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
        }

        // 作品の情報（左へずらすと、下の操作ボタンが見える）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
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
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .padding(start = (16 + level * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    novel.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (fullTitle) Int.MAX_VALUE else 1,
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
