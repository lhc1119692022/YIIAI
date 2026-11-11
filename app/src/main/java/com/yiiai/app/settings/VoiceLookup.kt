package com.yiiai.app.settings

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 按音色 ID 查音色名。
 * 接口：GET /v1/audio/voices?limit=100&offset=N，返回 {data:[{id,name}], has_more}。
 */
object VoiceLookup {
    private const val TAG = "YIIAI.Voice"
    private const val BASE = "https://api.mosi.cn/v1/audio/voices"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val idKeys = listOf("id", "voice_id", "voiceId", "voice_name", "uuid")
    private val nameKeys = listOf("name", "display_name", "displayName", "title", "label", "nickname", "voice_label")

    sealed interface Result {
        data class Found(val name: String) : Result
        data object NotFound : Result
        data class Failed(val message: String) : Result
    }

    suspend fun lookup(apiKey: String, voiceId: String): Result = withContext(Dispatchers.IO) {
        val id = voiceId.trim()
        if (apiKey.isBlank()) return@withContext Result.Failed("还没有填写 API Key")
        if (id.isEmpty()) return@withContext Result.NotFound
        // 没有按 ID 查单个音色的接口（实测 404），直接翻列表匹配。
        var url: String? = "$BASE?limit=100"
        var pages = 0
        var lastError: String? = null
        while (url != null && pages < 10) {
            val page = get(apiKey, url)
            val body = page.body
            if (body == null) {
                lastError = page.error ?: lastError
                break
            }
            val json = parse(body)
            findName(json, id)?.let { return@withContext Result.Found(it) }
            url = nextPage(json, url)
            pages++
        }
        if (lastError != null && pages == 0) Result.Failed(lastError) else Result.NotFound
    }

    private class Resp(val body: String?, val error: String?)

    private fun get(apiKey: String, url: String): Resp = runCatching {
        http.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer ${apiKey.trim()}").get().build(),
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            Log.d(TAG, "GET $url -> ${r.code} ${text.take(300)} … ${text.takeLast(300)}")
            if (r.isSuccessful) Resp(text, null) else Resp(null, "HTTP ${r.code}")
        }
    }.getOrElse {
        Log.w(TAG, "GET $url failed", it)
        Resp(null, it.message ?: "网络错误")
    }

    private fun parse(body: String): Any? = runCatching {
        val t = body.trim()
        if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    }.getOrNull()

    /** 在任意嵌套结构里找 id 字段等于 [id] 的对象，返回它的名字字段。 */
    private fun findName(node: Any?, id: String): String? {
        when (node) {
            is JSONObject -> {
                val matches = idKeys.any { node.optString(it) == id }
                if (matches) {
                    nameKeys.firstNotNullOfOrNull { k -> node.optString(k).takeIf { it.isNotBlank() && it != id } }
                        ?.let { return it }
                }
                for (key in node.keys()) findName(node.opt(key), id)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findName(node.opt(i), id)?.let { return it }
        }
        return null
    }

    /** 实测：列表接口用 offset 翻页（has_more 为 true 时继续），cursor / after 参数会被忽略或报错。 */
    private fun nextPage(json: Any?, current: String): String? {
        val o = json as? JSONObject ?: return null
        if (!o.optBoolean("has_more", false)) return null
        val count = o.optJSONArray("data")?.length() ?: return null
        if (count == 0) return null
        val offset = Regex("[?&]offset=(\\d+)").find(current)?.groupValues?.get(1)?.toInt() ?: 0
        return current.replace(Regex("&offset=\\d+"), "") + "&offset=" + (offset + count)
    }
}