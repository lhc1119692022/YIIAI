package com.yiiai.app.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * 应用清单。
 *
 * 之前只靠 `queryIntentActivities(MAIN/LAUNCHER)` 取列表，问题有两个：
 * 1. 没有 `QUERY_ALL_PACKAGES` 时，Android 11+ 只会返回少量可见应用；
 * 2. 用户冻结、隐藏或停用的应用不一定有 launcher intent，就查不到。
 *
 * 现在用「包管理器全量枚举 + 解析启动入口」的方式，并缓存结果。
 */
object AppCatalog {

    data class Entry(
        val label: String,
        /** 启动用的包名。 */
        val packageName: String,
        /** 具体 Activity，可能为空（用包名即可启动）。 */
        val activity: String?,
    ) {
        override fun toString() = "$label（$packageName）"
    }

    @Volatile
    private var cache: List<Entry> = emptyList()

    @Volatile
    private var cachedAt = 0L

    private const val TTL_MS = 5 * 60 * 1000L

    /** 全部可启动应用，按名称排序。已安装/卸载后 5 分钟内会自动刷新。 */
    fun all(context: Context, force: Boolean = false): List<Entry> {
        val now = System.currentTimeMillis()
        if (!force && cache.isNotEmpty() && now - cachedAt < TTL_MS) return cache
        val list = runCatching { enumerate(context) }.getOrElse { emptyList() }
        if (list.isNotEmpty()) {
            cache = list
            cachedAt = now
        }
        return list
    }

    fun invalidate() {
        cachedAt = 0L
    }

    private fun enumerate(context: Context): List<Entry> {
        val manager = context.packageManager
        val self = context.packageName
        val entries = LinkedHashMap<String, Entry>()

        // 1) 全量已安装包：能覆盖被隐藏、被冻结、没有 launcher intent 的应用。
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getInstalledPackages(0)
        }
        for (info in installed) {
            val pkg = info.packageName
            if (pkg == self) continue
            val appInfo = info.applicationInfo ?: continue
            val label = runCatching { manager.getApplicationLabel(appInfo)?.toString()?.trim() }
                .getOrNull()
                .orEmpty()
            if (label.isEmpty()) continue
            val launch = runCatching { manager.getLaunchIntentForPackage(pkg) }.getOrNull()
            // 没有启动入口的（纯服务、插件、输入法）不进清单，打开也没意义。
            if (launch == null && !hasLaunchActivity(manager, info)) continue
            entries[pkg] = Entry(label, pkg, launch?.component?.className)
        }

        // 2) 兜底：再按 launcher intent 查一遍，补上个别没有包信息的入口。
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            manager.queryIntentActivities(intent, 0).forEach { ris ->
                val pkg = ris.activityInfo?.packageName ?: return@forEach
                if (pkg == self) return@forEach
                val label = runCatching { ris.loadLabel(manager)?.toString()?.trim() }.getOrNull().orEmpty()
                if (label.isEmpty()) return@forEach
                entries.putIfAbsent(pkg, Entry(label, pkg, ris.activityInfo?.name))
            }
        }

        return entries.values.sortedBy { it.label.lowercase() }
    }

    private fun hasLaunchActivity(manager: PackageManager, info: android.content.pm.PackageInfo): Boolean {
        val activities = info.activities ?: return false
        return activities.any { it.exported && it.enabled }
    }

    /**
     * 按名字或包名查找；精确 → 去空格同名 → 名称包含关键词。
     *
     * 注意不能反过来做「关键词包含名称」的匹配：应用名里有单字母的（比如「X」），
     * 任何带这个字母的输入都会被它抢走，结果打开一个完全不相干的应用。
     */
    fun find(context: Context, query: String): Entry? {
        val key = query.trim()
        if (key.isEmpty()) return null
        val list = all(context)
        val squash = { s: String -> s.replace(" ", "").lowercase() }
        list.firstOrNull { it.packageName.equals(key, ignoreCase = true) }?.let { return it }
        list.firstOrNull { it.label.equals(key, ignoreCase = true) }?.let { return it }
        list.firstOrNull { squash(it.label) == squash(key) }?.let { return it }
        // 关键词至少两个字才做包含匹配，避免单字误伤。
        if (squash(key).length >= 2) {
            val hits = list.filter { squash(it.label).contains(squash(key)) }
            // 多个都命中时取名字最短的那个（最贴近原词）。
            return hits.minByOrNull { it.label.length }
        }
        return null
    }

    /** 关键词搜索，给模型「找一下这个 App 装没装」用。 */
    fun search(context: Context, query: String, limit: Int = 12): List<Entry> {
        val key = query.trim()
        val list = all(context)
        if (key.isEmpty()) return list.take(limit)
        val squash = { s: String -> s.replace(" ", "").lowercase() }
        return list.filter {
            squash(it.label).contains(squash(key)) || it.packageName.contains(key, ignoreCase = true)
        }.take(limit)
    }

    /**
     * 启动应用。返回一句给模型看的结果。
     * 打不开时尽量说清楚原因，并给出相近的名字便于重试。
     */
    fun launch(context: Context, nameOrPackage: String): String {
        val manager = context.packageManager
        val key = nameOrPackage.trim()
        if (key.isEmpty()) return "没有给出应用名"

        // 包名直连
        if (key.contains('.')) {
            runCatching { manager.getLaunchIntentForPackage(key) }.getOrNull()?.let { intent ->
                return if (start(context, intent)) "已打开 $key" else "打开 $key 失败（可能被系统限制或应用已停用）"
            }
        }

        val entry = find(context, key)
        if (entry == null) {
            val similar = search(context, key, 5).map { it.label }
            val hint = if (similar.isEmpty()) {
                // 完全没线索：告诉他现在装了哪些，方便直接点名。
                "设备上能启动的应用共 ${all(context).size} 个，例如：" +
                    all(context).take(20).joinToString("、") { it.label }
            } else {
                "相近的有：${similar.joinToString("、")}。"
            }
            return "没找到叫「$key」的应用。$hint"
        }

        val intent = runCatching { manager.getLaunchIntentForPackage(entry.packageName) }.getOrNull()
            ?: entry.activity?.let { cls ->
                Intent().apply {
                    setClassName(entry.packageName, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        if (intent == null) return "「${entry.label}」没有可启动的界面"
        return if (start(context, intent)) {
            "已打开 ${entry.label}"
        } else {
            // 被隐藏/冻结的应用会抛异常，这里如实回报。
            "「${entry.label}」打不开：应用可能已被冻结、隐藏或停用，需要先在系统里恢复"
        }
    }

    /**
     * 启动并**确认真的到了前台**。
     *
     * 之前只判断 `startActivity` 没抛异常就回「已打开」，但 Android 10+ 的后台启动限制
     * 会让启动被系统静默拦截——不抛异常、也没打开。所以要等一下再看前台包名。
     */
    suspend fun launchVerified(context: Context, nameOrPackage: String): String {
        val entry = find(context, nameOrPackage)
        if (entry == null) return notFoundMessage(context, nameOrPackage)
        val manager = context.packageManager
        val intent = manager.getLaunchIntentForPackage(entry.packageName)
            ?: entry.activity?.let { cls ->
                Intent().apply { setClassName(entry.packageName, cls) }
            }
            ?: return "「${entry.label}」没有可启动的界面"

        // 第一轮：常规启动 + 等待确认
        start(context, intent)
        if (awaitForeground(context, entry.packageName, 1800)) return "已打开 ${entry.label}"

        // 第二轮：补上 RESET_TASK_IF_NEEDED，有些 Launcher 入口需要它才会切到已有任务
        start(
            context,
            Intent(intent).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        )
        if (awaitForeground(context, entry.packageName, 1800)) return "已打开 ${entry.label}"

        // 第三轮：让无障碍服务按名字点桌面图标（绕过后台启动限制）
        val svc = PhoneUseService.instance
        if (svc != null) {
            svc.pressHome()
            kotlinx.coroutines.delay(900)
            // 桌面可能需要翻页或搜索：先尝试直接点名字，再尝试在抽屉里找。
            var clicked = svc.tapNodeText(entry.label)
            if (!clicked) {
                // 很多桌面把所有应用放在上滑抽屉里，先上滑再找。
                svc.swipe(720f, 2200f, 720f, 700f, 320)
                kotlinx.coroutines.delay(700)
                clicked = svc.tapNodeText(entry.label)
            }
            if (clicked && awaitForeground(context, entry.packageName, 2500)) {
                return "已打开 ${entry.label}"
            }
        }

        // 仍然没到前台：如实报告，别再说「已打开」。
        val blocked = !hasOverlayPermission(context)
        return buildString {
            append("没能打开「${entry.label}」（包名 ${entry.packageName}）。")
            if (isStartConfirmShowing(context)) {
                append("系统弹出了「是否允许 YIIAI 启动应用」的确认框，需要用户点『允许』。")
                append("请让用户看一眼屏幕并点允许，然后再试一次。")
            } else if (blocked) {
                append("YIIAI 没有「显示在其他应用上层」权限，Android 会拦截后台启动应用。")
                append("请告诉用户：到系统设置给 YIIAI 开启悬浮窗权限后重试；")
            } else if (isStopped(context, entry.packageName)) {
                append("这个应用当前处于「强制停止」状态（系统里 stopped=true），")
                append("Android 不允许其他应用把它拉起来。请让用户自己在桌面点一次打开它，")
                append("之后 YIIAI 就能正常打开了。")
            } else {
                append("启动被系统拦下了，可能是应用需要登录、正在更新，或被系统冻结。")
            }
            append("不要对用户说已打开，如实说明没打开。")
        }
    }

    /** 应用是否处于「强制停止」状态：这种状态下第三方无法启动它。 */
    private fun isStopped(context: Context, pkg: String): Boolean = runCatching {
        val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }
        (info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(false)

    private fun hasOverlayPermission(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    /**
     * 等目标应用真正到前台。
     *
     * HyperOS 在 App 拉起别的应用时会弹 `ConfirmStartActivity`（"是否允许 YIIAI 启动…"），
     * 这时前台是 com.miui.securitycenter。以前把它当失败直接返回，用户还没看清弹窗
     * 就被告知"没打开"；现在会一直等到用户点完确认（最多 [confirmTimeoutMs]）。
     */
    private suspend fun awaitForeground(
        context: Context,
        pkg: String,
        timeoutMs: Long,
        confirmTimeoutMs: Long = 15_000L,
    ): Boolean {
        val step = 150L
        var waited = 0L
        var confirming = false
        while (waited < (if (confirming) confirmTimeoutMs else timeoutMs)) {
            ForegroundApp.invalidate()
            val top = ForegroundApp.current(context)
            if (top == pkg) return true
            // HyperOS / MIUI 的启动确认弹窗
            if (top == "com.miui.securitycenter" || top == "com.miui.packageinstaller") {
                confirming = true
            }
            kotlinx.coroutines.delay(step)
            waited += step
        }
        return false
    }

    /** 当前是不是停在系统的「允许启动」确认框上。 */
    private fun isStartConfirmShowing(context: Context): Boolean =
        ForegroundApp.current(context) in setOf("com.miui.securitycenter", "com.miui.packageinstaller")

    private fun notFoundMessage(context: Context, key: String): String {
        val similar = search(context, key, 5).map { it.label }
        return if (similar.isEmpty()) {
            "没找到叫「$key」的应用。设备上能启动的应用共 ${all(context).size} 个，例如：" +
                all(context).take(20).joinToString("、") { it.label }
        } else {
            "没找到叫「$key」的应用。相近的有：${similar.joinToString("、")}。"
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
