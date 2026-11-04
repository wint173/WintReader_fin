package com.example.wintreader

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- テーブル（要件定義書 10.1）

/**
 * 本棚の作品。id は "siteId:workId"（タイトルではなくサイトと作品IDで識別する）。
 * 前回の読書位置（READ-35/36）もここに持つ。
 */
@Serializable
@Entity(tableName = "novels")
data class NovelEntity(
    @PrimaryKey val id: String,
    val siteId: String,
    val workId: String,
    val title: String,
    val author: String,
    val sourceUrl: String,
    /** 所属カテゴリ。null = 未分類（1作品につき最大1カテゴリ） */
    val categoryId: Long? = null,
    val addedAt: Long,
    val lastReadAt: Long? = null,
    /** 作品の更新日時（更新順ソート用） */
    val updatedAt: Long? = null,
    val latestChapterTitle: String? = null,
    /** 前回の読書位置 */
    val lastChapterId: String? = null,
    val lastBlock: Int = 0,
    val lastOffset: Int = 0,
) {
    companion object {
        fun idOf(siteId: String, workId: String) = "$siteId:$workId"
    }
}

/** 目次の1話分。id は "novelId/chapterId" */
@Serializable
@Entity(tableName = "chapters", indices = [Index("novelId")])
data class ChapterEntity(
    @PrimaryKey val id: String,
    val novelId: String,
    val chapterId: String,
    val title: String,
    /** 元サイトのページ（READ-19） */
    val url: String,
    /** 目次での並び順（0始まり） */
    val order: Int,
    /** 章のタイトル（章・節の階層 TOC-04） */
    val sectionTitle: String? = null,
    val isRead: Boolean = false,
    /** 本文をファイルに保存済みか */
    val cached: Boolean = false,
)

/** 本棚のカテゴリ（LIB-17〜38） */
@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 手動の並び順 */
    val displayOrder: Int,
    val isExpanded: Boolean = true,
    /** カテゴリ内のソート方式（SortMode の名前） */
    val sortMode: String = SortMode.RECENT.name,
)

/** 栞（ページ単位。中身は本文内の位置で持つ） */
@Serializable
@Entity(tableName = "bookmarks", indices = [Index("novelId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: String,
    val chapterId: String,
    val block: Int,
    val offset: Int,
    /** 栞を付けた所の本文の書き出し（一覧の表示用） */
    val excerpt: String = "",
    val createdAt: Long,
)

/**
 * 本棚に出す、作品ごとの読書の進み具合（LIB-09）。
 * total = 目次の話数（元サイトで消えた話は数えない）、readCount = 既読の話数、currentOrder = 今読んでいる話の並び順（0始まり）
 */
data class ChapterStats(
    val novelId: String,
    val total: Int,
    val readCount: Int,
    val currentOrder: Int?,
)

/** 本棚のソート（LIB-20〜23） */
enum class SortMode { RECENT, UPDATED, ADDED, TITLE }

// ---------------------------------------------------------------- DAO

@Dao
interface NovelDao {
    @Query("SELECT * FROM novels")
    fun observeAll(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun get(id: String): NovelEntity?

    @Query("SELECT COUNT(*) FROM novels")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(novel: NovelEntity)

    @Query("UPDATE novels SET lastChapterId = :chapterId, lastBlock = :block, lastOffset = :offset, lastReadAt = :at WHERE id = :id")
    suspend fun updatePosition(id: String, chapterId: String, block: Int, offset: Int, at: Long)

    @Query("UPDATE novels SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: String, categoryId: Long?)

    @Query("UPDATE novels SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun delete(id: String)

    // バックアップ・復元・全削除（BAK / SET-07）
    @Query("SELECT * FROM novels")
    suspend fun listAll(): List<NovelEntity>

    @Upsert
    suspend fun upsertAll(novels: List<NovelEntity>)

    @Query("DELETE FROM novels")
    suspend fun deleteAll()
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY `order`")
    fun observe(novelId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY `order`")
    suspend fun list(novelId: String): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET isRead = 1 WHERE novelId = :novelId AND chapterId = :chapterId")
    suspend fun markRead(novelId: String, chapterId: String)

    // 既読・未読をまとめて付け替える（目次の長押し・右上の「…」から）
    @Query("UPDATE chapters SET isRead = :read WHERE novelId = :novelId AND chapterId = :chapterId")
    suspend fun setRead(novelId: String, chapterId: String, read: Boolean)

    @Query("UPDATE chapters SET isRead = :read WHERE novelId = :novelId AND `order` <= :order")
    suspend fun setReadUpTo(novelId: String, order: Int, read: Boolean)

    @Query("UPDATE chapters SET isRead = :read WHERE novelId = :novelId AND `order` >= :order")
    suspend fun setReadFrom(novelId: String, order: Int, read: Boolean)

    @Query("UPDATE chapters SET isRead = :read WHERE novelId = :novelId")
    suspend fun setReadAll(novelId: String, read: Boolean)

    @Query("UPDATE chapters SET cached = :cached WHERE novelId = :novelId AND chapterId = :chapterId")
    suspend fun setCached(novelId: String, chapterId: String, cached: Boolean)

    @Query("DELETE FROM chapters WHERE novelId = :novelId")
    suspend fun deleteForNovel(novelId: String)

    @Query("DELETE FROM chapters WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM chapters")
    suspend fun listAll(): List<ChapterEntity>

    @Query(
        """
        SELECT n.id AS novelId,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id AND c.title NOT LIKE '［元サイトで削除］%') AS total,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id AND c.isRead = 1 AND c.title NOT LIKE '［元サイトで削除］%') AS readCount,
          (SELECT c.`order` FROM chapters c WHERE c.novelId = n.id AND c.chapterId = n.lastChapterId) AS currentOrder
        FROM novels n
        """
    )
    fun observeStats(): Flow<List<ChapterStats>>

    @Query("DELETE FROM chapters")
    suspend fun deleteAll()

    /** 保存した本文をすべて消したとき */
    @Query("UPDATE chapters SET cached = 0")
    suspend fun clearAllCached()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY displayOrder")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY displayOrder")
    suspend fun list(): List<CategoryEntity>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Update
    suspend fun updateAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId ORDER BY createdAt DESC")
    fun observe(novelId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId AND chapterId = :chapterId")
    suspend fun listForChapter(novelId: String, chapterId: String): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE novelId = :novelId")
    suspend fun deleteForNovel(novelId: String)

    @Query("SELECT * FROM bookmarks")
    suspend fun listAll(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()
}

// ---------------------------------------------------------------- データベース

@Database(
    entities = [NovelEntity::class, ChapterEntity::class, CategoryEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novels(): NovelDao
    abstract fun chapters(): ChapterDao
    abstract fun categories(): CategoryDao
    abstract fun bookmarks(): BookmarkDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "wint_reader.db")
                // 開発中はスキーマを変えたら作り直す（リリース前にマイグレーションへ切り替える）
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
