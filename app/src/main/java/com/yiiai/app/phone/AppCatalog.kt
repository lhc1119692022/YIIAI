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

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
