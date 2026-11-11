package com.yiiai.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 检查更新与自主更新。
 *
 * 更新源是 GitHub Releases：`releases/latest` 给出最新版本号和 APK 下载地址，
 * 不用自己搭服务端。下载走 OkHttp，装完由系统安装器接管。
 *
 * 注意：Android 8+ 要求「安装未知来源应用」权限，且必须用 FileProvider 把
 * 缓存目录里的 APK 暴露给安装器，不能直接传 file://。
 */
object Updater {

    /** GitHub 仓库坐标。改仓库时只改这里。 */
    private const val OWNER = "lhc1119692022"
    private const val REPO = "YIIAI"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    const val APK_MIME = "application/vnd.android.package-archive"

    private const val TAG = "YIIAI.Updater"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
    )

    sealed interface UpdateResult {
        /** 已是最新。 */
        data class UpToDate(val current: String) : UpdateResult

        /** 有新版本。 */
        data class Available(val release: Release, val current: String) : UpdateResult

        data class Failed(val message: String) : UpdateResult
    }

    /**
     * 查最新 Release。
     * [includePrerelease] 为 true 时连 alpha/beta 一起看——本项目的版本号都带 alpha，
     * 所以要退回到 releases 列表里挑，而不是只看 latest（latest 会跳过预发布）。
     */
    suspend fun check(currentVersion: String, includePrerelease: Boolean = true): UpdateResult =
        withContext(Dispatchers.IO) {
            val json = runCatching {
                val url = if (includePrerelease) {
                    "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
                } else {
                    API_LATEST
                }
                val body = get(url) ?: return@withContext UpdateResult.Failed("无法连接更新服务器")
                if (includePrerelease) {
                    val arr = org.json.JSONArray(body)
                    // 按发布时间倒序取第一条带 APK 的。
                    val first = (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { !it.optBoolean("draft", false) && assetsOf(it).isNotEmpty() }
                        ?: return@withContext UpdateResult.Failed("还没有可用的安装包")
                    first.toString()
                } else {
                    body
                }
            }.getOrElse { return@withContext UpdateResult.Failed(it.message ?: "检查更新失败") }

            parse(json, currentVersion)
        }

    private fun parse(body: String, current: String): UpdateResult = runCatching {
        val o = JSONObject(body)
        val tag = o.optString("tag_name").removePrefix("v")
        val assets = assetsOf(o)
        val apk = assets.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return UpdateResult.Failed("这个版本没有附带安装包")
        val release = Release(
            version = tag.ifBlank { o.optString("name") },
            notes = o.optString("body").trim(),
            apkUrl = apk.optString("browser_download_url"),
            apkSize = apk.optLong("size"),
            pageUrl = o.optString("html_url"),
        )
        if (compareVersions(release.version, current) > 0) {
            UpdateResult.Available(release, current)
        } else {
            UpdateResult.UpToDate(current)
        }
    }.getOrElse { UpdateResult.Failed(it.message ?: "解析更新信息失败") }

    private fun assetsOf(o: JSONObject): List<JSONObject> {
        val arr = o.optJSONArray("assets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YIIAI-Android")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()

    /** 语义化比较：0.3.1-alpha > 0.3.0-alpha；带后缀的比同号正式版低。 */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, String> {
            val core = v.substringBefore('-')
            val suffix = v.substringAfter('-', "")
            val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
            return nums to suffix
        }
        val (na, sa) = parts(a)
        val (nb, sb) = parts(b)
        val maxLen = maxOf(na.size, nb.size)
        for (i in 0 until maxLen) {
            val x = na.getOrElse(i) { 0 }
            val y = nb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // 数字相同：无后缀的正式版更高。
        return when {
            sa == sb -> 0
            sa.isEmpty() -> 1
            sb.isEmpty() -> -1
            else -> sa.compareTo(sb)
        }
    }

    /** 下载 APK 到缓存目录。返回文件或 null。 */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // 只保留最新一个包，避免缓存越积越多。
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "YIIAI-${release.version}.apk")
            val req = Request.Builder().url(release.apkUrl).header("User-Agent", "YIIAI-Android").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败（HTTP ${resp.code}）")
                val body = resp.body ?: error("下载内容为空")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            out
        }
    }

    /** 能否安装未知来源应用。 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 申请安装：用 PackageInstaller 会话，把下载好的 APK 交给系统安装器。
     *
     * 之前用 FileProvider + ACTION_VIEW 在部分 ROM 上会让 App 被顶掉却不安装；
     * PackageInstaller 是官方推荐路径，安装界面由系统接管，App 可以留在后台。
     * 返回 sessionId，或 -1 表示失败。
     */
    fun requestInstall(context: Context, apk: File, onStatus: (String) -> Unit = {}): Int = runCatching {
        val manager = context.packageManager
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = manager.packageInstaller.createSession(params)
        val session = manager.packageInstaller.openSession(sessionId)
        session.use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
            }
            val intent = android.content.Intent(context, InstallResultReceiver::class.java).apply {
                setPackage(context.packageName)
            }
            val pending = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pending.intentSender)
        }
        onStatus("已交给系统安装器")
        sessionId
    }.getOrElse {
        Log.w(TAG, "requestInstall failed", it)
        onStatus("安装请求失败：${it.message}")
        -1
    }

    /**
     * 交给系统浏览器 / 下载器去下载安装包（兜底路径）。
     */
    fun openInBrowser(context: Context, release: Release): Boolean = runCatching {
        val url = release.pageUrl.ifBlank { release.apkUrl }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

}
