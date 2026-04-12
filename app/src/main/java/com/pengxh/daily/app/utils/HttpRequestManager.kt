package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.pengxh.daily.app.BuildConfig
import com.pengxh.kt.lite.extensions.timestampToDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HttpRequestManager(private val context: Context) {

    private val kTag = "HttpRequestManager"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val batteryManager by lazy { context.getSystemService(BatteryManager::class.java) }

    fun sendMessage(title: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val webhookKey = SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String
            if (webhookKey.isBlank()) {
                Log.e(kTag, "企业微信 Webhook Key 未配置")
                return@launch
            }

            val url = "${Constant.WX_WEB_HOOK_URL}/cgi-bin/webhook/send?key=$webhookKey"

            val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val content = buildString {
                appendLine(title)
                appendLine(message)
                appendLine("当前日期：${System.currentTimeMillis().timestampToDate()}")
                appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
                append("版本号：${BuildConfig.VERSION_NAME}")
            }

            val jsonBody = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().apply {
                    put("content", content)
                })
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder().url(url).post(requestBody).build()

            try {
                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body.string()
                Log.d(kTag, "响应: $responseBody")
            } catch (e: Exception) {
                Log.e(kTag, "发送失败", e)
            }
        }
    }
}