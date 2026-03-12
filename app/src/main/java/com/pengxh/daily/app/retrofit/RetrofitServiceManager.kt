package com.pengxh.daily.app.retrofit

import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.RetrofitFactory
import com.pengxh.kt.lite.utils.SaveKeyValues
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object RetrofitServiceManager {
    private val api by lazy {
        RetrofitFactory.createRetrofit<RetrofitService>(Constant.WX_WEB_HOOK_URL)
    }

    suspend fun sendMessage(content: String): String {
        val jsonBody = JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().apply {
                put("content", content)
            })
        }

        val requestBody = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val keyMap = HashMap<String, String>()
        keyMap["key"] = SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String
        return api.sendMessage(requestBody, keyMap)
    }
}