package com.pengxh.daily.app.retrofit

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.QueryMap

interface RetrofitService {
    /**
     * 企业微信推送消息
     * 单机器人每分钟最多 20 条消息，超过限制后立即返回错误码 45009
     */
    @POST("/cgi-bin/webhook/send")
    suspend fun sendMessage(
        @Body requestBody: RequestBody,
        @QueryMap map: Map<String, String>
    ): String
}