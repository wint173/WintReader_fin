package com.example.wintreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** GitHub の Releases にある新しい版 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

/**
 * アプリ内アップデート。GitHub Actions が Releases に置いた APK（タグ build-<実行番号>）を確かめて、
 * 今の版より新しければダウンロードし、Android のインストール画面を開く。
 * 版の番号は build.gradle.kts と同じ決まり（versionCode = 実行番号 + 1）。
 * リポジトリの名前は GitHub Actions でのビルドのときだけ入る（BuildConfig.GITHUB_REPO）。
 */
object Updater {

    /** アップデートを確かめられるビルドか（PC でのビルドでは確かめない） */
    val enabled: Boolean get() = BuildConfig.GITHUB_REPO.isNotBlank()

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 最新の版を調べる。今より新しければ返し、同じか古ければ null */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "WintReader-updater")
            .build()
        val body = client.newCall(request).execute().use { res ->
            if (res.code == 404) return@withContext null // まだリリースがない
            if (!res.isSuccessful) throw IOException("GitHub から版を確かめられませんでした（HTTP ${res.code}）")
            res.body?.string() ?: throw IOException("空の応答")
        }
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@withContext null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val tag = root.str("tag_name") ?: return@withContext null
        val run = Regex("""build-(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
        val code = run + 1
        if (code <= BuildConfig.VERSION_CODE) return@withContext null
        val apk = (root["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("name")?.endsWith(".apk") == true }
            ?.str("browser_download_url") ?: return@withContext null
        UpdateInfo(code, "1.0.$run", apk, root.str("body").orEmpty().trim())
    }

    /** APK をダウンロードする（進み具合は 0〜1 で知らせる） */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 前にダウンロードした版は消す
        val file = File(dir, "WintReader-${info.versionName}.apk")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "WintReader-updater").build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("ダウンロードできませんでした（HTTP ${res.code}）")
            val body = res.body ?: throw IOException("空の応答")
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** このアプリが APK をインストールしてよいと許可されているか */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 「この提供元のアプリを許可」の設定画面を開く */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Android のインストール画面を開く（インストールするかどうかは利用者が決める） */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * 新しい版があるときのダイアログ。「アップデート」でダウンロードしてインストール画面を開く。
 * 初めてのときは「この提供元のアプリを許可」の設定を開いてもらう
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) } // -1 = まだダウンロードしていない
    var error by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }

    fun installOrAskPermission(file: File) {
        if (Updater.canInstall(context)) Updater.install(context, file)
        else Updater.openInstallPermission(context)
    }

    AlertDialog(
        onDismissRequest = { if (progress < 0f || downloaded != null || error != null) onDismiss() },
        title = { Text("新しい版があります") },
        text = {
            Column {
                Text("今の版：${Updater.currentVersionName}\n新しい版：${info.versionName}")
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                when {
                    error != null -> {
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    downloaded != null -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (Updater.canInstall(context)) "ダウンロードしました。インストール画面で「アップデート」を押してください。"
                            else "インストールには「この提供元のアプリを許可」が必要です。設定で許可してから、もう一度「インストール」を押してください。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    progress >= 0f -> {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            val file = downloaded
            when {
                file != null -> TextButton(onClick = { installOrAskPermission(file) }) { Text("インストール") }
                progress >= 0f && error == null -> TextButton(onClick = {}, enabled = false) { Text("ダウンロード中…") }
                else -> TextButton(onClick = {
                    error = null
                    progress = 0f
                    scope.launch {
                        runCatching { Updater.download(context, info) { p -> progress = p } }
                            .onSuccess { f ->
                                downloaded = f
                                installOrAskPermission(f)
                            }
                            .onFailure { error = it.message ?: "ダウンロードできませんでした"; progress = -1f }
                    }
                }) { Text("アップデート") }
            }
        },
        dismissButton = {
            if (progress < 0f || downloaded != null || error != null) TextButton(onClick = onDismiss) { Text("あとで") }
        },
    )
}
