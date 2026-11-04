package com.example.wintreader

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- 本文の保存（要件定義書 8 / 10.4）

/**
 * 本文（共通本文モデルの JSON）をアプリ専用の領域に保存する。
 * 置き場所：files/novels/{siteId}/{workId}/{chapterId}.json
 */
class ChapterStore(context: Context) {
    private val root = File(context.filesDir, "novels")

    private fun dirOf(novelId: String): File {
        val (site, work) = novelId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "_" } }
        return File(File(root, safe(site)), safe(work))
    }

    private fun fileOf(novelId: String, chapterId: String) = File(dirOf(novelId), "${safe(chapterId)}.json")

    /** ファイル名に使えない文字を置き換える */
    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    suspend fun save(novelId: String, doc: NovelDocument) = withContext(Dispatchers.IO) {
        val file = fileOf(novelId, doc.chapterId)
        file.parentFile?.mkdirs()
        // 書きかけのファイルが残らないよう、一時ファイルに書いてから置き換える
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ReaderJson.encodeToString(NovelDocument.serializer(), doc))
        tmp.renameTo(file)
    }

    suspend fun load(novelId: String, chapterId: String): NovelDocument? = withContext(Dispatchers.IO) {
        val file = fileOf(novelId, chapterId)
        if (!file.exists()) return@withContext null
        runCatching { ReaderJson.decodeFromString(NovelDocument.serializer(), file.readText()) }.getOrNull()
    }

    suspend fun deleteNovel(novelId: String) = withContext(Dispatchers.IO) {
        dirOf(novelId).deleteRecursively()
    }

    /** 保存した本文の合計サイズ（バイト） */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 保存した本文をすべて消す */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
    }
}

// ---------------------------------------------------------------- アプリの設定（SET / WEB-15）

/** アプリの見た目（SET-01）。読書画面の色は表示設定で別に選ぶ */
enum class ThemeMode(val label: String) { SYSTEM("端末に合わせる"), LIGHT("ライト"), DARK("ダーク") }

/** 本棚に追加したあとの動作（WEB-15） */
enum class AfterAddAction(val label: String) {
    STAY_ON_SITE("サイトに残る"),
    BACK_TO_LIBRARY("本棚に戻る"),
    OPEN_NOVEL("その作品を開く"),
}

data class AppPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val afterAdd: AfterAddAction = AfterAddAction.STAY_ON_SITE,
    /** 読んでいる話の次を何話先に取っておくか（CACHE-02） */
    val prefetchCount: Int = 2,
)

/** 文庫タブのサイトの並び順と、表示しないサイト */
data class BunkoLayout(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/** 読書メーター（HELP-01〜03） */
data class ReadingStats(
    val readingMillis: Long = 0,
    /** アプリを使った日の合計 */
    val usedDays: Int = 0,
)

// ---------------------------------------------------------------- 表示設定の保存（SET-02）

/** 最初の話より前・最終話より次へ進もうとしたときの動作 */
enum class EdgeAction(val glyph: String, val label: String) {
    /** 更新を確かめ、新しい話があればそのまま次の話へ */
    UPDATE("⟳", "更新を確認"),
    /** 作品のページ（元サイトの目次）を開く */
    WORK_PAGE("↗", "作品のページへ"),
}

/** リーダーの設定。表示設定・操作モード・タップの向き・端の動作は全作品共通 */
data class ReaderPrefs(
    val settings: ReaderSettings = ReaderSettings(),
    val mode: ReadingMode = ReadingMode.TAP,
    val leftTapNext: Boolean = true,
    val edgeAction: EdgeAction = EdgeAction.UPDATE,
    /** 月のアイコンで切り替える夜の配色（UI-11）。解除すると settings の色に戻る */
    val nightMode: Boolean = false,
    /** 読書中の画面の向き（UI-06）："auto" | "portrait" | "landscape" */
    val orientation: String = "auto",
    /** 読書画面だけの明るさ（UI-04）。0.0〜1.0、-1 = 端末の明るさのまま */
    val brightness: Float = -1f,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val keySettings = stringPreferencesKey("reader_settings")
    private val keyMode = stringPreferencesKey("reader_mode")
    private val keyLeftTapNext = booleanPreferencesKey("left_tap_next")
    private val keyEdgeAction = stringPreferencesKey("edge_action")
    private val keyNightMode = booleanPreferencesKey("night_mode")
    private val keyOrientation = stringPreferencesKey("reader_orientation")
    private val keyBrightness = floatPreferencesKey("reader_brightness")
    private val keyTheme = stringPreferencesKey("theme")
    private val keyAfterAdd = stringPreferencesKey("after_add")
    private val keyPrefetch = intPreferencesKey("prefetch_count")
    private val keyReadingMillis = longPreferencesKey("reading_millis")
    private val keyUsedDays = intPreferencesKey("used_days")
    private val keyLastUsedDay = stringPreferencesKey("last_used_day")
    private val keySampleInserted = booleanPreferencesKey("sample_inserted")
    private val keyBunkoOrder = stringPreferencesKey("bunko_order")
    private val keyBunkoHidden = stringPreferencesKey("bunko_hidden")

    val readerPrefs: Flow<ReaderPrefs> = context.settingsDataStore.data.map { p ->
        ReaderPrefs(
            settings = p[keySettings]
                ?.let { runCatching { ReaderJson.decodeFromString(ReaderSettings.serializer(), it) }.getOrNull() }
                ?: ReaderSettings(),
            mode = p[keyMode]?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() } ?: ReadingMode.TAP,
            leftTapNext = p[keyLeftTapNext] ?: true,
            edgeAction = p[keyEdgeAction]?.let { runCatching { EdgeAction.valueOf(it) }.getOrNull() } ?: EdgeAction.UPDATE,
            nightMode = p[keyNightMode] ?: false,
            orientation = p[keyOrientation] ?: "auto",
            brightness = p[keyBrightness] ?: -1f,
        )
    }

    // 本棚の未分類作品の並び順（LIB-20〜23）。カテゴリ内の並び順はカテゴリごとに持つ
    private val keyLibrarySort = stringPreferencesKey("library_sort")

    val librarySort: Flow<SortMode> = context.settingsDataStore.data.map { p ->
        p[keyLibrarySort]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.RECENT
    }

    suspend fun saveLibrarySort(mode: SortMode) {
        context.settingsDataStore.edit { it[keyLibrarySort] = mode.name }
    }

    // ------------------------------------------------ アプリの設定

    val appPrefs: Flow<AppPrefs> = context.settingsDataStore.data.map { p ->
        AppPrefs(
            theme = p[keyTheme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            afterAdd = p[keyAfterAdd]?.let { runCatching { AfterAddAction.valueOf(it) }.getOrNull() } ?: AfterAddAction.STAY_ON_SITE,
            prefetchCount = p[keyPrefetch] ?: 2,
        )
    }

    suspend fun saveAppPrefs(prefs: AppPrefs) {
        context.settingsDataStore.edit { p ->
            p[keyTheme] = prefs.theme.name
            p[keyAfterAdd] = prefs.afterAdd.name
            p[keyPrefetch] = prefs.prefetchCount
        }
    }

    // ------------------------------------------------ 文庫タブ

    val bunkoLayout: Flow<BunkoLayout> = context.settingsDataStore.data.map { p ->
        BunkoLayout(
            order = p[keyBunkoOrder]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            hidden = p[keyBunkoHidden]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        )
    }

    suspend fun saveBunkoLayout(layout: BunkoLayout) {
        context.settingsDataStore.edit { p ->
            p[keyBunkoOrder] = layout.order.joinToString(",")
            p[keyBunkoHidden] = layout.hidden.joinToString(",")
        }
    }

    // ------------------------------------------------ 読書メーター

    val stats: Flow<ReadingStats> = context.settingsDataStore.data.map { p ->
        ReadingStats(readingMillis = p[keyReadingMillis] ?: 0L, usedDays = p[keyUsedDays] ?: 0)
    }

    /** 読書時間を足す（読書画面が前面に出ていた時間） */
    suspend fun addReadingTime(millis: Long) {
        if (millis <= 0) return
        context.settingsDataStore.edit { p -> p[keyReadingMillis] = (p[keyReadingMillis] ?: 0L) + millis }
    }

    /** 今日アプリを使ったことを記録する（その日の初回だけ日数を1増やす） */
    suspend fun markUsedToday() {
        val today = java.time.LocalDate.now().toString()
        context.settingsDataStore.edit { p ->
            if (p[keyLastUsedDay] != today) {
                p[keyLastUsedDay] = today
                p[keyUsedDays] = (p[keyUsedDays] ?: 0) + 1
            }
        }
    }

    suspend fun resetStats() {
        context.settingsDataStore.edit { p ->
            p.remove(keyReadingMillis)
            p.remove(keyUsedDays)
            p.remove(keyLastUsedDay)
        }
    }

    // ------------------------------------------------ サンプル作品

    /** サンプル作品を一度入れたか（消したあとにまた入らないようにする） */
    suspend fun sampleInserted(): Boolean = context.settingsDataStore.data.map { it[keySampleInserted] ?: false }.first()

    suspend fun markSampleInserted() {
        context.settingsDataStore.edit { it[keySampleInserted] = true }
    }

    /** 設定をすべて消す（SET-07）。サンプル作品はもう入れない */
    suspend fun clearAll() {
        context.settingsDataStore.edit { p ->
            p.clear()
            p[keySampleInserted] = true
        }
    }

    suspend fun save(prefs: ReaderPrefs) {
        context.settingsDataStore.edit { p ->
            p[keySettings] = ReaderJson.encodeToString(ReaderSettings.serializer(), prefs.settings)
            p[keyMode] = prefs.mode.name
            p[keyLeftTapNext] = prefs.leftTapNext
            p[keyEdgeAction] = prefs.edgeAction.name
            p[keyNightMode] = prefs.nightMode
            p[keyOrientation] = prefs.orientation
            p[keyBrightness] = prefs.brightness
        }
    }
}
