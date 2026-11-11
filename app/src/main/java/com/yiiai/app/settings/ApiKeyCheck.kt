package com.yiiai.app.settings

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * API Key 连通性验证。
 *
 * 之前只有「保存」，Key 填错了要等到真正开始通话才报错，而且报错信息藏在通话页。
 * 现在保存前先打一个轻量请求：`GET /v1/audio/voices?limit=1`。
 * 实测：有效 Key 返回 200，无效返回 403 `iam api key auth failed`。
 */
object ApiKeyCheck {

    private const val TAG = "YIIAI.ApiKey"
    private const val URL = "https://api.mosi.cn/v1/audio/voices?limit=1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        /** 连接成功，Key 有效。 */
        data object Ok : Result

        /** Key 无效或已失效。 */
        data class Invalid(val message: String) : Result

        /** 网络问题，无法判断 Key 对错。 */
        data class Network(val message: String) : Result
    }

    suspend fun verify(apiKey: String): Result = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext Result.Invalid("还没有填写 API Key")
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $key")
            .build()
        runCatching {
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                Log.d(TAG, "verify -> ${r.code} ${body.take(200)}")
                when {
                    r.isSuccessful -> Result.Ok
                    r.code == 401 || r.code == 403 -> Result.Invalid(detail(body) ?: "API Key 无效或已失效")
                    r.code == 429 -> Result.Invalid("调用太频繁或额度不足")
                    r.code in 500..599 -> Result.Network("服务端暂时不可用（HTTP ${r.code}）")
                    else -> Result.Invalid("验证失败（HTTP ${r.code}）")
                }
            }
        }.getOrElse {
            Result.Network(it.message ?: "网络连接失败")
        }
    }

    /** 从 MOSS 的错误体里抠出可读信息：{"error":{"code":..,"message":..}} */
    private fun detail(body: String): String? = runCatching {
        val obj = org.json.JSONObject(body).optJSONObject("error") ?: return@runCatching null
        val msg = obj.optString("message").trim()
        when {
            msg.contains("auth failed", ignoreCase = true) -> "API Key 无效或已失效"
            msg.isNotEmpty() -> msg
            else -> null
        }
    }.getOrNull()
}
