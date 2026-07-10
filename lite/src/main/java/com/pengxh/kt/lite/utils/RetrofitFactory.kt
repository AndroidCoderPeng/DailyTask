package com.pengxh.kt.lite.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitFactory {
    const val kTag = "RetrofitFactory"

    /**
     * @param httpConfig 接口地址
     * @param timeout 网络请求超时时间，单位：秒
     * */
    inline fun <reified T> createRetrofit(
        httpConfig: String, timeout: Long = LiteKitConstant.HTTP_TIMEOUT, debug: Boolean = true
    ): T {
        val httpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
        val httpClient = if (debug) {
            val interceptor = HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
                override fun log(message: String) {
                    Log.d(kTag, ">>>>> $message")
                }
            })
            interceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
            httpClientBuilder.addInterceptor(interceptor).build()
        } else {
            httpClientBuilder.build()
        }
        return Retrofit.Builder()
            .baseUrl(httpConfig)
            .addConverterFactory(ScalarsConverterFactory.create())          //字符串转换器
            .addConverterFactory(GsonConverterFactory.create())             //Gson转换器
            .client(httpClient)
            .build()
            .create(T::class.java)
    }
}