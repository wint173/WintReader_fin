package com.example.wintreader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全話の保存の進み具合 */
data class DownloadProgress(val done: Int, val total: Int, val running: Boolean, val message: String? = null)

/**
 * 目次・栞の画面（TOC / BM 要件）。
 * 上のタブで「目次」と「栞」を切り替える。既読・未読・栞のある話を見分けられるようにする。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TocScreen(
    novelTitle: String,
    currentChapterId: String,
    chapters: List<ChapterEntity>,
    bookmarks: List<BookmarkEntity>,
    onClose: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    onOpenBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    /** 目次を取り直す（LIB-15）。サイトの作品のときだけ使える */
    onRefresh: (suspend () -> Unit)? = null,
    /** 全話の保存の進み具合（null = 動いていない） */
    download: DownloadProgress? = null,
    /** 全話を保存し始める・止める（サイトの作品のときだけ） */
    onDownload: (() -> Unit)? = null,
    onStopDownload: () -> Unit = {},
    /** 既読・未読をまとめて付け替える（話の長押し・右上の「…」から） */
    onMarkRead: ((ChapterEntity?, LibraryRepository.ReadMark) -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<BookmarkEntity?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bookmarkedChapters = bookmarks.map { it.chapterId }.toSet()
    val titleOf = chapters.associate { it.chapterId to it.title }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            // 見出し
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onClose) { Icon(WIcons.Back, contentDescription = "読書に戻る", modifier = Modifier.size(24.dp)) }
                Text(
                    novelTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onRefresh != null) {
                    IconButton(
                        enabled = !refreshing,
                        onClick = {
                            refreshing = true
                            scope.launch {
                                try { onRefresh() } finally { refreshing = false }
                            }
                        },
                    ) {
                        if (refreshing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(WIcons.Refresh, contentDescription = "更新を確認", modifier = Modifier.size(24.dp))
                    }
                }
                if (onDownload != null) {
                    IconButton(onClick = { if (download?.running == true) onStopDownload() else onDownload() }) {
                        Icon(
                            if (download?.running == true) WIcons.Pause else WIcons.Download,
                            contentDescription = if (download?.running == true) "保存を止める" else "全話を保存",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                // すべての話の既読・未読をまとめて付け替える
                if (onMarkRead != null) {
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(WIcons.More, contentDescription = "既読の管理", modifier = Modifier.size(24.dp))
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("すべて既読にする") },
                                onClick = { moreOpen = false; onMarkRead(null, LibraryRepository.ReadMark.ALL_READ) },
                            )
                            DropdownMenuItem(
                                text = { Text("すべて未読にする") },
                                onClick = { moreOpen = false; onMarkRead(null, LibraryRepository.ReadMark.ALL_UNREAD) },
                            )
                        }
                    }
                }
            }

            // 全話の保存の進み具合（CACHE-15）
            download?.let { d ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            d.running -> "保存中　${d.done} / ${d.total} 話（止めても、あとで続きから保存できます）"
                            d.message != null -> d.message
                            else -> "${d.done} / ${d.total} 話を保存済み"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 上部のタブ（TOC-10）
            Row(Modifier.fillMaxWidth()) {
                listOf("目次", "栞（${bookmarks.size}）").forEachIndexed { i, label ->
                    val selected = tab == i
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { tab = i }
                            .padding(top = 10.dp),
                    ) {
                        Text(
                            label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                        )
                    }
                }
            }
            HorizontalDivider()

            if (tab == 0) {
                ChapterList(
                    chapters = chapters,
                    currentChapterId = currentChapterId,
                    bookmarkedChapters = bookmarkedChapters,
                    onOpenChapter = onOpenChapter,
                    onMarkRead = onMarkRead,
                )
            } else {
                if (bookmarks.isEmpty()) {
                    Text(
                        "栞はまだありません。読書中に画面の中央をタップして「栞を付ける」を押すと、そのページに栞が付きます。",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(bookmarks, key = { it.id }) { b ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenBookmark(b) },
                                    onLongClick = { confirmDelete = b },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Row {
                                Text(
                                    titleOf[b.chapterId] ?: "第${b.chapterId}話",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(b.createdAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                b.excerpt.ifBlank { "（本文の位置）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("栞を削除") },
            text = { Text("「${b.excerpt.take(20)}…」の栞を削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDeleteBookmark(b.id)
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("やめる") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterList(
    chapters: List<ChapterEntity>,
    currentChapterId: String,
    bookmarkedChapters: Set<String>,
    onOpenChapter: (String) -> Unit,
    onMarkRead: ((ChapterEntity?, LibraryRepository.ReadMark) -> Unit)?,
) {
    // 開いた時点で、今読んでいる話が見える位置までスクロールしておく
    val listState = rememberLazyListState()
    LaunchedEffect(chapters.size) {
        val index = chapters.indexOfFirst { it.chapterId == currentChapterId }
        if (index > 2) listState.scrollToItem(index - 2)
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(chapters, key = { it.id }) { ch ->
            val prevSection = chapters.getOrNull(ch.order - 1)?.sectionTitle
            // 章の見出し（TOC-04）
            if (ch.sectionTitle != null && ch.sectionTitle != prevSection) {
                Text(
                    ch.sectionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            val current = ch.chapterId == currentChapterId
            // 長押しで、この話を基準に既読・未読を付け替える
            var markMenu by remember { mutableStateOf(false) }
            Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                    .combinedClickable(
                        onClick = { onOpenChapter(ch.chapterId) },
                        onLongClick = { if (onMarkRead != null) markMenu = true },
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    ch.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (ch.isRead) FontWeight.Normal else FontWeight.Bold,
                    color = if (ch.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (bookmarkedChapters.contains(ch.chapterId)) {
                    Text(
                        "栞",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(Color(0xFFC0392B))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    when {
                        current -> "読書中"
                        ch.isRead -> "既読"
                        else -> "未読"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current || !ch.isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
            }
            if (onMarkRead != null) {
                DropdownMenu(expanded = markMenu, onDismissRequest = { markMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("ここまで既読にする") },
                        onClick = { markMenu = false; onMarkRead(ch, LibraryRepository.ReadMark.UP_TO_READ) },
                    )
                    DropdownMenuItem(
                        text = { Text("ここから未読にする") },
                        onClick = { markMenu = false; onMarkRead(ch, LibraryRepository.ReadMark.FROM_UNREAD) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (ch.isRead) "この話だけ未読にする" else "この話だけ既読にする") },
                        onClick = {
                            markMenu = false
                            onMarkRead(ch, if (ch.isRead) LibraryRepository.ReadMark.THIS_UNREAD else LibraryRepository.ReadMark.THIS_READ)
                        },
                    )
                }
            }
            }
            HorizontalDivider()
        }
    }
}
