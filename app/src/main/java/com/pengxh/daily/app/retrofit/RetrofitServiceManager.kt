package com.pengxh.daily.app.retrofit

import com.google.gson.JsonObject
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.RetrofitFactory
import com.pengxh.kt.lite.utils.SaveKeyValues
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object RetrofitServiceManager {
    private val api by lazy {
        RetrofitFactory.createRetrofit<RetrofitService>(Constant.WX_WEB_HOOK_URL)
    }

    suspend fun sendMessage(content: String): String {
        val param = JsonObject()
        param.addProperty("msgtype", "text")

        val obj = JsonObject()
        obj.addProperty("content", content)
        param.add("text", obj)

        val requestBody = param.toString().toRequestBody(
            "application/json;charset=UTF-8".toMediaType()
        )

        val keyMap = HashMap<String, String>()
        keyMap["key"] = SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String
        return api.sendMessage(requestBody, keyMap)
    }
}