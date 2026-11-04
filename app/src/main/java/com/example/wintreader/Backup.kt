package com.example.wintreader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * バックアップファイルの中身（BAK-01）。本棚・カテゴリ・目次（既読の印）・栞・読書位置。
 * 本文は含めない（サイトから取り直せるため）。
 */
@Serializable
data class BackupData(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val novels: List<NovelEntity>,
    val categories: List<CategoryEntity>,
    val chapters: List<ChapterEntity>,
    val bookmarks: List<BookmarkEntity>,
) {
    companion object {
        const val FORMAT = "wintreader-backup"
    }
}

val BackupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
